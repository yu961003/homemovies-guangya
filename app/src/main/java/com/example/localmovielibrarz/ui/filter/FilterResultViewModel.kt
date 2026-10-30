package com.example.localmovielibrarz.ui.filter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrarz.data.local.MovieEntity
import com.example.localmovielibrarz.data.repository.MovieRepository
import com.example.localmovielibrarz.data.repository.Top250RankingRepository
import com.example.localmovielibrarz.util.extractMovieNumberInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

class FilterResultViewModel(
    private val filterType: String,
    private val filterValue: String,
    private val repository: MovieRepository,
    private val top250Repository: Top250RankingRepository? = null
) : ViewModel() {
    private val normalizedType = filterType.lowercase(Locale.ROOT)

    /** "year-2025" -> 2025；"all" -> null（总榜）。非榜单类型恒为 null。 */
    private val rankingYear: Int? =
        if (normalizedType == TOP250_FILTER) filterValue.removePrefix("year-").toIntOrNull() else null

    val uiState: StateFlow<FilterResultUiState> =
        if (normalizedType == TOP250_FILTER && top250Repository != null) {
            observeTop250(top250Repository)
        } else {
            repository.observeFilteredMovies(normalizedType, filterValue)
                .map { results ->
                    FilterResultUiState(
                        filterType = normalizedType,
                        filterValue = filterValue,
                        title = filterTitle(normalizedType, filterValue),
                        movies = results
                    )
                }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            // 榜单页的空态是「还没算完」而不是「没有影片」：首帧不能闪一下空态文案。
            if (normalizedType == TOP250_FILTER) {
                FilterResultUiState(emptyText = "正在匹配榜单与已入库影片…")
            } else {
                FilterResultUiState()
            }
        )

    /**
     * 榜单页只列已入库影片：先取本地影片全量，再按归一化番号查榜单名次，
     * 没上榜单的（mapNotNull 掉）不显示——这与 5.0.5 的榜单页语义一致。
     *
     * 依赖两个上游：影片库变动（扫描/删除）与榜单缓存变动（status.revision）。
     * 后者是仓储唯一的失效信号，刷新完榜单后本页会自动重排。
     */
    private fun observeTop250(repository: Top250RankingRepository): Flow<FilterResultUiState> =
        combine(
            this.repository.observeMovies(),
            repository.status.map { it.revision }.distinctUntilChanged()
        ) { movies, _ -> movies to repository.rankingsForList(rankingYear) }
            .map { (movies, ranks) ->
                val ranked = if (ranks.isEmpty()) {
                    emptyList()
                } else {
                    movies
                        .mapNotNull { movie ->
                            val number = movie.top250Number() ?: return@mapNotNull null
                            val ranking = ranks[Top250RankingRepository.normalizeNumber(number)]
                                ?: return@mapNotNull null
                            movie to ranking
                        }
                        .sortedWith(
                            compareBy({ it.second }, { it.first.sortTitle.ifBlank { it.first.title } })
                        )
                        .map { it.first }
                }
                FilterResultUiState(
                    filterType = normalizedType,
                    filterValue = filterValue,
                    title = top250Title(rankingYear),
                    movies = ranked,
                    emptyText = when {
                        ranks.isEmpty() -> "榜单尚未缓存，请到设置页获取榜单"
                        else -> "这个榜单暂无已入库影片"
                    }
                )
            }

    companion object {
        const val TOP250_FILTER = "top250"

        fun factory(
            filterType: String,
            filterValue: String,
            repository: MovieRepository,
            top250Repository: Top250RankingRepository? = null
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    FilterResultViewModel(filterType, filterValue, repository, top250Repository) as T
            }
    }
}

data class FilterResultUiState(
    val filterType: String = "",
    val filterValue: String = "",
    val title: String = "",
    val movies: List<MovieEntity> = emptyList(),
    /** 空态文案；为空时页面回落成通用的「没有找到相关影片」。 */
    val emptyText: String = ""
)

/** 卡片与详情页取号顺序保持一致：文件名优先，回退标题。 */
private fun MovieEntity.top250Number(): String? =
    extractMovieNumberInfo(videoName)?.number ?: extractMovieNumberInfo(title)?.number

private fun top250Title(year: Int?): String =
    if (year == null) "总榜 TOP250" else "$year 年度 TOP250"

private fun filterTitle(type: String, value: String): String = when (type) {
    "actor" -> "\u6F14\u5458\uFF1A$value"
    "tag" -> "\u6807\u7B7E\uFF1A$value"
    "genre" -> "\u7C7B\u578B\uFF1A$value"
    "year" -> "\u5E74\u4EFD\uFF1A$value"
    "studio" -> "工作室：$value"
    "collection" -> "合集：$value"
    else -> value
}

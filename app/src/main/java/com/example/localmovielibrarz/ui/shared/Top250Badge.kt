package com.example.localmovielibrarz.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.localmovielibrarz.LocalMovieLibraryApp
import com.example.localmovielibrarz.data.local.MovieEntity
import com.example.localmovielibrarz.data.repository.Top250CardRank
import com.example.localmovielibrarz.data.repository.Top250Rank
import com.example.localmovielibrarz.util.extractMovieNumberInfo

/**
 * 榜单标识配色与图标（移植自 5.0.5 的 `object Top250BadgeStyle`）。
 *
 * 六色两图标由 5.0.5 字节码直接读出，未按本工程主题重算：
 * 总榜是「金」、年度是「蓝灰」—— 这是两者唯一的视觉区分信号。
 * 换成主题语义色会把这个信号抹掉（一张 8sp 的小角标上，tertiary/secondary 几乎无法分辨）。
 */
object Top250BadgeStyle {
    /** 总榜底色（暖金）。 */
    private val GoldBackground = Color(0xFFFFCF56)

    /** 总榜文字色（深金棕）。 */
    private val GoldForeground = Color(0xFF4A3100)

    /** 总榜名次章底色（深琥珀）。 */
    private val GoldRankBackground = Color(0xFF59410B)

    /** 年度榜底色（浅蓝灰）。 */
    private val YearBackground = Color(0xFFACC9D8)

    /** 年度榜文字色（深蓝）。 */
    private val YearForeground = Color(0xFF16394B)

    /** 年度榜名次章底色（深蓝灰）。 */
    private val YearRankBackground = Color(0xFF29485A)

    fun background(year: Int?): Color = if (year == null) GoldBackground else YearBackground

    fun foreground(year: Int?): Color = if (year == null) GoldForeground else YearForeground

    fun rankBackground(year: Int?): Color = if (year == null) GoldRankBackground else YearRankBackground

    fun icon(year: Int?): ImageVector =
        if (year == null) Icons.Rounded.EmojiEvents else Icons.Rounded.WorkspacePremium
}

/**
 * 卡片角标的呈现参数。
 *
 * [cacheRevision] 来自 `Top250CacheStatus.revision`，是唯一的全局失效信号：
 * 任一份榜单落盘 revision 就 +1，卡片角标的 `produceState` 把它当 key 重查，
 * 因此设置页刷新后不需要重启 App 就能看到新名次。
 *
 * 只保留金标开关：本工程（4.0.0 谱系）的卡片本来就有自己的收藏心与配色，
 * 5.0.5 的 `card_favorite_heart_enabled` / `detail_favorite_color` 是给
 * 它自己那套心形实现用的，照搬会与本工程已有的心形重复渲染。
 */
data class MovieCardBadgePresentation(
    val showTopGoldBadge: Boolean,
    val cacheRevision: Int
)

/** 卡片角标上下文默认值：不显示金标、缓存版本 0。 */
val LocalMovieCardBadgePresentation = staticCompositionLocalOf {
    MovieCardBadgePresentation(showTopGoldBadge = false, cacheRevision = 0)
}

/**
 * 影片卡片上的 TOP 金标（右下角 pill）。
 *
 * 调用处必须已经在 `Box` 内 —— 它靠 `align` 定位，不参与父布局测量。
 *
 * 位置选右下而不是左上：本工程的卡片右上被收藏心（18dp）与批选圈（24dp）占用、
 * 左下被刮削状态文字占用，右下是唯一长期空着的角。
 */
@Composable
fun BoxScope.Top250ArtworkBadge(movie: MovieEntity) {
    val presentation = LocalMovieCardBadgePresentation.current
    if (!presentation.showTopGoldBadge) return

    // 番号优先取 videoName，取不到才回退 title —— 与 5.0.5 一致：
    // videoName 是文件名，带番号的概率远高于刮削后的中文标题。
    val number = extractMovieNumberInfo(movie.videoName)?.number
        ?: extractMovieNumberInfo(movie.title)?.number
    val context = LocalContext.current
    val repository = (context.applicationContext as? LocalMovieLibraryApp)
        ?.container
        ?.top250RankingRepository

    val cardRank by produceState<Top250CardRank?>(
        initialValue = null,
        movie.id,
        movie.videoName,
        movie.title,
        presentation.showTopGoldBadge,
        presentation.cacheRevision
    ) {
        value = null
        val repo = repository ?: return@produceState
        val key = number ?: return@produceState
        value = repo.cardRankFor(key)
    }

    val rank = cardRank ?: return
    Row(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Top250BadgeStyle.background(rank.year))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            imageVector = Top250BadgeStyle.icon(rank.year),
            contentDescription = null,
            tint = Top250BadgeStyle.foreground(rank.year),
            modifier = Modifier.size(9.dp)
        )
        Text(
            text = cardRankText(rank),
            color = Top250BadgeStyle.foreground(rank.year),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** 卡片角标文案：总榜与年度句式不同，年度那句的「年度」前有一个空格。 */
private fun cardRankText(rank: Top250CardRank): String =
    if (rank.year == null) {
        "总榜 TOP250 第${rank.ranking}名"
    } else {
        "${rank.year} 年度 TOP250 第${rank.ranking}名"
    }

/**
 * 详情页徽章行（移植自 5.0.5 的 `Top250RankBadges`）。
 *
 * 一枚 = 名次章 `No.N`（深色底白字）+ 图标 + 榜单名。总榜在前，其后 2026 降序到 2008，
 * 最多 20 枚，因此用 [FlowRow] 自动换行而不是横向滚动 —— 详情页整体是纵向滚动的，
 * 内嵌横向滚动区会让手势方向变得难以预测。
 *
 * 空列表整块不渲染（没有「暂无排名」占位）：绝大多数影片不在榜上，
 * 给它们显示一行「暂无」纯属噪音。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Top250RankBadges(
    ranks: List<Top250Rank>,
    onTop250Click: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (ranks.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ranks.forEach { rank ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Top250BadgeStyle.background(rank.year))
                    .clickable { onTop250Click(rank.year) }
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "No.${rank.ranking}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Top250BadgeStyle.rankBackground(rank.year))
                        .padding(9.dp)
                )
                Icon(
                    imageVector = Top250BadgeStyle.icon(rank.year),
                    contentDescription = null,
                    tint = Top250BadgeStyle.foreground(rank.year),
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = rank.label,
                    color = Top250BadgeStyle.foreground(rank.year),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
    }
}

/**
 * 在组合树根部装配卡片角标上下文。
 *
 * 只在这里订阅一次设置与缓存版本：若改成每张卡各自订阅，
 * 首页 90 张卡就是 90 份订阅 + 90 次全量重组，滚动会明显掉帧。
 *
 * 这里**不触发网络请求**，只把磁盘缓存的状态版本下发给卡片；
 * 刷新时机在设置页（榜单一天一更，冷启动联网不划算）。
 */
@Composable
fun Top250BadgeProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as? LocalMovieLibraryApp)?.container
    if (container == null) {
        content()
        return
    }
    val settingsRepository = container.settingsRepository
    val status by container.top250RankingRepository.status.collectAsState()
    val enabled by settingsRepository.observeCardTopGoldBadgeEnabled()
        .collectAsState(initial = settingsRepository.isCardTopGoldBadgeEnabled())
    CompositionLocalProvider(
        LocalMovieCardBadgePresentation provides MovieCardBadgePresentation(
            showTopGoldBadge = enabled,
            cacheRevision = status.revision
        ),
        content = content
    )
}

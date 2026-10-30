package com.example.localmovielibrarz.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.localmovielibrarz.LocalMovieLibraryApp
import com.example.localmovielibrarz.data.local.MovieEntity
import com.example.localmovielibrarz.data.repository.Top250BadgeSize
import com.example.localmovielibrarz.data.repository.Top250Rank
import com.example.localmovielibrarz.data.repository.Top250State
import com.example.localmovielibrarz.ui.AppRadius

/**
 * 榜单标识（JavDB TOP 榜）。
 *
 * 视觉口径与 4.0.0 一致：颜色只取主题语义色（`tertiary` 在本工程里就是暖金色，
 * 正好对应「金榜」含义），圆角走 [AppRadius]，字号由调用方给 TextUnit，
 * 不引入新的硬编码色值 —— 4.0.0 的设计 token 层就是为了收敛这类散装数值。
 *
 * 三种尺寸对应 5.0.5 的 BADGE_ICON_LARGE / SMALL / NONE：
 * - [Top250Badge] 用于影片卡片左上角（小、单行、只显示名次）
 * - [Top250RankBadges] 用于详情页（大、可显示多条）
 * - 关闭开关时调用方直接不组合，等于 NONE
 */
@Composable
fun Top250Badge(
    rank: Int,
    modifier: Modifier = Modifier,
    prefix: String = "TOP",
    shape: CornerBasedShape = AppRadius.XS,
    fontSize: TextUnit = 10.sp,
    horizontalPadding: Int = 5,
    verticalPadding: Int = 2
) {
    if (rank <= 0) return
    Row(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.tertiary)
            .padding(horizontal = horizontalPadding.dp, vertical = verticalPadding.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = if (prefix.isBlank()) "$rank" else "$prefix $rank",
            color = MaterialTheme.colorScheme.onTertiary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            maxLines = 1
        )
    }
}

/**
 * 详情页角标：显示该片在榜单上的名次（可能同时命中多个榜）。
 *
 * 与卡片角标共用同一份 [Top250Rank]，避免两处各算一遍名次。
 * 按名次升序取前 [maxVisible] 条，超出部分不显示，避免详情页标题行被撑开。
 */
@Composable
fun Top250RankBadges(
    ranks: List<Top250Rank>,
    modifier: Modifier = Modifier,
    maxVisible: Int = 2
) {
    if (ranks.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ranks.sortedBy { it.rank }.take(maxVisible).forEach { rank ->
            Top250Badge(
                rank = rank.rank,
                prefix = "TOP",
                fontSize = 12.sp,
                horizontalPadding = 7,
                verticalPadding = 3
            )
        }
    }
}

/** 单条命中的详情页角标（影片只会命中一条榜单记录时用这个重载）。 */
@Composable
fun Top250RankBadge(rank: Top250Rank?, modifier: Modifier = Modifier) {
    if (rank == null) return
    Top250Badge(
        rank = rank.rank,
        prefix = "TOP",
        modifier = modifier,
        fontSize = 12.sp,
        horizontalPadding = 7,
        verticalPadding = 3
    )
}

/**
 * 海报左上角的榜单角标叠层（[BoxScope] 扩展，调用处必须已经在 Box 内）。
 *
 * 首页网格、横向轮播、搜索结果、收藏、筛选结果都各自有一块海报区域，
 * 但角标的口径必须完全一致（位置、留白、字号、开关），因此把「读开关 + 查名次 +
 * 定位到 TopStart」这三步收敛成一个叠层组件，各页面只叠一层，不再各写一遍。
 * 关闭开关时直接不组合，等价 5.0.5 的 NONE 档。
 */
@Composable
fun BoxScope.Top250ArtworkBadge(movie: MovieEntity) {
    val size = LocalTop250BadgeSize.current
    if (!size.isVisible) return
    val rank = LocalTop250RankOf.current(movie) ?: return
    // 三档只差字号与内边距：Large 用于宽松网格（卡片更宽），Small 用于密集场景。
    val large = size == Top250BadgeSize.Large
    Top250Badge(
        rank = rank.rank,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(if (large) 7.dp else 5.dp),
        fontSize = if (large) 12.sp else 9.sp,
        horizontalPadding = if (large) 7 else 4,
        verticalPadding = if (large) 3 else 1
    )
}

/**
 * 卡片角标的取值上下文。
 *
 * 为什么用 CompositionLocal 而不是把名次一路传参：
 * 影片卡片出现的位置有首页网格、横向轮播、搜索结果、筛选项结果等多处，
 * 每处都改签名会把「榜单」这一个横切能力扩散成十几处改动；而它本身
 * 只影响卡片左上角的一个小角标，属于典型的局部环境信息。
 *
 * 尺寸只有 [LocalTop250BadgeSize] 一个真值源：早前先有的「开关」布尔
 * 现在退化成 `size.isVisible`，不再单独下发，避免两个 CompositionLocal
 * 需要同步（同步漏一处就会出现「设置关了但某页还画」）。
 */

/** 角标尺寸；[Top250BadgeSize.None] 表示所有位置都不显示。 */
val LocalTop250BadgeSize = staticCompositionLocalOf { Top250BadgeSize.Small }

/** 影片 → 榜单名次；未加载或未命中都返回 null。 */
val LocalTop250RankOf = staticCompositionLocalOf<(MovieEntity) -> Top250Rank?> { { null } }

/**
 * 在组合树根部装配榜单角标上下文。
 *
 * 只在这里订阅一次仓储状态与设置开关：如果改成每张卡片各自订阅，
 * 首页 90 张卡就是 90 份订阅 + 90 次全量重组，滚动会明显掉帧。
 *
 * 榜单数据本身不在这里触发网络请求，只从磁盘缓存加载；刷新时机留给设置页，
 * 因为排行榜一天更新一次，冷启动联网并不划算。
 */
@Composable
fun Top250BadgeProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as? LocalMovieLibraryApp)?.container
    if (container == null) {
        content()
        return
    }
    val repository = container.top250RankingRepository
    val settingsRepository = container.settingsRepository
    val badgeSize by settingsRepository.observeCardTopGoldBadgeSize()
        .collectAsState(initial = settingsRepository.cardTopGoldBadgeSize())
    val badgeState by repository.state.collectAsState()
    LaunchedEffect(repository) {
        if (repository.state.value is Top250State.Idle) repository.loadFromCache()
    }
    // 缓存没命中时 rankOf 恒为 null，卡片自然不画角标，不需要额外的分支。
    val ready = badgeState is Top250State.Ready
    val rankOf: (MovieEntity) -> Top250Rank? = if (ready) {
        { movie -> repository.rankOf(movie) }
    } else {
        { _ -> null }
    }
    CompositionLocalProvider(
        LocalTop250BadgeSize provides badgeSize,
        LocalTop250RankOf provides rankOf,
        content = content
    )
}


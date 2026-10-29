package com.example.localmovielibrarz.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import com.example.localmovielibrarz.data.local.MovieEntity

/**
 * 海报/横图解码长边上界。
 *
 * 此前四处调用点各写一死 `if (Thumb) 960 else 720`，卡片实际只有 112–190dp 宽，
 * 在 3x 密度下也只需要约 570px，原值过采样约 1.7 倍（低密度机型更严重）。
 * 统一收敛到本处：横图 640（16:9，宽 190dp @3x 需 570px）、海报 480（2:3，高 190dp*1.5 @3x 需 855px
 * 但按长边采样，480 已足够，细节由磁盘缓存兜底）。
 */
private const val ARTWORK_DECODE_SIZE_THUMB = 640
private const val ARTWORK_DECODE_SIZE_POSTER = 480

@Composable
fun MovieArtwork(
    movie: MovieEntity,
    preferThumb: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxDecodeSize: Int = if (preferThumb) ARTWORK_DECODE_SIZE_THUMB else ARTWORK_DECODE_SIZE_POSTER
) {
    val imageUri = movie.selectArtworkUri(preferThumb)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        UriImage(
            uri = imageUri,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
            maxDecodeSize = maxDecodeSize,
            cacheKey = movie.artworkCacheRevision()
        )
        if (imageUri == null) {
            MovieArtworkPlaceholder(title = movie.title)
        }
    }
}

fun MovieEntity.selectArtworkUri(preferThumb: Boolean): String? =
    if (preferThumb) {
        thumbUri ?: fanartUri ?: posterUri
    } else {
        posterUri ?: thumbUri ?: fanartUri
    }

fun MovieEntity.artworkCacheRevision(): String =
    listOf(
        id.toString(),
        posterUri.orEmpty(),
        fanartUri.orEmpty(),
        thumbUri.orEmpty()
    ).joinToString("|")

@Composable
fun MovieArtworkPlaceholder(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title.take(2).uppercase(),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

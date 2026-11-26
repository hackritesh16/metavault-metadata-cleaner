package com.metavault.cleaner

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

private data class GlowPair(val upper: Color, val lower: Color)

@Composable
internal fun AuroraBackground(theme: String, scrollOffset: () -> Float, photo: Bitmap?, showTheme: Boolean) {
    Box(Modifier.fillMaxSize().background(paletteFor(theme).bg)) {
        if (photo != null) {
            Image(remember(photo) { photo.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // Neutral scrim (the pre-Aurora 0.48 look): wallpaper stays clearly visible, glass text stays readable.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f)))
        } else if (showTheme) {
            val palette = paletteFor(theme)
            val colors = GlowPair(palette.glowUpper, palette.glowLower)
            val motion = LocalMotionEnabled.current
            if (motion) {
                var drift by remember { mutableFloatStateOf(0f) }
                var direction by remember { mutableFloatStateOf(1f) }
                LaunchedEffect(colors) {
                    val start = drift
                    val target = direction
                    direction = -direction
                    // At this amplitude each step is under 1 dp. Don't schedule 720 frames for 9 dp of travel.
                    for (step in 1..24) {
                        delay(500)
                        withFrameMillis { drift = start + (target - start) * step / 24f }
                    }
                }
                Crossfade(colors, animationSpec = tween(320), label = "aurora palette") { palette ->
                    Glows(palette, scrollOffset) { drift }
                }
            } else {
                Glows(colors, scrollOffset = { 0f }, drift = { 0f })
            }
        }
    }
}

@Composable
private fun Glows(colors: GlowPair, scrollOffset: () -> Float, drift: () -> Float) {
    Glow(colors.upper.copy(alpha = 0.28f), 0.86f, 0.12f, -1f, scrollOffset, drift)
    Glow(colors.lower.copy(alpha = 0.15f), 0.08f, 0.76f, 0.72f, scrollOffset, drift)
}

@Composable
private fun Glow(color: Color, x: Float, y: Float, direction: Float, scrollOffset: () -> Float, drift: () -> Float) {
    Box(Modifier.fillMaxSize().graphicsLayer {
        translationY = (scrollOffset() * 0.16f).coerceIn(0f, size.height * 0.55f) * direction
        translationX = drift() * 9.dp.toPx() * direction
    }.drawWithCache {
        val radius = size.minDimension.coerceAtLeast(1f) * 0.90f
        val center = Offset(size.width * x, size.height * y)
        val brush = Brush.radialGradient(listOf(color, Color.Transparent), center, radius)
        onDrawBehind { drawCircle(brush, radius, center) }
    })
}

package com.metavault.cleaner

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale

private data class GlowPair(val upper: Color, val lower: Color)
private val palettes = linkedMapOf(
    "Aurora" to GlowPair(Accent, AccentSoft),
    "Ocean" to GlowPair(Color(0xFF087EAA), Color(0xFF16C5C0)),
    "Violet" to GlowPair(Color(0xFF7042C8), Color(0xFFB45BDB)),
    "Emerald" to GlowPair(Color(0xFF087B63), Color(0xFF52C878)),
    "Sunset" to GlowPair(Color(0xFFE06A42), Color(0xFFE5A044)),
    "Rose" to GlowPair(Color(0xFFC34F83), Color(0xFFEA8DAD)),
    "Midnight" to GlowPair(Color(0xFF34518D), Color(0xFF526A9D)),
    "Monochrome" to GlowPair(Color(0xFF686868), Color(0xFFA0A0A0))
)
internal val AuroraThemeNames = palettes.keys.toList()

@Composable
internal fun AuroraBackground(theme: String, scrollOffset: () -> Float, photo: Bitmap?, showTheme: Boolean) {
    Box(Modifier.fillMaxSize().background(Bg)) {
        if (photo != null) {
            Image(photo.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // Even a white photo must leave enough contrast behind secondary labels on glass.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)))
        } else if (showTheme) {
            val colors = palettes[theme] ?: palettes.getValue("Aurora")
            Box(Modifier.fillMaxSize().drawWithCache {
                val radius = size.minDimension.coerceAtLeast(1f) * 0.90f
                val upper = Brush.radialGradient(listOf(colors.upper.copy(alpha = 0.28f), Color.Transparent),
                    center = Offset.Zero, radius = radius)
                val lower = Brush.radialGradient(listOf(colors.lower.copy(alpha = 0.15f), Color.Transparent),
                    center = Offset.Zero, radius = radius)
                onDrawBehind {
                    val shift = (scrollOffset() * 0.16f).coerceIn(0f, size.height * 0.55f)
                    translate(size.width * 0.86f, size.height * 0.12f - shift) {
                        drawCircle(upper, radius, Offset.Zero)
                    }
                    translate(size.width * 0.08f, size.height * 0.76f + shift * 0.72f) {
                        drawCircle(lower, radius, Offset.Zero)
                    }
                }
            })
        }
    }
}

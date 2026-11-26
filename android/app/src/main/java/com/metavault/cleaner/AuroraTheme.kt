package com.metavault.cleaner

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Bg = Color(0xFF080D1B)
internal val Surface1 = Color(0xFF111B2E)
internal val Surface2 = Color(0xFF18243A)
internal val Accent = Color(0xFF8868FF)
internal val AccentSoft = Color(0xFF55D6FF)
internal val Clean = Color(0xFF43D9B0)
internal val TextMain = Color(0xFFF4F7FF)
internal val Muted = Color(0xFFA7B4CC)
internal val Warn = Color(0xFFFFC379)
internal val Line = Color(0xFF39455C)
internal val LineStrong = Color(0xFF738198)
internal val AccentTint = Color(0xFF332959)

internal object AuroraLayout {
    val small = 4.dp
    val compact = 8.dp
    val gap = 12.dp
    val padding = 16.dp
    val widePadding = 24.dp
    val touchTarget = 48.dp
    val actionHeight = 52.dp
    val contentMaxWidth = 840.dp
    val barMaxWidth = 600.dp

    fun gutter(available: Dp) = if (available >= 600.dp) widePadding else padding

    val cardShape = RoundedCornerShape(20.dp)
    val buttonShape = RoundedCornerShape(16.dp)
    val pill = RoundedCornerShape(50)
}

// Build #116 typography: Bricolage Grotesque headings, Instrument Sans body, JetBrains Mono technical text.
// Static font files are fetched at build time (see prepareAuroraFonts in build.gradle.kts); no text shadows.
internal val DisplayFont = FontFamily(
    Font(R.font.bricolage_grotesque_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_grotesque_bold, FontWeight.Bold)
)
private val BodyFont = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.Normal),
    Font(R.font.instrument_sans_medium, FontWeight.Medium),
    Font(R.font.instrument_sans_semibold, FontWeight.SemiBold)
)
internal val MonoFont = FontFamily(Font(R.font.jetbrains_mono_medium, FontWeight.Medium))

private fun heading(size: Int, line: Int, weight: FontWeight = FontWeight.Bold, tracking: Float = 0f) = TextStyle(
    fontFamily = DisplayFont, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp
)

private fun body(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = BodyFont, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp
)

internal val TechnicalLabel = TextStyle(fontFamily = MonoFont, fontWeight = FontWeight.Medium,
    fontSize = 12.sp, lineHeight = 18.sp)

private val AuroraTypography = Typography(
    displayLarge = heading(57, 64), displayMedium = heading(45, 52), displaySmall = heading(36, 44),
    headlineLarge = heading(36, 39, tracking = -0.9f), headlineMedium = heading(28, 32, tracking = -0.6f), headlineSmall = heading(24, 30),
    titleLarge = heading(20, 26, FontWeight.SemiBold, -0.3f), titleMedium = heading(17, 22, FontWeight.SemiBold, -0.2f), titleSmall = heading(15, 21, FontWeight.SemiBold),
    bodyLarge = body(16, 24), bodyMedium = body(14, 21), bodySmall = body(13, 18),
    labelLarge = body(14, 20, FontWeight.Medium), labelMedium = body(12, 16, FontWeight.Medium),
    labelSmall = body(12, 16, FontWeight.Medium)
)

private val AuroraScheme = darkColorScheme(
    primary = Accent, onPrimary = Bg, primaryContainer = AccentTint, onPrimaryContainer = TextMain,
    secondary = AccentSoft, onSecondary = Bg, secondaryContainer = Color(0xFF183448), onSecondaryContainer = TextMain,
    tertiary = Clean, onTertiary = Bg, tertiaryContainer = Color(0xFF10392F), onTertiaryContainer = TextMain,
    error = Color(0xFFFFB4AE), onError = Bg, errorContainer = Color(0xFF552526), onErrorContainer = TextMain,
    background = Bg, onBackground = TextMain, surface = Surface1, onSurface = TextMain,
    surfaceVariant = Surface2, onSurfaceVariant = Muted, outline = LineStrong, outlineVariant = Line,
    surfaceDim = Bg, surfaceBright = Color(0xFF29354B), surfaceContainerLowest = Bg,
    surfaceContainerLow = Surface1, surfaceContainer = Surface2, surfaceContainerHigh = Color(0xFF1D293E),
    surfaceContainerHighest = Color(0xFF243149), surfaceTint = Color.Transparent,
    inverseSurface = TextMain, inverseOnSurface = Bg, inversePrimary = Color(0xFF5139A7)
)

// photoBackground is kept so existing call sites do not change; text no longer needs a shadow over photos.
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun AuroraTheme(photoBackground: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMotionEnabled provides systemMotionEnabled()) {
        MaterialTheme(colorScheme = AuroraScheme, typography = AuroraTypography, content = content)
    }
}

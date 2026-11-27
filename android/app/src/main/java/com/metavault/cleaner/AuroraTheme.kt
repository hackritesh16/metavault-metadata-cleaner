package com.metavault.cleaner

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One complete colour set. Every theme option is one of these, so picking a theme recolours the whole app. */
internal class ThemePalette(
    val bg: Color, val surface1: Color, val surface2: Color, val surface3: Color, val surface4: Color, val bright: Color,
    val accent: Color, val accentSoft: Color, val accentTint: Color, val line: Color, val lineStrong: Color,
    val glowUpper: Color, val glowLower: Color
)

private fun derived(bg: Long, accent: Long, soft: Long): ThemePalette {
    val b = Color(bg); val a = Color(accent); val s = Color(soft)
    fun tone(t: Float) = lerp(b, a, t)
    return ThemePalette(
        bg = b, surface1 = tone(0.09f), surface2 = tone(0.14f), surface3 = tone(0.19f), surface4 = tone(0.25f), bright = tone(0.31f),
        accent = a, accentSoft = s, accentTint = tone(0.38f),
        line = lerp(tone(0.2f), Color(0xFFA7B4CC), 0.22f), lineStrong = lerp(tone(0.3f), Color(0xFFA7B4CC), 0.5f),
        glowUpper = a, glowLower = s
    )
}

private val AuroraPalette0 = ThemePalette(
    bg = Color(0xFF080D1B), surface1 = Color(0xFF111B2E), surface2 = Color(0xFF18243A), surface3 = Color(0xFF1D293E),
    surface4 = Color(0xFF243149), bright = Color(0xFF29354B), accent = Color(0xFF8868FF), accentSoft = Color(0xFF55D6FF),
    accentTint = Color(0xFF332959), line = Color(0xFF39455C), lineStrong = Color(0xFF738198),
    glowUpper = Color(0xFF8868FF), glowLower = Color(0xFF55D6FF)
)

private val themePalettes = linkedMapOf(
    "Aurora" to AuroraPalette0,
    "Ocean" to derived(0xFF06141C, 0xFF1EA7DA, 0xFF2EE0D0),
    "Violet" to derived(0xFF110A1E, 0xFF9B6BFF, 0xFFD17BF0),
    "Emerald" to derived(0xFF07150F, 0xFF2FBF8F, 0xFF7EE08F),
    "Sunset" to derived(0xFF1B0F0A, 0xFFF07A4A, 0xFFF5B35A),
    "Rose" to derived(0xFF1B0B14, 0xFFE0689B, 0xFFF5A3C0),
    "Midnight" to derived(0xFF070A14, 0xFF5B7FE0, 0xFF8DA6E8),
    "Monochrome" to derived(0xFF0C0C0C, 0xFFB8B8B8, 0xFFE2E2E2)
)

internal val AuroraThemeNames: List<String> = themePalettes.keys.toList()

internal fun paletteFor(name: String): ThemePalette = themePalettes[name] ?: AuroraPalette0

/** The theme in use. It is Compose state, so everything that reads a colour below redraws when it changes. */
internal object ActiveTheme {
    var palette by mutableStateOf(AuroraPalette0)
        private set
    var name by mutableStateOf("Aurora")
        private set

    fun select(theme: String) {
        val resolved = if (theme in themePalettes) theme else "Aurora"
        name = resolved
        palette = paletteFor(resolved)
    }
}

internal val Bg: Color get() = ActiveTheme.palette.bg
internal val Surface1: Color get() = ActiveTheme.palette.surface1
internal val Surface2: Color get() = ActiveTheme.palette.surface2
internal val Accent: Color get() = ActiveTheme.palette.accent
internal val AccentSoft: Color get() = ActiveTheme.palette.accentSoft
internal val AccentTint: Color get() = ActiveTheme.palette.accentTint
internal val Line: Color get() = ActiveTheme.palette.line
internal val LineStrong: Color get() = ActiveTheme.palette.lineStrong
internal val Clean = Color(0xFF43D9B0)
internal val TextMain = Color(0xFFF4F7FF)
internal val Muted = Color(0xFFA7B4CC)
internal val Warn = Color(0xFFFFC379)

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

private fun schemeFor(p: ThemePalette) = darkColorScheme(
    primary = p.accent, onPrimary = p.bg, primaryContainer = p.accentTint, onPrimaryContainer = TextMain,
    secondary = p.accentSoft, onSecondary = p.bg, secondaryContainer = lerp(p.bg, p.accentSoft, 0.22f), onSecondaryContainer = TextMain,
    tertiary = Clean, onTertiary = p.bg, tertiaryContainer = Color(0xFF10392F), onTertiaryContainer = TextMain,
    error = Color(0xFFFFB4AE), onError = p.bg, errorContainer = Color(0xFF552526), onErrorContainer = TextMain,
    background = p.bg, onBackground = TextMain, surface = p.surface1, onSurface = TextMain,
    surfaceVariant = p.surface2, onSurfaceVariant = Muted, outline = p.lineStrong, outlineVariant = p.line,
    surfaceDim = p.bg, surfaceBright = p.bright, surfaceContainerLowest = p.bg,
    surfaceContainerLow = p.surface1, surfaceContainer = p.surface2, surfaceContainerHigh = p.surface3,
    surfaceContainerHighest = p.surface4, surfaceTint = Color.Transparent,
    inverseSurface = TextMain, inverseOnSurface = p.bg, inversePrimary = lerp(p.bg, p.accent, 0.6f)
)

@Composable
internal fun AuroraTheme(photoBackground: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMotionEnabled provides systemMotionEnabled(), LocalPhotoBackdrop provides photoBackground) {
        MaterialTheme(colorScheme = schemeFor(ActiveTheme.palette), typography = AuroraTypography, content = content)
    }
}

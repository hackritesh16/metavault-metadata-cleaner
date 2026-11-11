package com.metavault.cleaner

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// ---------- Palette: deep navy base, mint and electric-blue accents ----------
internal val Bg = Color(0xFF05080F)
internal val BgDeep = Color(0xFF0B1224)
internal val TextMain = Color(0xFFF2F5FB)
internal val Muted = Color(0xFFA6B0C6)
internal val Accent = Color(0xFF3A63E8)
internal val AccentSoft = Color(0xFFA9C0FF)
internal val AccentTint = Color(0x333A63E8)
internal val Clean = Color(0xFF3DDC97)
internal val CleanInk = Color(0xFF04210F)
internal val Paper = Color(0xFFF3F5F8)
internal val PaperInk = Color(0xFF0B1018)
internal val PaperMuted = Color(0xFF4B586D)
internal val Line = Color(0x26FFFFFF)

// Glass material: translucent white fill, bright rim on top edge, soft sheen.
internal val GlassTop = Color(0x1EFFFFFF)
internal val GlassBottom = Color(0x0CFFFFFF)
internal val GlassRim = Color(0x73FFFFFF)

// Fonts are bundled in res/font so the app never downloads them.
internal val DisplayFont = FontFamily(
    Font(R.font.bricolage_grotesque_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_grotesque_bold, FontWeight.Bold)
)
internal val BodyFont = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.Normal),
    Font(R.font.instrument_sans_medium, FontWeight.Medium),
    Font(R.font.instrument_sans_semibold, FontWeight.SemiBold)
)
internal val MonoFont = FontFamily(Font(R.font.jetbrains_mono_medium, FontWeight.Medium))

internal val VaultTypography = Typography(
    headlineLarge = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 39.sp, letterSpacing = (-0.9).sp),
    headlineMedium = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(fontFamily = BodyFont, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = BodyFont, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = BodyFont, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
)

@Composable
internal fun VaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg, surface = BgDeep, surfaceVariant = BgDeep,
            primary = Accent, onPrimary = Color.White,
            secondary = AccentSoft, onSecondary = PaperInk,
            secondaryContainer = AccentTint, onSecondaryContainer = TextMain,
            tertiary = Clean, onTertiary = CleanInk,
            onBackground = TextMain, onSurface = TextMain, onSurfaceVariant = Muted,
            outline = GlassRim, outlineVariant = Line
        ),
        typography = VaultTypography,
        content = content
    )
}

/** True when the user has turned off animations in system settings. */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Slowly drifting colour orbs behind everything. The blur gives the frosted look that glass refracts. */
@Composable
internal fun AmbientBackground(modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val transition = rememberInfiniteTransition(label = "ambient")
    val animated by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animationSpec = tween(durationMillis = 24_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ambient-phase"
    )
    val phase = if (reduceMotion) 0.25f else animated
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgDeep, Bg)))) {
        Canvas(Modifier.fillMaxSize().blur(64.dp)) {
            val a = phase * 6.2831855f
            val w = size.width
            val h = size.height
            drawOrb(Accent.copy(alpha = 0.6f), Offset(w * (0.22f + 0.10f * cos(a)), h * (0.16f + 0.05f * sin(a))), w * 0.62f)
            drawOrb(Clean.copy(alpha = 0.30f), Offset(w * (0.86f + 0.07f * sin(a)), h * (0.46f + 0.06f * cos(a))), w * 0.50f)
            drawOrb(Color(0xFF7C5CFF).copy(alpha = 0.36f), Offset(w * (0.42f + 0.12f * sin(a * 0.7f)), h * (0.92f + 0.04f * cos(a))), w * 0.70f)
        }
    }
}

private fun DrawScope.drawOrb(color: Color, center: Offset, radius: Float) {
    drawCircle(
        brush = Brush.radialGradient(colors = listOf(color, Color.Transparent), center = center, radius = radius),
        radius = radius,
        center = center
    )
}

/**
 * The glass surface. Translucent fill, a soft sheen across the top half and a bright
 * rim along the top edge make it read as curved, lit glass over the backdrop.
 */
@Composable
internal fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    elevation: Dp = 16.dp,
    tint: Color? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val fill = if (tint != null) {
        Brush.verticalGradient(listOf(tint.copy(alpha = 0.30f), tint.copy(alpha = 0.10f)))
    } else {
        Brush.verticalGradient(listOf(GlassTop, GlassBottom))
    }
    Box(
        modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.5f),
                spotColor = Color.Black.copy(alpha = 0.6f)
            )
            .clip(shape)
            .background(fill)
            .drawBehind {
                val radius = CornerRadius(cornerRadius.toPx())
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), endY = size.height * 0.5f),
                    cornerRadius = radius
                )
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(GlassRim, Color.Transparent), endY = size.height * 0.6f),
                    cornerRadius = radius,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
    ) {
        content()
    }
}

/** A glass card with standard padding and vertical rhythm. */
@Composable
internal fun GlassCard(
    modifier: Modifier = Modifier,
    tint: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassSurface(modifier = modifier.fillMaxWidth(), tint = tint) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

/** Small glass disc holding an icon. */
@Composable
internal fun IconBadge(
    icon: ImageVector,
    description: String? = null,
    tint: Color = Accent,
    iconColor: Color = AccentSoft,
    size: Dp = 44.dp
) {
    GlassSurface(modifier = Modifier.size(size), cornerRadius = size / 2, elevation = 4.dp, tint = tint) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = iconColor, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/** Filled gradient action with a coloured glow and a press-squish. */
@Composable
internal fun PrimaryButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    gradient: List<Color> = listOf(Accent, Color(0xFF6E8DFF)),
    contentColor: Color = Color.White,
    glow: Color = Accent
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 520f),
        label = "press-primary"
    )
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(elevation = 14.dp, shape = shape, ambientColor = glow, spotColor = glow)
            .clip(shape)
            .background(Brush.horizontalGradient(gradient))
            .drawBehind {
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent), endY = size.height * 0.6f),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = contentColor)
        Spacer(Modifier.width(9.dp))
        Text(label, color = contentColor, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

/** Frosted glass action for secondary choices. */
@Composable
internal fun SecondaryButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 520f),
        label = "press-secondary"
    )
    val shape = RoundedCornerShape(16.dp)
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick),
        cornerRadius = 16.dp,
        elevation = 0.dp
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = TextMain)
            Spacer(Modifier.width(9.dp))
            Text(label, color = TextMain, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

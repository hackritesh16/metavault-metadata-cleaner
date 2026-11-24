package com.metavault.cleaner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

private val reflection = Brush.linearGradient(listOf(
    Color.White.copy(alpha = 0.075f), Color.White.copy(alpha = 0.022f), Color.White.copy(alpha = 0.045f)
))
private val glassEdge = Brush.linearGradient(listOf(
    Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.27f)
))

internal fun Modifier.auroraGlass(shape: Shape): Modifier =
    clip(shape).background(reflection).border(1.dp, glassEdge, shape)

@Composable
internal fun AuroraContentFrame(scaffoldPadding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(scaffoldPadding).consumeWindowInsets(scaffoldPadding),
        contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = AuroraLayout.contentMaxWidth).fillMaxSize()
            .padding(horizontal = AuroraLayout.gutter(maxWidth)).padding(top = AuroraLayout.gap),
            verticalArrangement = Arrangement.spacedBy(AuroraLayout.padding), content = content)
    }
}

@Composable
internal fun AuroraGlassCard(
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit
) {
    val action = if (onClick == null) Modifier else Modifier
        .heightIn(min = AuroraLayout.touchTarget).clickable(role = Role.Button, onClick = onClick)
    Column(modifier.fillMaxWidth().auroraGlass(AuroraLayout.cardShape).then(action).padding(AuroraLayout.padding),
        verticalArrangement = Arrangement.spacedBy(AuroraLayout.gap), content = content)
}

@Composable
internal fun AuroraGlassButton(
    label: String, icon: ImageVector, onClick: () -> Unit, primary: Boolean = true,
    enabled: Boolean = true, loading: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = AuroraLayout.buttonShape
    Button(onClick = onClick, enabled = enabled, interactionSource = interaction, shape = shape,
        modifier = Modifier.fillMaxWidth().heightIn(min = AuroraLayout.actionHeight).auroraGlass(shape),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = if (pressed) 0.13f else if (primary) 0.055f else 0f),
            contentColor = TextMain, disabledContainerColor = Color.Transparent, disabledContentColor = Muted),
        elevation = null) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Muted)
        else Icon(icon, null, tint = if (!enabled) Muted else if (primary) AccentSoft else TextMain)
        Spacer(Modifier.width(AuroraLayout.compact))
        Text(label)
    }
}

@Composable
internal fun AuroraStatusChip(label: String, indicator: Color, modifier: Modifier = Modifier) {
    Row(modifier.auroraGlass(AuroraLayout.pill).padding(horizontal = AuroraLayout.gap, vertical = AuroraLayout.compact),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AuroraLayout.compact)) {
        Spacer(Modifier.size(7.dp).background(indicator, CircleShape))
        Text(label, color = TextMain, style = TechnicalLabel)
    }
}

@Composable
internal fun AuroraIconContainer(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(modifier.size(36.dp).auroraGlass(AuroraLayout.buttonShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = AccentSoft, modifier = Modifier.size(22.dp))
    }
}

// On translucent surfaces, cyan maintains text contrast over a wider range of backgrounds than violet.
@Composable
internal fun AuroraTextButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = AccentSoft),
        content = content)
}

@Composable
internal fun AuroraOutlinedButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = AuroraLayout.buttonShape,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSoft),
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, border = border,
        colors = colors, content = content)
}

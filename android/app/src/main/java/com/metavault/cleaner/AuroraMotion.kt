package com.metavault.cleaner

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal const val NavigationMillis = 300
private const val CardMillis = 240
internal val LocalMotionEnabled = staticCompositionLocalOf { true }
private val LocalPageEntrance = compositionLocalOf<State<Float>?> { null }

@Composable
internal fun systemMotionEnabled(): Boolean {
    // The window recomposer already observes Android's animator duration scale and its lifecycle.
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    return scale?.let { it.scaleFactor > 0f } ?: ValueAnimator.areAnimatorsEnabled()
}

@Composable
internal fun AuroraPageMotion(page: Any, modifier: Modifier, content: @Composable () -> Unit) {
    key(page) {
        val enabled = LocalMotionEnabled.current
        val entrance = remember { Animatable(if (enabled) 0f else 1f) }
        var settled by remember { mutableStateOf(!enabled) }
        LaunchedEffect(enabled) {
            if (enabled && !settled) entrance.animateTo(1f, tween(NavigationMillis, easing = LinearEasing))
            settled = true
        }
        val active = enabled && !settled
        Box(modifier.then(if (active) Modifier.graphicsLayer {
            val fraction = FastOutSlowInEasing.transform(entrance.value)
            alpha = 0.84f + 0.16f * fraction
            translationX = 8.dp.toPx() * (1 - fraction)
        } else Modifier)) {
            CompositionLocalProvider(LocalPageEntrance provides if (active) entrance.asState() else null, content = content)
        }
    }
}

@Composable
internal fun Modifier.cardEntrance(): Modifier {
    val entrance = LocalPageEntrance.current ?: return this
    return graphicsLayer {
        // One timeline per page. Scrolling through a long library never starts a coroutine per row.
        val fraction = FastOutSlowInEasing.transform((entrance.value * NavigationMillis / CardMillis).coerceAtMost(1f))
        translationY = (1f - fraction) * 6.dp.toPx()
    }
}

@Composable
internal fun Modifier.pressMotion(interaction: MutableInteractionSource): Modifier {
    if (!LocalMotionEnabled.current) return this
    val down by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (down) 0.978f else 1f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium), label = "press")
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

@Composable
internal fun Modifier.selectionMotion(selected: Boolean): Modifier {
    if (!LocalMotionEnabled.current) return this
    val scale = animateFloatAsState(if (selected) 1.09f else 1f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy), label = "selected icon")
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

@Composable
internal fun AuroraLoadingIcon(modifier: Modifier = Modifier) {
    if (LocalMotionEnabled.current) {
        CircularProgressIndicator(modifier.size(20.dp).clearAndSetSemantics {}, color = Muted, strokeWidth = 2.dp)
    } else {
        Icon(Icons.Default.HourglassEmpty, null, modifier.size(20.dp), tint = Muted)
    }
}

@Composable
internal fun AuroraLoadingLabel(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AuroraLayout.compact),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        AuroraLoadingIcon()
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun AuroraProgress(done: Int, total: Int, modifier: Modifier = Modifier) {
    val actual = (done.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    val shown = if (LocalMotionEnabled.current) animateFloatAsState(actual, tween(180), label = "batch progress")
        else rememberUpdatedState(actual)
    LinearProgressIndicator(progress = { shown.value }, modifier = modifier.clearAndSetSemantics {
        progressBarRangeInfo = ProgressBarRangeInfo(actual, 0f..1f)
    }, color = Clean)
}

internal data class SavedNotice(override val message: String) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction = false
    override val duration = SnackbarDuration.Short
}

@Composable
internal fun AuroraSuccessMark() {
    val enabled = LocalMotionEnabled.current
    val arrival = remember { Animatable(0.90f) }
    LaunchedEffect(enabled) {
        if (enabled) arrival.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy))
    }
    Icon(Icons.Filled.CheckCircle, null, tint = Clean,
        modifier = Modifier.size(24.dp).then(if (enabled) Modifier.graphicsLayer {
            scaleX = arrival.value; scaleY = arrival.value
        } else Modifier))
}

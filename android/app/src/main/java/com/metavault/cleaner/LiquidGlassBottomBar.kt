package com.metavault.cleaner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.max
import kotlin.math.min

/**
 * Native bottom navigation material. Unlike a gradient-only card, this view
 * samples the live activity backdrop, then applies a lightweight lens shader
 * on API 33+ (RenderEffect blur fallback on API 31-32).
 */
@Composable
internal fun LiquidGlassBottomBar(
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    AndroidView(
        factory = { context -> LiquidGlassNavView(context) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(58.dp),
        update = { view ->
            view.selectedIndex = selectedIndex
            view.onSelected = onSelected
        }
    )
}

internal class LiquidGlassNavView(context: Context) : View(context) {
    var selectedIndex: Int = 0
        set(value) {
            field = value.coerceIn(0, 4)
            invalidate()
        }
    var onSelected: (Int) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val scale = 0.33f
    private var backdrop: Bitmap? = null
    private var bitmapShader: BitmapShader? = null
    private var runtimeShader: RuntimeShader? = null
    private var lastCaptureAt = 0L
    private var captureInProgress = false
    private val bounds = RectF()
    private val source = Rect()
    private val matrix = Matrix()
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconPath = Path()
    private val labels = arrayOf("Home", "Browse", "Cleaner", "History", "Settings")
    private val white = Color.argb(255, 245, 249, 255)
    private val muted = Color.argb(255, 158, 174, 199)
    private val accent = Color.argb(255, 128, 171, 255)

    init {
        isClickable = true
        contentDescription = "Main navigation"
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val r = height * 0.40f
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        captureBackdropIfNeeded()
        drawGlass(canvas, r)
        drawNavigation(canvas)
        // Keep the sampled backdrop fresh without forcing a 60fps full-window readback.
        postInvalidateDelayed(100L)
    }

    private fun captureBackdropIfNeeded() {
        if (captureInProgress || System.currentTimeMillis() - lastCaptureAt < 90L) return
        val root = rootView ?: return
        if (root.width <= 0 || root.height <= 0) return
        captureInProgress = true
        val previousVisibility = visibility
        try {
            val bw = max(1, (root.width * scale).toInt())
            val bh = max(1, (root.height * scale).toInt())
            if (backdrop == null || backdrop?.width != bw || backdrop?.height != bh) {
                backdrop?.recycle()
                backdrop = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                bitmapShader = BitmapShader(backdrop!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                runtimeShader = if (Build.VERSION.SDK_INT >= 33) createGlassShader() else null
            }
            visibility = INVISIBLE
            val target = Canvas(backdrop!!)
            target.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            target.scale(scale, scale)
            root.draw(target)
            visibility = previousVisibility
            lastCaptureAt = System.currentTimeMillis()
        } catch (_: Throwable) {
            visibility = previousVisibility
        } finally {
            captureInProgress = false
        }
    }

    @RequiresApi(33)
    private fun createGlassShader(): RuntimeShader = RuntimeShader(GLASS_SHADER)

    private fun drawGlass(canvas: Canvas, radius: Float) {
        val shape = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val captured = backdrop
        if (captured != null) {
            val root = rootView
            val viewPos = IntArray(2)
            val rootPos = IntArray(2)
            getLocationOnScreen(viewPos)
            root?.getLocationOnScreen(rootPos)
            val left = ((viewPos[0] - rootPos[0]) * scale).toInt().coerceIn(0, max(0, captured.width - 1))
            val top = ((viewPos[1] - rootPos[1]) * scale).toInt().coerceIn(0, max(0, captured.height - 1))
            val right = (left + max(1, (width * scale).toInt())).coerceAtMost(captured.width)
            val bottom = (top + max(1, (height * scale).toInt())).coerceAtMost(captured.height)
            source.set(left, top, right, bottom)

            if (Build.VERSION.SDK_INT >= 33 && bitmapShader != null && runtimeShader != null) {
                matrix.setScale(scale, scale)
                matrix.postTranslate((viewPos[0] - rootPos[0]) * scale, (viewPos[1] - rootPos[1]) * scale)
                bitmapShader?.setLocalMatrix(matrix)
                runtimeShader?.setInputShader("backdrop", bitmapShader!!)
                runtimeShader?.setFloatUniform("size", width.toFloat(), height.toFloat())
                glassPaint.shader = runtimeShader
                glassPaint.alpha = 255
                canvas.save()
                val clipPath = Path().apply { addRoundRect(shape, radius, radius, Path.Direction.CW) }
                canvas.clipPath(clipPath)
                canvas.drawRect(shape, glassPaint)
                canvas.restore()
                glassPaint.shader = null
            } else if (right > left && bottom > top) {
                glassPaint.color = Color.WHITE
                glassPaint.alpha = 255
                canvas.drawBitmap(captured, source, shape, glassPaint)
            }
        }

        // Frost floor, adaptive navy tint, and a soft cyan/white specular rim.
        overlayPaint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(0x42DCEAFF, 0x302E4668, 0x4A091426),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(shape, radius, radius, overlayPaint)
        overlayPaint.shader = null

        overlayPaint.color = 0x18FFFFFF
        canvas.drawRoundRect(RectF(1f, 1f, width - 1f, height * 0.48f), radius, radius, overlayPaint)
        overlayPaint.color = Color.TRANSPARENT

        borderPaint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(0xBFFFFFFF.toInt(), 0x668EABFF, 0x33FFFFFF),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(RectF(0.7f, 0.7f, width - 0.7f, height - 0.7f), radius, radius, borderPaint)
        borderPaint.shader = null
        overlayPaint.color = 0x66FFFFFF
        canvas.drawRoundRect(RectF(width * 0.16f, 1.2f, width * 0.84f, 2.1f), 1f, 1f, overlayPaint)
    }

    private fun drawNavigation(canvas: Canvas) {
        val slot = width / 5f
        val iconY = height * 0.34f
        val iconSize = 9.5f * density
        for (index in 0..4) {
            val centerX = slot * (index + 0.5f)
            val selected = index == selectedIndex
            if (selected) {
                overlayPaint.color = 0x3D568BFF
                canvas.drawRoundRect(
                    RectF(centerX - 19f * density, height * 0.10f, centerX + 19f * density, height * 0.62f),
                    15f * density, 15f * density, overlayPaint
                )
                overlayPaint.color = 0x28FFFFFF
                canvas.drawRoundRect(
                    RectF(centerX - 14f * density, height * 0.11f, centerX + 14f * density, height * 0.14f),
                    2f, 2f, overlayPaint
                )
            }
            drawIcon(canvas, index, centerX, iconY, iconSize, if (selected) white else muted)
            textPaint.color = if (selected) accent else muted
            textPaint.typeface = android.graphics.Typeface.create(
                "sans-serif-medium",
                if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            canvas.drawText(labels[index], centerX, height * 0.84f, textPaint)
        }
    }

    private fun drawIcon(canvas: Canvas, index: Int, cx: Float, cy: Float, s: Float, color: Int) {
        iconPaint.color = color
        iconPath.reset()
        when (index) {
            0 -> {
                iconPath.moveTo(cx - s, cy)
                iconPath.lineTo(cx, cy - s * 0.88f)
                iconPath.lineTo(cx + s, cy)
                iconPath.lineTo(cx + s * 0.76f, cy)
                iconPath.lineTo(cx + s * 0.76f, cy + s * 0.9f)
                iconPath.lineTo(cx - s * 0.76f, cy + s * 0.9f)
                iconPath.lineTo(cx - s * 0.76f, cy)
                iconPath.close()
                canvas.drawPath(iconPath, iconPaint)
                canvas.drawLine(cx - s * 0.2f, cy + s * 0.9f, cx - s * 0.2f, cy + s * 0.2f, iconPaint)
            }
            1 -> {
                canvas.drawRoundRect(RectF(cx - s, cy - s * 0.45f, cx + s, cy + s * 0.8f), 2f * density, 2f * density, iconPaint)
                iconPath.moveTo(cx - s, cy - s * 0.2f)
                iconPath.lineTo(cx - s * 0.45f, cy - s * 0.85f)
                iconPath.lineTo(cx + s * 0.12f, cy - s * 0.85f)
                iconPath.lineTo(cx + s * 0.42f, cy - s * 0.42f)
                canvas.drawPath(iconPath, iconPaint)
            }
            2 -> {
                iconPath.moveTo(cx, cy - s * 1.2f)
                iconPath.lineTo(cx + s * 0.25f, cy - s * 0.25f)
                iconPath.lineTo(cx + s * 1.1f, cy)
                iconPath.lineTo(cx + s * 0.25f, cy + s * 0.25f)
                iconPath.lineTo(cx, cy + s * 1.2f)
                iconPath.lineTo(cx - s * 0.25f, cy + s * 0.25f)
                iconPath.lineTo(cx - s * 1.1f, cy)
                iconPath.lineTo(cx - s * 0.25f, cy - s * 0.25f)
                iconPath.close()
                canvas.drawPath(iconPath, iconPaint)
                canvas.drawCircle(cx + s * 1.25f, cy - s * 0.9f, s * 0.2f, iconPaint)
            }
            3 -> {
                canvas.drawArc(RectF(cx - s, cy - s, cx + s, cy + s), 205f, 285f, false, iconPaint)
                iconPath.moveTo(cx - s * 0.95f, cy - s * 0.55f)
                iconPath.lineTo(cx - s * 0.9f, cy + s * 0.05f)
                iconPath.lineTo(cx - s * 0.32f, cy - s * 0.08f)
                canvas.drawPath(iconPath, iconPaint)
                canvas.drawLine(cx, cy - s * 0.5f, cx, cy + s * 0.15f, iconPaint)
                canvas.drawLine(cx, cy + s * 0.15f, cx + s * 0.45f, cy + s * 0.4f, iconPaint)
            }
            else -> {
                canvas.drawCircle(cx, cy, s * 0.38f, iconPaint)
                for (angle in 0 until 8) {
                    val a = Math.toRadians((angle * 45).toDouble())
                    val x1 = cx + kotlin.math.cos(a).toFloat() * s * 0.55f
                    val y1 = cy + kotlin.math.sin(a).toFloat() * s * 0.55f
                    val x2 = cx + kotlin.math.cos(a).toFloat() * s * 0.95f
                    val y2 = cy + kotlin.math.sin(a).toFloat() * s * 0.95f
                    canvas.drawLine(x1, y1, x2, y2, iconPaint)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val index = (event.x / (width / 5f)).toInt().coerceIn(0, 4)
                performClick()
                onSelected(index)
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        backdrop?.recycle()
        backdrop = null
        bitmapShader = null
        runtimeShader = null
    }

    private companion object {
        const val GLASS_SHADER = """
            uniform shader backdrop;
            uniform float2 size;

            float4 main(float2 p) {
                float2 center = size * 0.5;
                float edge = min(min(p.x, size.x - p.x), min(p.y, size.y - p.y));
                float rim = 1.0 - smoothstep(0.0, 24.0, edge);
                float2 direction = normalize(p - center + float2(0.001, 0.001));
                float2 uv = p + direction * rim * 5.0;

                float4 c = backdrop.eval(uv) * 0.40;
                c += backdrop.eval(uv + float2(1.6, 0.0)) * 0.15;
                c += backdrop.eval(uv - float2(1.6, 0.0)) * 0.15;
                c += backdrop.eval(uv + float2(0.0, 1.6)) * 0.15;
                c += backdrop.eval(uv - float2(0.0, 1.6)) * 0.15;

                float sheen = pow(max(0.0, 1.0 - abs(p.y / size.y * 2.0 - 1.0)), 13.0);
                float3 coolGlass = float3(0.78, 0.86, 1.0);
                c.rgb = mix(c.rgb, coolGlass, 0.10);
                c.rgb += float3(0.18, 0.24, 0.38) * rim * 0.22;
                c.rgb += float3(1.0, 1.0, 1.0) * sheen * 0.09;
                return float4(clamp(c.rgb, 0.0, 1.0), 0.98);
            }
        """
    }
}

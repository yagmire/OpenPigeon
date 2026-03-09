package com.openbubbles.openpigeon.pool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Draws a small pool ball indicating whether the player has stripes or solids.
 * Pass [isStripes] = true for a striped ball, false for a solid ball, null for unassigned ("?").
 * Pass [ballNumber] to pick a specific ball color (1–7 solid, 9–15 stripe); defaults look fine.
 */
class BallTypeIndicatorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** true = stripes, false = solids, null = not yet decided */
    var isStripes: Boolean? = null
        set(value) { field = value; invalidate() }

    // Ball colours indexed by ball number (index 0 unused)
    private val ballColors = intArrayOf(
        0, // 0 - unused
        0xFFE8C840.toInt(), // 1 - yellow solid
        0xFF2255CC.toInt(), // 2 - blue solid
        0xFFCC3322.toInt(), // 3 - red solid
        0xFF882299.toInt(), // 4 - purple solid
        0xFFDD6622.toInt(), // 5 - orange solid
        0xFF228833.toInt(), // 6 - green solid
        0xFF993322.toInt(), // 7 - maroon solid
        0xFF111111.toInt(), // 8 - black (not used here)
        0xFFE8C840.toInt(), // 9  - yellow stripe
        0xFF2255CC.toInt(), // 10 - blue stripe
        0xFFCC3322.toInt(), // 11 - red stripe
        0xFF882299.toInt(), // 12 - purple stripe
        0xFFDD6622.toInt(), // 13 - orange stripe
        0xFF228833.toInt(), // 14 - green stripe
        0xFF993322.toInt(), // 15 - maroon stripe
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55000000
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 3f

        // shadow
        canvas.drawCircle(cx + 2f, cy + 3f, r, shadowPaint)

        when (val type = isStripes) {
            null -> {
                // Unassigned — draw a grey ball with "?"
                fillPaint.color = 0xFF444444.toInt()
                canvas.drawCircle(cx, cy, r, fillPaint)
                textPaint.textSize = r * 1.1f
                textPaint.color = 0xAAFFFFFF.toInt()
                canvas.drawText("?", cx, cy + r * 0.38f, textPaint)
            }
            false -> {
                // Solid ball (use ball 1 – yellow)
                val color = ballColors[1]
                fillPaint.color = color
                canvas.drawCircle(cx, cy, r, fillPaint)

                // white number circle
                canvas.drawCircle(cx, cy, r * 0.42f, whitePaint)
                textPaint.textSize = r * 0.55f
                textPaint.color = Color.BLACK
                canvas.drawText("1", cx, cy + r * 0.18f, textPaint)
            }
            true -> {
                // Striped ball (use ball 9 – yellow stripe)
                val color = ballColors[9]

                // white base
                canvas.drawCircle(cx, cy, r, whitePaint)

                // coloured stripe band across the middle third
                val stripeTop = cy - r * 0.38f
                val stripeBottom = cy + r * 0.38f
                fillPaint.color = color
                // clip stripe to circle shape via a rect — we use saveLayer + clip
                canvas.save()
                val clipPath = android.graphics.Path().apply {
                    addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
                }
                canvas.clipPath(clipPath)
                canvas.drawRect(cx - r, stripeTop, cx + r, stripeBottom, fillPaint)
                canvas.restore()

                // white number circle
                canvas.drawCircle(cx, cy, r * 0.42f, whitePaint)
                textPaint.textSize = r * 0.55f
                textPaint.color = Color.BLACK
                canvas.drawText("9", cx, cy + r * 0.18f, textPaint)
            }
        }

        // specular highlight
        val highlightR = r * 0.32f
        val highlightX = cx - r * 0.25f
        val highlightY = cy - r * 0.3f
        highlightPaint.shader = RadialGradientCompat(
            highlightX, highlightY, highlightR,
            intArrayOf(0xBBFFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 1f)
        )
        canvas.drawCircle(highlightX, highlightY, highlightR, highlightPaint)

        // outer ring
        canvas.drawCircle(cx, cy, r, borderPaint)
    }

    /** Helper so we don't have to import android.graphics.RadialGradient at call site */
    private fun RadialGradientCompat(
        x: Float, y: Float, radius: Float,
        colors: IntArray, stops: FloatArray
    ): android.graphics.RadialGradient =
        android.graphics.RadialGradient(x, y, radius, colors, stops, Shader.TileMode.CLAMP)
}

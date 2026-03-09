package com.openbubbles.openpigeon.pool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class SpinSelectorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var spinX = 0f  // -1..1 normalized
    var spinY = 0f  // -1..1 normalized

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2a2a2a.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FFFFFF
        strokeWidth = 1.5f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val dotShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - 4f

        // filled circle background
        canvas.drawCircle(cx, cy, radius, circlePaint)

        // crosshair lines
        canvas.drawLine(cx - radius, cy, cx + radius, cy, crosshairPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, crosshairPaint)

        // border ring
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // spin dot
        val dotX = cx + spinX * radius
        val dotY = cy + spinY * radius
        canvas.drawCircle(dotX + 2f, dotY + 2f, 10f, dotShadowPaint)
        canvas.drawCircle(dotX, dotY, 10f, dotPaint)
    }
}
package com.openbubbles.openpigeon.pool

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.view.SurfaceHolder
import androidx.core.animation.doOnEnd
import com.openbubbles.openpigeon.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import android.graphics.BlurMaskFilter
import kotlin.math.sign

class PoolRenderer(val holder: SurfaceHolder, val activity: PoolActivity) : Thread(), SurfaceHolder.Callback {
    var running = true

    val bitmap: Bitmap by lazy {
        BitmapFactory.decodeResource(
            activity.resources,
            if (activity.isHard) R.drawable.pool_transparent_hard else R.drawable.`pool_transparent`
        )
    }

    val cue: Bitmap = BitmapFactory.decodeResource(activity.resources, R.drawable.cue)

    init {
        holder.addCallback(this)
    }

    private val TARGET_FPS: Int = 60
    private val FRAME_TIME: Long = (1000 / TARGET_FPS).toLong()

    var cueRot = 0.0f
    var cueDraw = 0.0f
    var cueAlpha = 1.0f
    var cuePos = floatArrayOf(0f, 0f)

    private val _transform = Matrix()
    private var lastSurfaceWidth = -1
    private var lastSurfaceHeight = -1

    val transform: Matrix
        get() {
            val w = holder.surfaceFrame.width()
            val h = holder.surfaceFrame.height()
            if (w != lastSurfaceWidth || h != lastSurfaceHeight) {
                lastSurfaceWidth = w
                lastSurfaceHeight = h
                val desiredWidth  = 441.189f
                val desiredHeight = 784.743f
                val insetPx = 12f
                val availableWidth = w - insetPx * 2
                val scale = availableWidth / desiredWidth
                _transform.reset()
                _transform.postScale(scale, -scale)
                _transform.postRotate(-90f)
                val extra = (h.toFloat() - desiredHeight * scale) / 2
                _transform.postTranslate(w.toFloat() - insetPx, h.toFloat() - extra)
            }
            return _transform
        }


    fun angleDifference(a: Double, b: Double): Double {
        var diff = (a - b + PI) % (2 * PI)
        if (diff < 0) diff += 2 * PI
        return diff - PI
    }

    external fun update(table: Long): Boolean

    private fun drawFrame(canvas: Canvas) {
        synchronized(activity) {
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()

            // Only rebuild gradient when surface size changes
            if (w != lastGradientW || h != lastGradientH) {
                lastGradientW = w
                lastGradientH = h
                gradientPaint.shader = android.graphics.RadialGradient(
                    w / 2f, h / 2f,
                    maxOf(w, h) * 0.6f,
                    intArrayOf(0xFFF0EFE9.toInt(), 0xFFB0AEA8.toInt()),
                    floatArrayOf(0f, 1f),
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            canvas.drawPaint(gradientPaint)

            canvas.concat(transform)

            if (!update(activity.table) && activity.mode == PoolActivity.PoolMode.Playing) {
                activity.handleFinishPlay()
            }

            canvas.drawBitmap(bitmap, null, RectF(-0.057f, -0.189f, 784.743f, 441.189f), null)

            if (false) {
                val debugPaint = Paint().apply {
                    color = 0xFFFF00FF.toInt() // magenta
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    isAntiAlias = true
                }
                val pocketPaint = Paint().apply {
                    color = 0xFF00FFFF.toInt() // cyan
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    isAntiAlias = true
                }
                val labelPaint = Paint().apply {
                    color = 0xFFFFFF00.toInt() // yellow
                    textSize = 14f
                    isAntiAlias = true
                }

                // Approximate inner cushion wall bounds
                // Table bitmap spans roughly 0..784 x 0..441
                // Corner pockets sit at ~40,40 / 744,40 / 40,400 / 744,400
                // Side pockets at ~392,28 / 392,412
                // Cushion walls inset from pocket edges
                val left   = 60f
                val right  = 724f
                val top    = 55f
                val bottom = 386f

                // Left wall (two segments around the side pocket gap)
                canvas.drawLine(left, top + 20f,    left, bottom - 20f, debugPaint)
                // Right wall
                canvas.drawLine(right, top + 20f,   right, bottom - 20f, debugPaint)
                // Top wall (two segments with gap at corner pockets)
                canvas.drawLine(left + 20f, top,    392f - 18f, top, debugPaint)
                canvas.drawLine(392f + 18f, top,    right - 20f, top, debugPaint)
                // Bottom wall
                canvas.drawLine(left + 20f, bottom, 392f - 18f, bottom, debugPaint)
                canvas.drawLine(392f + 18f, bottom, right - 20f, bottom, debugPaint)

                // Draw pockets
                for ((i, hole) in activity.holes.withIndex()) {
                    canvas.drawCircle(hole[0].toFloat(), hole[1].toFloat(), 20f, pocketPaint)
                    canvas.drawText("P$i", hole[0].toFloat() - 7f, hole[1].toFloat() + 5f, labelPaint)
                }

                // Table bounding box (full bitmap rect)
                canvas.drawRect(0f, 0f, 784.743f, 441.189f, Paint().apply {
                    color = 0x44FFFFFF
                    style = Paint.Style.STROKE
                    strokeWidth = 1f
                })
            }

            for (ball in activity.poolBalls) {
                if (ball.sunk) continue
                ball.updateRotation(transform = transform)
                canvas.drawCircle(ball.x + 2f, ball.y + 2f, 11f, shadowPaint)
            }

            if (activity.call8Ball) {
                for (hole in activity.holes) {
                    canvas.drawCircle(hole[0].toFloat(), hole[1].toFloat(), 20f, holePaint)
                }
            }

            if (activity.mode == PoolActivity.PoolMode.Aiming ||
                activity.mode == PoolActivity.PoolMode.ReplayAiming ||
                activity.mode == PoolActivity.PoolMode.Playing) {

                val translation = if (activity.mode != PoolActivity.PoolMode.Playing)
                    floatArrayOf(activity.cueBall.x, activity.cueBall.y)
                else cuePos

                val degrees = Math.toDegrees(cueRot.toDouble()).toFloat()
                canvas.save()
                canvas.translate(translation[0], translation[1])
                canvas.rotate(degrees - 90f)

                val gap = 20f + cueDraw
                val cueLength = 325f
                val halfWidth = 8f

                val centerY = -(gap + cueLength / 2f)
                canvas.scale(1f, -1f, 0f, centerY)

                cuePaint.alpha = (cueAlpha * 255).roundToInt()
                canvas.drawBitmap(
                    cue,
                    null,
                    RectF(-halfWidth, -gap - cueLength, halfWidth, -gap),
                    cuePaint
                )
                canvas.restore()
            }
            activity.runOnUiThread {
                activity.findViewById<AimingOverlayView>(R.id.aimingOverlay).invalidate()
            }
        }
    }

    var cueAnimator: ValueAnimator? = null
    fun setCueVisible(visible: Boolean) {
        cueAnimator?.cancel()
        cueAnimator = ValueAnimator.ofFloat(cueAlpha, if (visible) 1f else 0f)?.apply {
            duration = 200L
            doOnEnd { cueAnimator = null }
            addUpdateListener { animation -> cueAlpha = animation.animatedValue as Float }
            start()
        }
    }

    var hasSurface = false

    // Cached paints — allocated once, reused every frame
    private val gradientPaint = Paint()
    private val shadowPaint = Paint().apply {
        color = 0x66000000
        isAntiAlias = true
        maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
    }
    private val holePaint = Paint().apply { color = 0x55FFFFFF }
    private val cuePaint = Paint()
    private var lastGradientW = -1f
    private var lastGradientH = -1f
    override fun run() {
        var startTime: Long
        var timeMillis: Long
        var waitTime: Long
        var frame = 0L


        while (running) {
            startTime = System.nanoTime()


            if (hasSurface) {

                val canvas = holder.lockHardwareCanvas()
                if (canvas != null) {
                    drawFrame(canvas)
                    holder.unlockCanvasAndPost(canvas)

                    frame += 1
                }
            }

            timeMillis = (System.nanoTime() - startTime) / 1000000
//            if ((frame % 60) == 0L) {
//                Log.i("PoolRenderer", "Did frame in $timeMillis ms")
//            }

            waitTime = FRAME_TIME - timeMillis

            if (waitTime > 0) {
                try {
                    sleep(waitTime)
                } catch (e: InterruptedException) {
                }
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d("Surface", "Created")
        hasSurface = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d("Surface", "Changed width: $width, Height: $height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d("Surface", "Destroyed")
        hasSurface = false
    }
}
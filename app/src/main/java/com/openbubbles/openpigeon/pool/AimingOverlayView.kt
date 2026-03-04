package com.openbubbles.openpigeon.pool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class AimingOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var activity: PoolActivity? = null

    override fun onDraw(canvas: Canvas) {
        val act = activity ?: return
        if (act.mode != PoolActivity.PoolMode.Aiming) return

        canvas.concat(act.renderer.transform)

        val cueRot = act.renderer.cueRot

        if (act.scratch) {
            canvas.drawCircle(act.cueBall.x, act.cueBall.y, 15f, Paint().apply {
                color = Color.WHITE
                strokeWidth = 2.5f
                style = Paint.Style.STROKE
                isAntiAlias = true
            })
        }

        var closestBall: PoolActivity.PoolBall? = null
        var closestDistance = Float.MAX_VALUE
        var hitPointX = 0f
        var hitPointY = 0f

        for (ball in act.poolBalls) {
            if (ball.number == 0 || ball.sunk) continue
            val otherBallX = ball.x - act.cueBall.x
            val otherBallY = ball.y - act.cueBall.y
            val slope = tan(cueRot)
            val A = slope * slope + 1
            val B = 2 * (-slope * otherBallY - otherBallX)
            val C = otherBallY * otherBallY + otherBallX * otherBallX - 400
            val discriminant = B * B - 4 * A * C
            if (discriminant <= 0) continue
            val pointsRight = cos(cueRot) > 0
            val direction = if (pointsRight) -1 else 1
            val xCoord = (-B + sqrt(discriminant) * direction) / 2 / A
            if (pointsRight && xCoord < 0) continue
            if (!pointsRight && xCoord > 0) continue
            if (abs(xCoord) < closestDistance) {
                closestDistance = abs(xCoord)
                closestBall = ball
                hitPointY = slope * xCoord + act.cueBall.y
                hitPointX = xCoord + act.cueBall.x
            }
        }

        if (closestBall != null) {
            val paint = Paint().apply {
                color = Color.WHITE
                strokeWidth = 2.5f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
            canvas.drawCircle(hitPointX, hitPointY, 9f, paint)
            canvas.drawLine(
                hitPointX - cos(cueRot) * 10f, hitPointY - sin(cueRot) * 10f,
                act.cueBall.x + cos(cueRot) * 10f, act.cueBall.y + sin(cueRot) * 10f,
                paint
            )

            val stripes = act.iAmStripes
            val ballNotMine = stripes != null && ((stripes && !closestBall.isStripe) || (!stripes && !closestBall.isSolid))
            val hasMoreBalls = stripes == null || act.poolBalls.count { !it.sunk && ((stripes && it.isStripe) || (!stripes && it.isSolid)) } != 0

            if ((ballNotMine || (closestBall.number == 8 && hasMoreBalls)) && (closestBall.number != 8 || hasMoreBalls)) {
                canvas.drawLine(hitPointX - 10, hitPointY - 10, hitPointX + 10, hitPointY + 10, paint)
                canvas.drawLine(hitPointX + 10, hitPointY - 10, hitPointX - 10, hitPointY + 10, paint)
            } else if (!act.isHard) {
                val interBallX = closestBall.x - hitPointX
                val interBallY = closestBall.y - hitPointY
                val interBallAngle = atan2(interBallY, interBallX)
                val directness = (act.renderer.angleDifference(interBallAngle.toDouble(), cueRot.toDouble()) / (PI / 2)).toFloat()
                canvas.drawLine(
                    closestBall.x, closestBall.y,
                    closestBall.x + cos(interBallAngle) * 70f * (1 - abs(directness)),
                    closestBall.y + sin(interBallAngle) * 70f * (1 - abs(directness)),
                    paint
                )
                var tangentAngle = if (directness < 0) interBallAngle + PI.toFloat() / 2 else interBallAngle - PI.toFloat() / 2
                canvas.drawLine(
                    hitPointX + cos(tangentAngle) * 10f, hitPointY + sin(tangentAngle) * 10f,
                    hitPointX + cos(tangentAngle) * 10f + cos(tangentAngle) * 70f * abs(directness),
                    hitPointY + sin(tangentAngle) * 10f + sin(tangentAngle) * 70f * abs(directness),
                    paint
                )
            }
        }
    }
}
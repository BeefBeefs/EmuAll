package com.beefbeefs.emuall

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * A compact, touch-friendly analogue stick. Values are normalized to -1..1,
 * with positive Y pointing down as required by libretro's analogue API.
 */
class AnalogStickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var onValueChanged: ((x: Float, y: Float) -> Unit)? = null

    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x553C4940
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA718078.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDDB8C8BD.toInt()
        style = Paint.Style.FILL
    }
    private val knobRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEE526058.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private var valueX = 0f
    private var valueY = 0f
    private var active = false

    init {
        isClickable = true
        contentDescription = "Analog stick"
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f - 4f).coerceAtLeast(1f)
        canvas.drawCircle(cx, cy, radius, outerPaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        val knobRadius = radius * .42f
        val knobX = cx + valueX * radius * .55f
        val knobY = cy + valueY * radius * .55f
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobRingPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                active = true
                updateValue(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                active = false
                valueX = 0f
                valueY = 0f
                onValueChanged?.invoke(0f, 0f)
                invalidate()
                performClick()
                return true
            }
        }
        return active
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateValue(x: Float, y: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f - 8f).coerceAtLeast(1f)
        var dx = (x - cx) / radius
        var dy = (y - cy) / radius
        val rawMagnitude = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (rawMagnitude > 1f) {
            dx /= rawMagnitude
            dy /= rawMagnitude
        }
        // A small dead zone prevents accidental drift when the thumb is near
        // the neutral position, while retaining the full travel at the edge.
        val deadZone = .12f
        val magnitude = rawMagnitude.coerceAtMost(1f)
        val adjusted = if (magnitude < deadZone) 0f else ((magnitude - deadZone) / (1f - deadZone)).coerceIn(0f, 1f)
        val directionX = if (magnitude == 0f) 0f else dx / magnitude.coerceAtLeast(1e-6f)
        val directionY = if (magnitude == 0f) 0f else dy / magnitude.coerceAtLeast(1e-6f)
        valueX = directionX * adjusted
        valueY = directionY * adjusted
        onValueChanged?.invoke(valueX, valueY)
        invalidate()
    }
}

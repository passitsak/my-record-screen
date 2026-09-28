package com.example.myrecordsgreen

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class RecordingGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 16f // Glow border stroke width
        color = Color.RED
    }

    private var alphaValue = 255
    private var animator: ValueAnimator? = null
    private var isGlowing = false

    fun startGlow() {
        if (isGlowing) return
        isGlowing = true
        visibility = VISIBLE

        // Create smooth pulsing animation (Pulse Effect)
        animator = ValueAnimator.ofInt(80, 255).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                alphaValue = animation.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    fun stopGlow() {
        isGlowing = false
        animator?.cancel()
        animator = null
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isGlowing) return

        paint.color = Color.argb(alphaValue, 255, 0, 0)
        
        // Draw glowing border around the screen
        val strokeOffset = paint.strokeWidth / 2
        canvas.drawRect(
            strokeOffset,
            strokeOffset,
            width.toFloat() - strokeOffset,
            height.toFloat() - strokeOffset,
            paint
        )
    }
}
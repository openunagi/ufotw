package com.first.ufotw

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

class WaveformPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var steps: List<PatternStep> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var loop: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var playheadProgress: Float? = null
        set(value) {
            field = value
            invalidate()
        }

    private val warmPaint = Paint().apply {
        color = 0xFFFF8800.toInt()
        isAntiAlias = true
    }

    private val cyanPaint = Paint().apply {
        color = 0xFF22DDCC.toInt()
        isAntiAlias = true
    }

    private val baselinePaint = Paint().apply {
        color = 0xFF1A3838.toInt()
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val loopTextPaint = Paint().apply {
        color = 0xFFAAAAAA.toInt()
        isAntiAlias = true
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 10f, context.resources.displayMetrics
        )
    }

    private val playheadPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (steps.isEmpty()) return

        val totalDur = steps.sumOf { it.durationMs }
        if (totalDur == 0L) return

        val w = width.toFloat()
        val h = height.toFloat()

        var x = 0f
        for (step in steps) {
            val barW = (step.durationMs.toFloat() / totalDur) * w
            val barH = (step.speed / 100f) * (h - 4f)
            val paint = if (step.direction == 0) warmPaint else cyanPaint
            canvas.drawRect(x, h - 2f - barH, x + barW, h - 2f, paint)
            x += barW
        }

        // baseline
        canvas.drawLine(0f, h - 2f, w, h - 2f, baselinePaint)

        // loop indicator
        if (loop) {
            val text = "↻"
            val textW = loopTextPaint.measureText(text)
            canvas.drawText(text, w - textW - 4f, loopTextPaint.textSize + 2f, loopTextPaint)
        }

        // playhead
        playheadProgress?.let { progress ->
            val px = progress * w
            canvas.drawLine(px, 0f, px, h, playheadPaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultHeightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 64f, context.resources.displayMetrics
        ).toInt()
        val resolvedWidth = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val resolvedHeight = resolveSize(defaultHeightPx, heightMeasureSpec)
        setMeasuredDimension(resolvedWidth, resolvedHeight)
    }
}

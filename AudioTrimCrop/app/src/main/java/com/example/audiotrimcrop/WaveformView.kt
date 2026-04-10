package com.example.audiotrimcrop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * Renders an audio waveform and exposes two draggable handles for selecting
 * a trim range. Also draws a playback-position / seek-cursor indicator.
 *
 * Tap anywhere NOT on a handle to move the seek cursor.
 */
class WaveformView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    // ── Public state ──────────────────────────────────────────────────────────

    var durationMs: Long = 0L
        private set

    var startMs: Long = 0L
        private set

    var endMs: Long = 0L
        private set

    /** Current playback position or seek cursor. -1 = hidden. */
    var playbackPositionMs: Long = -1L
        set(v) { field = v; invalidate() }

    var onSelectionChanged: ((Long, Long) -> Unit)? = null

    /** Called when the user taps the waveform (not on a handle) to seek. */
    var onSeekTo: ((Long) -> Unit)? = null

    // ── Private state ─────────────────────────────────────────────────────────

    private var amplitudes = FloatArray(0)

    private enum class Drag { NONE, START, END }
    private var drag = Drag.NONE
    private var tapDownX = 0f

    private val dp = ctx.resources.displayMetrics.density
    private val handleTouchSlop = dp * 28f
    private val handleRadius    = dp * 9f

    // ── Paints ────────────────────────────────────────────────────────────────

    private val paintSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00BCD4.toInt()
        style = Paint.Style.FILL
    }
    private val paintDim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt()
        style = Paint.Style.FILL
    }
    private val paintOverlay = Paint().apply {
        color = 0x1A00BCD4.toInt()
        style = Paint.Style.FILL
    }
    private val paintHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF9800.toInt()
        style = Paint.Style.FILL
    }
    private val paintHandleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF9800.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp * 2.5f
    }
    private val paintPlayback = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp * 2f
    }
    private val paintCursorHead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setWaveformData(amps: FloatArray, durMs: Long) {
        amplitudes = amps
        durationMs = durMs
        startMs    = 0L
        endMs      = durMs
        playbackPositionMs = 0L
        invalidate()
    }

    fun setSelection(newStart: Long, newEnd: Long) {
        startMs = newStart.coerceIn(0, durationMs)
        endMs   = newEnd.coerceIn(0, durationMs)
        invalidate()
    }

    // ── Coordinate helpers ────────────────────────────────────────────────────

    private fun msToX(ms: Long): Float {
        val w = (width - paddingLeft - paddingRight).toFloat()
        return paddingLeft + if (durationMs > 0) w * ms / durationMs else 0f
    }

    private fun xToMs(x: Float): Long {
        val w = (width - paddingLeft - paddingRight).toFloat()
        return if (w > 0) ((x - paddingLeft) / w * durationMs).toLong()
            .coerceIn(0, durationMs)
        else 0L
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (amplitudes.isEmpty() || durationMs == 0L) return

        val usableW = (width - paddingLeft - paddingRight).toFloat()
        val usableH = (height - paddingTop - paddingBottom).toFloat()
        val cx      = paddingTop + usableH / 2f
        val maxHalf = usableH * 0.44f

        val startX = msToX(startMs)
        val endX   = msToX(endMs)
        val n      = amplitudes.size
        val barW   = usableW / n

        // Waveform bars
        val rect = RectF()
        for (i in 0 until n) {
            val bx    = paddingLeft + i * barW
            val half  = max(amplitudes[i] * maxHalf, dp * 2f)
            val barMs = durationMs * i / n
            rect.set(bx + 1f, cx - half, bx + barW - 1f, cx + half)
            canvas.drawRoundRect(rect, dp * 2f, dp * 2f,
                if (barMs in startMs..endMs) paintSelected else paintDim)
        }

        // Selection tint overlay
        val top = paddingTop.toFloat()
        val bot = (height - paddingBottom).toFloat()
        canvas.drawRect(startX, top, endX, bot, paintOverlay)

        // Start handle
        canvas.drawLine(startX, top, startX, bot, paintHandleLine)
        canvas.drawCircle(startX, cx, handleRadius, paintHandle)

        // End handle
        canvas.drawLine(endX, top, endX, bot, paintHandleLine)
        canvas.drawCircle(endX, cx, handleRadius, paintHandle)

        // Playback / seek cursor
        if (playbackPositionMs >= 0L) {
            val px = msToX(playbackPositionMs)
            canvas.drawLine(px, top, px, bot, paintPlayback)
            // small triangle head at top
            canvas.drawCircle(px, top + dp * 5f, dp * 5f, paintCursorHead)
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (durationMs == 0L) return false
        val x = e.x
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                val dStart = abs(x - msToX(startMs))
                val dEnd   = abs(x - msToX(endMs))
                drag = when {
                    dStart <= handleTouchSlop && dStart <= dEnd -> Drag.START
                    dEnd   <= handleTouchSlop                   -> Drag.END
                    else                                         -> Drag.NONE
                }
                if (drag != Drag.NONE) {
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                tapDownX = x
                return true  // always consume to receive ACTION_UP
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag == Drag.NONE) return true
                val ms = xToMs(x)
                when (drag) {
                    Drag.START -> startMs = ms.coerceIn(0, endMs - MIN_SPAN_MS)
                    Drag.END   -> endMs   = ms.coerceIn(startMs + MIN_SPAN_MS, durationMs)
                    else       -> {}
                }
                onSelectionChanged?.invoke(startMs, endMs)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent.requestDisallowInterceptTouchEvent(false)
                if (drag == Drag.NONE && abs(x - tapDownX) < handleTouchSlop) {
                    // Tap-to-seek: move cursor to tapped position
                    val ms = xToMs(x).coerceIn(0L, durationMs)
                    playbackPositionMs = ms
                    onSeekTo?.invoke(ms)
                }
                drag = Drag.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                drag = Drag.NONE
            }
        }
        return true
    }

    companion object {
        private const val MIN_SPAN_MS = 500L
    }
}

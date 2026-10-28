package dev.glide

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.os.Handler

/**
 * One virtual finger driven live: down, any number of moves, up. Each step is dispatched as a
 * continuation of the previous stroke, so drags, long-presses and scrolls happen in real time.
 * Only one gesture may be in flight; moves that arrive meanwhile are merged into the next segment.
 */
class Finger(private val service: AccessibilityService, private val main: Handler) {
    private sealed interface Op
    private data class Down(val x: Float, val y: Float) : Op
    private data class Move(val x: Float, val y: Float) : Op
    private data object Up : Op

    private val queue = ArrayDeque<Op>()
    private var stroke: StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f
    private var busy = false

    /** Logical state: true from [down] until [up], even while segments are still queued. */
    var isDown = false
        private set

    fun down(x: Float, y: Float) {
        if (isDown) return
        isDown = true
        queue += Down(x, y)
        pump()
    }

    fun moveTo(x: Float, y: Float) {
        if (!isDown) return
        queue += Move(x, y)
        pump()
    }

    fun up() {
        if (!isDown) return
        isDown = false
        queue += Up
        pump()
    }

    private fun pump() {
        if (busy) return
        val op = queue.removeFirstOrNull() ?: return
        when (op) {
            is Down -> {
                lastX = op.x; lastY = op.y
                dispatch(StrokeDescription(Path().apply { moveTo(op.x, op.y) }, 0, SEGMENT_MS, true))
            }
            is Move -> {
                val prev = stroke ?: return pump()
                val path = Path().apply { moveTo(lastX, lastY); lineTo(op.x, op.y) }
                lastX = op.x; lastY = op.y
                while (queue.firstOrNull() is Move) {
                    val m = queue.removeFirst() as Move
                    path.lineTo(m.x, m.y)
                    lastX = m.x; lastY = m.y
                }
                dispatch(prev.continueStroke(path, 0, SEGMENT_MS, true))
            }
            Up -> {
                val prev = stroke ?: return pump()
                dispatch(prev.continueStroke(Path().apply { moveTo(lastX, lastY) }, 0, 1, false))
                stroke = null
            }
        }
    }

    private fun dispatch(s: StrokeDescription) {
        busy = true
        if (s.willContinue()) stroke = s
        val ok = service.dispatchGesture(GestureDescription.Builder().addStroke(s).build(), callback, main)
        if (!ok) reset()
    }

    private val callback = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(g: GestureDescription?) {
            busy = false
            pump()
        }

        // A real touch on the screen, or the system, cancelled our finger: start clean on the next press.
        override fun onCancelled(g: GestureDescription?) = reset()
    }

    private fun reset() {
        busy = false
        stroke = null
        isDown = false
        queue.clear()
    }

    companion object {
        // Time the finger takes to travel one merged segment. Lower = less lag, higher = smoother on slow phones.
        private const val SEGMENT_MS = 8L
    }
}

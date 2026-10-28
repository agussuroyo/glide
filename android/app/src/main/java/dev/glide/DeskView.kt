package dev.glide

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.glide.Ui.color
import dev.glide.Ui.dp

/**
 * The Mac and this phone side by side, to scale (Mac points = phone dp), like the Displays panel in macOS.
 * Drag the phone to any side of the Mac and slide it along; it snaps flush on release. The stretch where
 * the two touch is where the cursor crosses. The cursor glyph shows who has control right now.
 */
class DeskView(ctx: Context) : View(ctx) {
    /** Called with the new arrangement after the user drops the phone somewhere new. */
    var onArrange: ((Glide.Arrangement) -> Unit)? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(1.6f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = ctx.color(R.color.ink)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.color(R.color.surface) }
    private val shared = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val ghost = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(1.6f)
        color = ctx.color(R.color.accent)
        pathEffect = DashPathEffect(floatArrayOf(ctx.dp(4f), ctx.dp(4f)), 0f)
    }
    private val dashes = DashPathEffect(floatArrayOf(ctx.dp(4f), ctx.dp(5f)), 0f)
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.color(R.color.accent) }

    // World units are Mac points / phone dp. The Mac sits at (0,0).
    private var macW = 1512f
    private var macH = 982f
    private var phoneW = 412f
    private var phoneH = 915f
    private var arrangement = Glide.Arrangement(Proto.RIGHT, ((macH - phoneH) / 2).toInt())
    private val phone = RectF() // world
    private val snapTo = RectF() // world, while dragging

    // View transform: view = world * scale + origin.
    private var scale = 0.1f
    private var originX = 0f
    private var originY = 0f

    private var editable = false
    private var connected = false
    private var onPhone = false
    private var t = 0f // cursor: 0 on the Mac, 1 on the phone
    private var cursorAnim: ValueAnimator? = null
    private var settleAnim: ValueAnimator? = null
    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f

    init {
        isFocusable = true
        place(arrangement, phone)
    }

    fun render(s: Glide.State) {
        val b = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val d = resources.displayMetrics.density
        phoneW = b.width() / d
        phoneH = b.height() / d
        s.macSize?.let { (w, h) -> macW = w.toFloat(); macH = h.toFloat() }
        editable = s.macSize != null && GlideService.instance != null
        connected = s.phase == Glide.Phase.CONNECTED || s.phase == Glide.Phase.CONTROLLING
        if (!dragging && settleAnim?.isRunning != true) {
            arrangement = s.arrangement ?: arrangement
            place(arrangement, phone)
            fit()
        }
        val wasOnPhone = onPhone
        onPhone = s.phase == Glide.Phase.CONTROLLING
        contentDescription = describe()
        if (onPhone != wasOnPhone && isAttachedToWindow) {
            cursorAnim?.cancel()
            cursorAnim = ValueAnimator.ofFloat(t, if (onPhone) 1f else 0f).apply {
                duration = 650
                interpolator = Ui.settle
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            t = if (onPhone) 1f else 0f
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = fit()

    /** World rect of the phone for an arrangement. */
    private fun place(a: Glide.Arrangement, out: RectF) {
        val o = a.offset.toFloat()
        when (a.macEdge) {
            Proto.RIGHT -> out.set(macW, o, macW + phoneW, o + phoneH)
            Proto.LEFT -> out.set(-phoneW, o, 0f, o + phoneH)
            Proto.TOP -> out.set(o, -phoneH, o + phoneW, 0f)
            else -> out.set(o, macH, o + phoneW, macH + phoneH)
        }
    }

    /** Snap a free position to the nearest side, keeping at least [MIN_TOUCH] points of shared edge. */
    private fun snap(r: RectF): Glide.Arrangement {
        val nx = (r.centerX() - macW / 2) / (macW / 2 + phoneW / 2)
        val ny = (r.centerY() - macH / 2) / (macH / 2 + phoneH / 2)
        return if (kotlin.math.abs(nx) >= kotlin.math.abs(ny)) {
            val off = r.top.coerceIn(-phoneH + MIN_TOUCH, macH - MIN_TOUCH)
            Glide.Arrangement(if (nx > 0) Proto.RIGHT else Proto.LEFT, off.toInt())
        } else {
            val off = r.left.coerceIn(-phoneW + MIN_TOUCH, macW - MIN_TOUCH)
            Glide.Arrangement(if (ny > 0) Proto.BOTTOM else Proto.TOP, off.toInt())
        }
    }

    /** Scale and origin that frame the Mac and the phone at [r]. */
    private fun fitFor(r: RectF): FloatArray {
        val left = minOf(0f, r.left)
        val top = minOf(0f, r.top)
        val right = maxOf(macW, r.right)
        val bottom = maxOf(macH + BASE, r.bottom)
        val pad = context.dp(12f)
        val s = minOf((width - 2 * pad) / (right - left), (height - 2 * pad) / (bottom - top)).coerceAtLeast(0.001f)
        return floatArrayOf(s, (width - (right - left) * s) / 2 - left * s, (height - (bottom - top) * s) / 2 - top * s)
    }

    private fun fit() {
        if (width == 0) return
        val f = fitFor(phone)
        scale = f[0]; originX = f[1]; originY = f[2]
        invalidate()
    }

    private fun toView(r: RectF, out: RectF) =
        out.set(r.left * scale + originX, r.top * scale + originY, r.right * scale + originX, r.bottom * scale + originY)

    // ---- dragging ----

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editable) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val v = RectF().also { toView(phone, it) }
                v.inset(-context.dp(12f), -context.dp(12f)) // forgiving grab area
                if (!v.contains(e.x, e.y)) return false
                settleAnim?.cancel()
                dragging = true
                lastX = e.x; lastY = e.y
                parent?.requestDisallowInterceptTouchEvent(true) // keep the page from scrolling mid-drag
                place(snap(phone), snapTo)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                phone.offset((e.x - lastX) / scale, (e.y - lastY) / scale)
                lastX = e.x; lastY = e.y
                place(snap(phone), snapTo)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                drop(snap(phone))
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Glide the phone (and the framing) to the snapped spot, then report it. */
    private fun drop(a: Glide.Arrangement) {
        val from = RectF(phone)
        val to = RectF().also { place(a, it) }
        val s0 = scale; val x0 = originX; val y0 = originY
        val end = fitFor(to)
        settleAnim?.cancel()
        settleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = Ui.settle
            addUpdateListener {
                val f = it.animatedValue as Float
                phone.set(lerp(from.left, to.left, f), lerp(from.top, to.top, f), lerp(from.right, to.right, f), lerp(from.bottom, to.bottom, f))
                scale = lerp(s0, end[0], f); originX = lerp(x0, end[1], f); originY = lerp(y0, end[2], f)
                invalidate()
            }
            start()
        }
        if (a != arrangement) {
            arrangement = a
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            contentDescription = describe()
            onArrange?.invoke(a)
        }
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    // ---- drawing ----

    private val macV = RectF()
    private val phoneV = RectF()
    private val ghostV = RectF()
    private val macWorld = RectF()
    private val arrow = Path()

    override fun onDraw(c: Canvas) {
        macWorld.set(0f, 0f, macW, macH)
        toView(macWorld, macV)
        toView(phone, phoneV)
        if (!dragging) { // hairline between the two, as in the Displays panel
            val gap = context.dp(2f)
            when (arrangement.macEdge) {
                Proto.RIGHT -> phoneV.offset(gap, 0f)
                Proto.LEFT -> phoneV.offset(-gap, 0f)
                Proto.TOP -> phoneV.offset(0f, -gap)
                else -> phoneV.offset(0f, gap)
            }
        }

        val r = context.dp(5f)
        c.drawRoundRect(macV, r, r, fill)
        c.drawRoundRect(macV, r, r, line)
        if (arrangement.macEdge != Proto.BOTTOM || dragging) {
            val baseY = macV.bottom + context.dp(5f)
            c.drawLine(macV.left - context.dp(8f), baseY, macV.right + context.dp(8f), baseY, line)
        }

        if (dragging) {
            toView(snapTo, ghostV)
            c.drawRoundRect(ghostV, context.dp(8f), context.dp(8f), ghost)
        }

        val pr = context.dp(8f)
        c.drawRoundRect(phoneV, pr, pr, fill)
        c.drawRoundRect(phoneV, pr, pr, line)
        c.drawCircle(phoneV.centerX(), phoneV.top + context.dp(6f), context.dp(1.6f), line)

        // The stretch of the Mac's edge the cursor can cross: solid accent once connected.
        if (!dragging) {
            shared.color = context.color(if (connected) R.color.accent else R.color.muted)
            shared.pathEffect = if (connected) null else dashes
            when (arrangement.macEdge) {
                Proto.RIGHT, Proto.LEFT -> {
                    val x = if (arrangement.macEdge == Proto.RIGHT) macV.right else macV.left
                    c.drawLine(x, maxOf(macV.top, phoneV.top) + r, x, minOf(macV.bottom, phoneV.bottom) - r, shared)
                }
                else -> {
                    val y = if (arrangement.macEdge == Proto.TOP) macV.top else macV.bottom
                    c.drawLine(maxOf(macV.left, phoneV.left) + r, y, minOf(macV.right, phoneV.right) - r, y, shared)
                }
            }
        }

        val cx = macV.centerX() + (phoneV.centerX() - macV.centerX()) * t
        val cy = macV.centerY() + (phoneV.centerY() - macV.centerY()) * t
        val s = context.dp(15f)
        arrow.reset()
        arrow.moveTo(cx, cy)
        arrow.lineTo(cx, cy + s * 0.86f)
        arrow.lineTo(cx + s * 0.25f, cy + s * 0.64f)
        arrow.lineTo(cx + s * 0.42f, cy + s)
        arrow.lineTo(cx + s * 0.56f, cy + s * 0.94f)
        arrow.lineTo(cx + s * 0.39f, cy + s * 0.58f)
        arrow.lineTo(cx + s * 0.68f, cy + s * 0.58f)
        arrow.close()
        c.drawPath(arrow, cursorFill)
    }

    // ---- accessibility: the same arrangement without dragging ----

    private fun describe(): String {
        val side = when (arrangement.macEdge) {
            Proto.RIGHT -> "right of"
            Proto.LEFT -> "left of"
            Proto.TOP -> "above"
            else -> "below"
        }
        val who = if (onPhone) "Cursor is on this phone." else if (connected) "Cursor is on the Mac." else "Not connected."
        return "Phone is $side the Mac. $who"
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (!editable) return
        SIDES.forEach { (id, label, _) -> info.addAction(AccessibilityNodeInfo.AccessibilityAction(id, label)) }
    }

    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        val side = SIDES.firstOrNull { it.first == action } ?: return super.performAccessibilityAction(action, args)
        val centred = if (side.third == Proto.LEFT || side.third == Proto.RIGHT) (macH - phoneH) / 2 else (macW - phoneW) / 2
        drop(Glide.Arrangement(side.third, centred.toInt()))
        return true
    }

    companion object {
        /** Least shared edge, in points, so the cursor always has somewhere to cross. */
        private const val MIN_TOUCH = 80f
        /** Room under the Mac for its base line. */
        private const val BASE = 40f
        private val SIDES = listOf(
            Triple(R.id.arrange_right, "Put phone right of the Mac", Proto.RIGHT),
            Triple(R.id.arrange_left, "Put phone left of the Mac", Proto.LEFT),
            Triple(R.id.arrange_top, "Put phone above the Mac", Proto.TOP),
            Triple(R.id.arrange_bottom, "Put phone below the Mac", Proto.BOTTOM),
        )
    }
}

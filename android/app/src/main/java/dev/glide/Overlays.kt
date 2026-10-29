package dev.glide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.glide.Ui.color
import dev.glide.Ui.dp
import dev.glide.Ui.dpi
import dev.glide.Ui.pressable
import dev.glide.Ui.shape
import dev.glide.Ui.text

/**
 * Small untouchable window holding the cursor, centred on the hotspot. Moving it only repositions the
 * window in the compositor; nothing is redrawn. Position updates are capped at one per display frame.
 */
class CursorLayer(private val ctx: Context, private val wm: WindowManager) {
    private val size = ctx.dpi(64) // hotspot in the centre, room for the click ring and the file badge
    private val root = FrameLayout(ctx)
    private val arrow = Arrow(ctx)
    private val ring = Ring(ctx)
    private val badge = ctx.text(12f, 700, R.color.on_accent).apply {
        gravity = Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(ctx.color(R.color.accent))
        }
        visibility = View.GONE
    }
    private val params = WindowManager.LayoutParams(
        size, size, TYPE_ACCESSIBILITY_OVERLAY,
        FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS or
            FLAG_HARDWARE_ACCELERATED, // windows from a service are software-rendered unless asked
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
    }
    private val choreographer = Choreographer.getInstance()
    private var pending = false
    private val place = Choreographer.FrameCallback {
        pending = false
        if (root.isAttachedToWindow) wm.updateViewLayout(root, params)
    }

    init {
        val ringSize = ctx.dpi(40)
        root.addView(ring, FrameLayout.LayoutParams(ringSize, ringSize).apply {
            leftMargin = (size - ringSize) / 2; topMargin = (size - ringSize) / 2
        })
        root.addView(arrow, FrameLayout.LayoutParams(ctx.dpi(24), ctx.dpi(24)).apply {
            leftMargin = size / 2; topMargin = size / 2
        })
        root.addView(badge, FrameLayout.LayoutParams(ctx.dpi(20), ctx.dpi(20)).apply {
            leftMargin = size / 2 + ctx.dpi(12); topMargin = size / 2 + ctx.dpi(16)
        })
        ring.alpha = 0f
        root.visibility = View.GONE
        wm.addView(root, params)
    }

    /** Visible cursor, and the screen stays on while the Mac is driving. */
    fun show() {
        if (root.visibility == View.VISIBLE) return
        root.visibility = View.VISIBLE
        params.flags = params.flags or FLAG_KEEP_SCREEN_ON
        wm.updateViewLayout(root, params)
    }

    fun hide() {
        if (root.visibility != View.VISIBLE) return
        root.visibility = View.GONE
        params.flags = params.flags and FLAG_KEEP_SCREEN_ON.inv()
        wm.updateViewLayout(root, params)
    }

    fun moveTo(x: Float, y: Float) {
        params.x = x.toInt() - size / 2
        params.y = y.toInt() - size / 2
        if (!pending) {
            pending = true
            choreographer.postFrameCallback(place)
        }
    }

    /** Shows how many files ride on the cursor during a drag from the Mac; 0 hides it. */
    fun carry(count: Int) {
        badge.text = if (count > 99) "99+" else count.toString()
        badge.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    /** A ring that blooms from the hotspot on each click, so a tap you cannot feel is still seen. */
    fun click() {
        ring.animate().cancel()
        ring.scaleX = 0.35f; ring.scaleY = 0.35f; ring.alpha = 0.9f
        ring.animate().scaleX(1f).scaleY(1f).alpha(0f).setDuration(320).setInterpolator(Ui.settle).start()
    }

    fun remove() = wm.removeView(root)

    private class Arrow(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(ctx.dp(2.5f), 0f, ctx.dp(1f), 0x55000000)
        }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF15181C.toInt()
            style = Paint.Style.STROKE
            strokeWidth = ctx.dp(1.4f)
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            // Classic pointer, tip at (0,0) so the view origin is the hotspot.
            val s = w * 0.82f
            val o = edge.strokeWidth / 2
            path.reset()
            path.moveTo(o, o)
            path.lineTo(o, s * 0.86f)
            path.lineTo(s * 0.25f, s * 0.64f)
            path.lineTo(s * 0.42f, s)
            path.lineTo(s * 0.56f, s * 0.94f)
            path.lineTo(s * 0.39f, s * 0.58f)
            path.lineTo(s * 0.68f, s * 0.58f)
            path.close()
        }

        override fun onDraw(c: Canvas) {
            c.drawPath(path, fill)
            c.drawPath(path, edge)
        }
    }

    private class Ring(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ctx.color(R.color.accent)
            style = Paint.Style.STROKE
            strokeWidth = ctx.dp(2f)
        }

        override fun onDraw(c: Canvas) = c.drawCircle(width / 2f, height / 2f, width / 2f - p.strokeWidth, p)
    }
}

/** Card at the bottom of whatever is on screen, asking to trust a Mac. Shown by the service, so it works without the app open. */
class PairPrompt(private val ctx: Context, private val wm: WindowManager) {
    private var view: View? = null

    fun show(req: Glide.PairRequest) {
        dismiss()
        var answered = false
        fun answer(ok: Boolean) {
            if (answered) return
            answered = true
            req.answer(ok)
            dismiss()
        }

        val pad = ctx.dpi(24)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, ctx.dpi(16))
            background = ctx.shape(R.color.surface, 24f, R.color.line)
            elevation = ctx.dp(12f) // lifts above whatever app is underneath
            addView(ctx.text(20f, 600, R.color.ink, "Pair with ${req.host}?"))
            addView(ctx.text(44f, 600, R.color.ink, req.code).apply {
                fontFeatureSettings = "tnum"
                setPadding(0, ctx.dpi(12), 0, ctx.dpi(4))
            })
            addView(ctx.text(15f, 400, R.color.muted,
                "Your Mac shows a code too. Pair only if both are the same.").apply {
                setLineSpacing(0f, 1.3f)
            })
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.END
                setPadding(0, ctx.dpi(20), 0, 0)
                addView(button(R.color.surface, R.color.ink, "Don't pair") { answer(false) })
                addView(button(R.color.accent, R.color.on_accent, "Pair") { answer(true) }.apply {
                    (layoutParams as LinearLayout.LayoutParams).marginStart = ctx.dpi(8)
                })
            })
        }
        val frame = FrameLayout(ctx).apply {
            val m = ctx.dpi(12)
            addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(m, m, m, m) })
            // Accessibility overlays get no insets dispatched; read the gesture bar height from the display.
            val nav = wm.currentWindowMetrics.windowInsets
                .getInsets(android.view.WindowInsets.Type.navigationBars() or android.view.WindowInsets.Type.mandatorySystemGestures())
            setPadding(0, 0, 0, nav.bottom)
        }
        val params = WindowManager.LayoutParams(
            MATCH_PARENT, WRAP_CONTENT, TYPE_ACCESSIBILITY_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM } // not LAYOUT_IN_SCREEN: keeps the card above the navigation bar
        wm.addView(frame, params)
        view = frame
        card.translationY = ctx.dp(240f)
        card.alpha = 0f
        card.animate().translationY(0f).alpha(1f).setDuration(420).setInterpolator(Ui.settle).start()
        frame.postDelayed({ answer(false) }, PAIR_TIMEOUT_MS)
    }

    fun dismiss() {
        view?.let { wm.removeView(it) }
        view = null
    }

    private fun button(fill: Int, ink: Int, label: String, onClick: () -> Unit): TextView =
        ctx.text(16f, 600, ink, label).apply {
            background = ctx.shape(fill, 14f)
            setPadding(ctx.dpi(20), ctx.dpi(14), ctx.dpi(20), ctx.dpi(14))
            minHeight = ctx.dpi(48)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            pressable()
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }

    companion object {
        const val PAIR_TIMEOUT_MS = 60_000L
    }
}

/**
 * Card at the top of the screen while files arrive from the Mac, then "Saved" with Open for a single file.
 * Outgoing transfers are shown by ShareActivity instead.
 */
class TransferCard(private val ctx: Context, private val wm: WindowManager) {
    val observer: (Glide.State) -> Unit = ::render
    private var view: View? = null
    private val title = ctx.text(16f, 600, R.color.ink)
    private val detail = ctx.text(14f, 400, R.color.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
    private val bar = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = android.content.res.ColorStateList.valueOf(ctx.color(R.color.accent))
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(ctx.color(R.color.line))
    }
    private val open = ctx.text(15f, 600, R.color.accent, "Open").apply {
        gravity = Gravity.CENTER
        minHeight = ctx.dpi(48)
        setPadding(ctx.dpi(16), 0, ctx.dpi(16), 0)
        isClickable = true
    }
    private var current: Glide.Transfer? = null
    /** Put away for good: drop it from state too, or the next state change would bring it back. */
    private val hide = Runnable { done() }

    private fun done() {
        val t = current
        Glide.update { if (it.transfer == t) it.copy(transfer = null) else it }
        dismiss()
    }

    fun render(s: Glide.State) {
        val t = s.transfer
        if (t == null || !t.incoming) return dismiss()
        current = t
        val card = view ?: show()
        card.removeCallbacks(hide)
        val what = if (t.files > 1) "${t.files} files" else t.name
        when {
            t.failed != null -> {
                title.text = "Couldn't receive $what"
                detail.text = "${t.failed}. Nothing partial was kept."
            }
            t.finished -> {
                title.text = "Saved $what"
                detail.text = "Downloads › Glide"
            }
            else -> {
                title.text = "Receiving from ${t.peer}"
                detail.text = "${t.name}  ·  ${human(t.done)} of ${human(t.total)}"
            }
        }
        val pct = if (t.total > 0) (t.done * 1000 / t.total).toInt() else 0
        bar.progress = pct
        bar.visibility = if (t.finished || t.failed != null) View.GONE else View.VISIBLE
        open.visibility = if (t.finished && t.open != null) View.VISIBLE else View.GONE
        open.setOnClickListener {
            val uri = t.open ?: return@setOnClickListener
            runCatching {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW)
                    .setDataAndType(uri, Transfers.mimeOf(t.name))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            done()
        }
        if (t.finished || t.failed != null) card.postDelayed(hide, 6000)
    }

    private fun show(): View {
        val pad = ctx.dpi(16)
        val text = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(detail)
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, ctx.dpi(8)).apply { topMargin = ctx.dpi(8) })
        }
        val card = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(20), pad, ctx.dpi(8), pad)
            background = ctx.shape(R.color.surface, 24f, R.color.line)
            elevation = ctx.dp(8f)
            addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(open)
            setOnClickListener { done() } // tap anywhere else on the card to put it away
        }
        val frame = FrameLayout(ctx).apply {
            val m = ctx.dpi(12)
            addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(m, m, m, m) })
        }
        wm.addView(frame, WindowManager.LayoutParams(
            MATCH_PARENT, WRAP_CONTENT, TYPE_ACCESSIBILITY_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP })
        card.translationY = -ctx.dp(120f)
        card.animate().translationY(0f).setDuration(380).setInterpolator(Ui.settle).start()
        view = frame
        return frame
    }

    fun dismiss() {
        val v = view ?: return
        view = null
        v.removeCallbacks(hide)
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        runCatching { wm.removeView(v) }
        listOf(title, detail, bar, open).forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
    }

    companion object {
        fun human(b: Long): String = when {
            b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
            b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
            else -> "$b B"
        }
    }
}

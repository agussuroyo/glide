package dev.glide

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.glide.Ui.color
import dev.glide.Ui.dp
import dev.glide.Ui.dpi
import dev.glide.Ui.pressable
import dev.glide.Ui.shape
import dev.glide.Ui.text

/**
 * "Share → Glide" from any app. Files go to the connected Mac's Downloads; shared text goes to its clipboard.
 * Stays open until the Mac has the files: Android's permission to read shared files lasts while this screen exists.
 */
class ShareActivity : Activity() {
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var bar: ProgressBar
    private lateinit var action: TextView
    private var sending = false
    private val observer: (Glide.State) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        title = text(20f, 600, R.color.ink)
        detail = text(15f, 400, R.color.muted).apply { setLineSpacing(0f, 1.3f); setPadding(0, dpi(6), 0, 0) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.line))
            visibility = View.GONE
        }
        action = text(16f, 600, R.color.on_accent, "Close").apply {
            background = shape(R.color.accent, 14f)
            gravity = Gravity.CENTER
            minHeight = dpi(52)
            isClickable = true
            pressable()
            setOnClickListener { close() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(24), dpi(24), dpi(24), dpi(16))
            background = shape(R.color.surface, 24f, R.color.line)
            elevation = dp(12f)
            addView(title)
            addView(detail)
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, dpi(8)).apply { topMargin = dpi(16) })
            addView(action, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(20) })
        }
        setContentView(FrameLayout(this).apply {
            setOnClickListener { if (!sending) close() }
            val m = dpi(12)
            addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(m, m, m, m) })
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        })
        card.translationY = dp(300f)
        card.animate().translationY(0f).setDuration(380).setInterpolator(Ui.settle).start()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { close() }
        start(intent)
    }

    private fun start(intent: Intent) {
        val service = GlideService.instance
        val mac = service?.connectedHost
        if (service == null || mac == null) {
            title.text = "No Mac connected"
            detail.text = "Run glide on your Mac with this phone on the same WiFi, then share again."
            return
        }
        val uris = sharedUris(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        when {
            uris.isNotEmpty() -> {
                if (!service.offerToMac(uris)) return start(Intent()) // connection dropped just now
                sending = true
                Glide.update { it.copy(transfer = null) }
                Glide.observe(observer)
                title.text = "Sending to $mac"
                detail.text = if (uris.size == 1) "Waiting for the Mac…" else "${uris.size} files. Waiting for the Mac…"
                bar.visibility = View.VISIBLE
                action.text = "Hide"
            }
            !text.isNullOrEmpty() && text.toByteArray().size <= Proto.MAX_CLIPBOARD -> {
                service.copyToMac(text)
                title.text = "Copied to $mac"
                detail.text = "Paste it on the Mac with Cmd V."
                action.postDelayed({ close() }, 1400)
            }
            else -> {
                title.text = "Nothing to send"
                detail.text = "Glide sends files, photos, videos and text."
            }
        }
    }

    private fun render(s: Glide.State) {
        val t = s.transfer ?: return
        if (t.incoming) return
        bar.progress = if (t.total > 0) (t.done * 1000 / t.total).toInt() else 0
        when {
            t.failed != null -> {
                sending = false
                title.text = "Couldn't send"
                detail.text = "${t.failed}. Try again when the Mac is connected."
                bar.visibility = View.GONE
                action.text = "Close"
            }
            t.finished -> {
                sending = false
                title.text = "Sent to ${t.peer}"
                detail.text = "In Downloads on the Mac."
                bar.visibility = View.GONE
                action.text = "Done"
                action.postDelayed({ close() }, 1600)
            }
            else -> detail.text = "${t.name}  ·  ${TransferCard.human(t.done)} of ${TransferCard.human(t.total)}"
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }

    /** While sending, leaving only hides the sheet: finishing would end the right to read the shared files. */
    private fun close() {
        if (sending) moveTaskToBack(true) else finish()
    }

    override fun onDestroy() {
        Glide.stopObserving(observer)
        super.onDestroy()
    }
}

package dev.glide

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import dev.glide.Ui.color
import dev.glide.Ui.dp
import dev.glide.Ui.dpi
import dev.glide.Ui.pressable
import dev.glide.Ui.shape
import dev.glide.Ui.text
import java.net.Inet4Address

/** Setup and status. Everything here reflects real state; sections appear only when they have a job. */
class MainActivity : Activity() {
    private lateinit var desk: DeskView
    private lateinit var headline: TextView
    private lateinit var detail: TextView
    private lateinit var setup: LinearLayout
    private lateinit var command: TextView
    private lateinit var fallback: TextView
    private lateinit var arrangeHint: TextView
    private lateinit var pairedList: LinearLayout
    private lateinit var scanButton: TextView
    private lateinit var scanNote: TextView
    private val observer: (Glide.State) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        val gutter = dpi(24)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gutter, 0, gutter, dpi(40))
        }

        desk = DeskView(this).apply { onArrange = { GlideService.instance?.arrange(it) } }
        column.addView(desk, LinearLayout.LayoutParams(MATCH_PARENT, dpi(200)).apply { topMargin = dpi(16) })
        arrangeHint = text(13f, 400, R.color.muted).apply { gravity = Gravity.CENTER }
        column.addView(arrangeHint)

        headline = text(30f, 600, R.color.ink).apply { setPadding(0, dpi(12), 0, 0) }
        detail = text(16f, 400, R.color.muted).apply {
            setLineSpacing(0f, 1.3f)
            setPadding(0, dpi(6), 0, 0)
        }
        column.addView(headline)
        column.addView(detail)

        setup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(20), dpi(20), dpi(12))
            background = shape(R.color.surface, 24f, R.color.line)
            addView(text(18f, 600, R.color.ink, "Turn on Glide"))
            addView(body("Glide needs Accessibility access to show a cursor, tap and type on this phone. " +
                "It only acts on input from a Mac you have paired."))
            addView(primaryButton("Open Accessibility settings") { openAccessibility() }.withTopMargin(16))
            addView(body("Switch greyed out? Android limits apps installed from a file. Open App info, " +
                "tap ⋮ in the corner, choose Allow restricted settings, then try again.").withTopMargin(16))
            addView(quietButton("Open App info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = -dpi(14) })
        }
        column.addView(setup, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(28) })

        column.addView(sectionTitle("On your Mac"))
        column.addView(body("Install Glide.app on the Mac; it runs in the menu bar. Prefer Terminal? Run this, with the phone on the same WiFi:"))
        command = text(16f, 400, R.color.ink).apply {
            typeface = Typeface.MONOSPACE
            fontVariationSettings = null
            gravity = Gravity.CENTER_VERTICAL
        }
        val copy = quietButton("Copy") {}
        copy.setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Glide command", command.text))
            copy.text = "Copied"
            copy.postDelayed({ copy.text = "Copy" }, 1600)
        }
        column.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16), dpi(4), dpi(4), dpi(4))
            background = shape(R.color.surface, 14f, R.color.line)
            addView(command, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(copy)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(12) })
        fallback = body("")
        column.addView(fallback)

        column.addView(sectionTitle("Moving files"))
        column.addView(body("From the Mac: drag files past the screen edge and let go on this phone, or copy them " +
            "in Finder and press Cmd V here. They arrive in Downloads › Glide, folders included."))
        column.addView(body("To the Mac: tap Share in any app and choose Glide. Files land in the Mac's Downloads; " +
            "shared text goes to its clipboard."))
        column.addView(body("Text copied on the Mac is ready to paste here. Cmd C in a text field here copies to the Mac."))

        column.addView(sectionTitle("Paired Macs"))
        pairedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(pairedList)
        column.addView(body("To add a Mac: click Glide in its menu bar, choose Pair New Phone, then scan the code."))
        scanButton = primaryButton("Scan pairing code") { scan() }
        column.addView(scanButton.withTopMargin(12))
        scanNote = body("").apply { visibility = View.GONE }
        column.addView(scanNote)

        column.addView(sectionTitle("Feel"))
        column.addView(slider("Pointer speed", Store.pointerSpeed) { Store.pointerSpeed = it })
        column.addView(slider("Scroll speed", Store.scrollSpeed) { Store.scrollSpeed = it })

        column.addView(sectionTitle("Shortcuts from your Mac"))
        listOf(
            "Esc or right click" to "Back",
            "Cmd H" to "Home",
            "Cmd Tab" to "Recent apps",
            "Cmd N" to "Notifications",
            "Ctrl Cmd Q" to "Lock screen",
            "Cmd C, X, V, A" to "Copy, cut, paste, select all",
            "Option + arrows or delete" to "Move or delete by word",
            "Left Ctrl Option Cmd" to "Take the cursor back on the Mac",
        ).forEach { (keys, action) -> column.addView(shortcut(keys, action)) }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
    }

    override fun onStart() {
        super.onStart()
        Glide.observe(observer)
    }

    override fun onStop() {
        Glide.stopObserving(observer)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        render(Glide.state) // IP may have changed while away
    }

    private fun render(s: Glide.State) {
        desk.render(s)
        val macEdge = edgeName(opposite(s.hostEdge))
        val phoneEdge = edgeName(s.hostEdge)
        when (s.phase) {
            Glide.Phase.OFF -> {
                headline.text = "Glide is off"
                detail.text = "Turn it on below so your Mac can reach this phone."
            }
            Glide.Phase.WAITING -> {
                val scanned = Store.scanned
                when {
                    s.pairing != null -> {
                        headline.text = "Check the code"
                        detail.text = "${s.pairing.host} wants to pair. Compare the code on both screens."
                    }
                    scanned != null -> {
                        headline.text = "Waiting for ${scanned.name}"
                        detail.text = "Keep Glide open on the Mac and both on the same WiFi. It connects in a few seconds."
                    }
                    Store.paired().isEmpty() -> {
                        headline.text = "Pair your Mac"
                        detail.text = "On the Mac, click Glide in the menu bar and choose Pair New Phone. Then tap Scan pairing code below."
                    }
                    else -> {
                        headline.text = "Waiting for your Mac"
                        detail.text = "Open Glide on your Mac, on the same WiFi."
                    }
                }
            }
            Glide.Phase.CONNECTED -> {
                headline.text = "Connected to ${s.host}"
                detail.text = "Push the Mac's cursor past its $macEdge edge to bring it here."
            }
            Glide.Phase.CONTROLLING -> {
                headline.text = "${s.host} has the cursor"
                detail.text = "Move past this phone's $phoneEdge edge to hand it back."
            }
        }
        setup.visibility = if (s.phase == Glide.Phase.OFF) View.VISIBLE else View.GONE

        command.text = "glide"
        val ip = wifiAddress()
        fallback.text = if (ip != null) "If the Mac can't find this phone: glide $ip" else "Connect this phone to WiFi."
        arrangeHint.text = if (s.macSize != null) "Drag the phone to where it sits next to your Mac."
        else "Connect your Mac to arrange the two screens."

        pairedList.removeAllViews()
        val paired = Store.paired()
        if (paired.isEmpty()) {
            pairedList.addView(body("None yet."))
        }
        paired.entries.sortedBy { it.value.lowercase() }.forEach { (fp, name) ->
            pairedList.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dpi(52)
                addView(text(16f, 500, R.color.ink, name), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(quietButton("Forget") {
                    Store.forget(fp)
                    GlideService.instance?.disconnect(fp)
                    render(Glide.state)
                }.apply { contentDescription = "Forget $name" })
            })
        }
    }

    // ---- building blocks ----

    private fun sectionTitle(t: String) = text(18f, 600, R.color.ink, t).apply {
        setPadding(0, dpi(36), 0, 0)
        isAccessibilityHeading = true
    }

    private fun body(t: String) = text(15f, 400, R.color.muted, t).apply {
        setLineSpacing(0f, 1.3f)
        setPadding(0, dpi(8), 0, 0)
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = text(16f, 600, R.color.on_accent, label).apply {
        background = shape(R.color.accent, 14f)
        gravity = Gravity.CENTER
        minHeight = dpi(52)
        setPadding(dpi(20), 0, dpi(20), 0)
        isClickable = true
        isFocusable = true
        pressable()
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun quietButton(label: String, onClick: () -> Unit) = text(15f, 600, R.color.accent, label).apply {
        gravity = Gravity.CENTER
        minHeight = dpi(48)
        minWidth = dpi(48)
        setPadding(dpi(14), 0, dpi(14), 0)
        isClickable = true
        isFocusable = true
        background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(color(R.color.line)), null, shape(R.color.surface, 14f),
        )
        setOnClickListener { onClick() }
    }

    private fun slider(label: String, value: Float, onChange: (Float) -> Unit): View {
        // 0.25× … 3×, in 0.05 steps.
        val readout = text(15f, 500, R.color.muted).apply { fontFeatureSettings = "tnum" }
        fun show(v: Float) { readout.text = "%.2f×".format(v) }
        show(value)
        val bar = SeekBar(this).apply {
            max = 55
            progress = ((value - 0.25f) / 0.05f).toInt().coerceIn(0, 55)
            progressTintList = ColorStateList.valueOf(color(R.color.accent))
            thumbTintList = ColorStateList.valueOf(color(R.color.accent))
            progressBackgroundTintList = ColorStateList.valueOf(color(R.color.line))
            contentDescription = label
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = 0.25f + p * 0.05f
                    show(v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpi(14), 0, 0)
            addView(LinearLayout(this@MainActivity).apply {
                addView(text(16f, 500, R.color.ink, label), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(readout)
            })
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, dpi(40)).apply {
                marginStart = -dpi(16); marginEnd = -dpi(16) // SeekBar pads its thumb; align the track with text
            })
        }
    }

    private fun shortcut(keys: String, action: String) = LinearLayout(this).apply {
        setPadding(0, dpi(12), 0, 0)
        addView(text(15f, 600, R.color.ink, keys), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(text(15f, 400, R.color.muted, action).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun View.withTopMargin(dp: Int) = apply {
        layoutParams = (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            .apply { topMargin = dpi(dp) }
    }

    // ---- QR pairing ----

    private fun scan() {
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, options)
        // The scanner is a small Play services module downloaded on first use; fetch it, then scan.
        val modules = com.google.android.gms.common.moduleinstall.ModuleInstall.getClient(this)
        val request = com.google.android.gms.common.moduleinstall.ModuleInstallRequest.newBuilder()
            .addApi(scanner)
            .build()
        showScanNote("Getting the scanner ready…")
        modules.installModules(request)
            .addOnSuccessListener { result ->
                if (result.areModulesAlreadyInstalled()) startScan(scanner) else waitForScanner(scanner)
            }
            .addOnFailureListener { scannerUnavailable() }
    }

    /** Polls until the freshly downloaded scanner module is usable (typically a few seconds). */
    private fun waitForScanner(scanner: com.google.mlkit.vision.codescanner.GmsBarcodeScanner, tries: Int = 0) {
        com.google.android.gms.common.moduleinstall.ModuleInstall.getClient(this).areModulesAvailable(scanner)
            .addOnSuccessListener { r ->
                when {
                    r.areModulesAvailable() -> startScan(scanner)
                    tries < 60 -> scanNote.postDelayed({ waitForScanner(scanner, tries + 1) }, 1000)
                    else -> scannerUnavailable()
                }
            }
            .addOnFailureListener { scannerUnavailable() }
    }

    private fun startScan(scanner: com.google.mlkit.vision.codescanner.GmsBarcodeScanner) {
        showScanNote("")
        scanner.startScan()
            .addOnSuccessListener { code ->
                val scanned = Pairing.parseQr(code.rawValue.orEmpty())
                if (scanned == null) {
                    showScanNote("That isn't a Glide pairing code. On the Mac, choose Pair New Phone in the Glide menu.")
                } else {
                    Store.scanned = scanned
                    GlideService.instance?.refreshDiscovery()
                    render(Glide.state)
                }
            }
            .addOnFailureListener { scannerUnavailable() }
    }

    private fun scannerUnavailable() = showScanNote(
        "The scanner couldn't start (it needs Google Play services and internet the first time). " +
            "Pair by code instead: open Glide on the Mac, choose Pair New Phone, and compare the 6-digit code on both screens.",
    )

    private fun showScanNote(text: String) {
        scanNote.text = text
        scanNote.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---- helpers ----

    private fun openAccessibility() {
        // Straight to Glide's switch where the phone supports it, otherwise the Accessibility list.
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(this, GlideService::class.java).flattenToString())
        runCatching { startActivity(details) }.onFailure { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun wifiAddress(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
    }

    private fun edgeName(edge: Int) = when (edge) {
        Proto.LEFT -> "left"
        Proto.RIGHT -> "right"
        Proto.TOP -> "top"
        else -> "bottom"
    }
}

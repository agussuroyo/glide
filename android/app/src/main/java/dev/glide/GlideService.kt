package dev.glide

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Target side: accepts one paired Mac over TLS, shows its cursor and turns its input into touches and typing. */
class GlideService : AccessibilityService() {
    companion object {
        private const val TAG = "GlideCursor"
        private const val SCROLL_RELEASE_MS = 120L
        private const val WAKE_CHECK_MS = 350L
        private const val AWAY_MS = 10 * 60_000L

        /** Set while the service runs, so the setup screen can drop a host it just forgot. */
        @Volatile var instance: GlideService? = null
            private set
    }

    /** One connected Mac. Writes come from the ping thread and the main thread, so they are serialized. */
    private inner class Session(val socket: SSLSocket, val fingerprint: String, private val out: OutputStream) {
        var ping: ScheduledFuture<*>? = null

        fun send(m: Proto.Msg) {
            runCatching {
                io.execute {
                    try {
                        synchronized(out) { Proto.write(out, m) }
                    } catch (e: Exception) {
                        close()
                    }
                }
            }
        }

        /** Safe from any thread: TLS close writes to the network, so it never runs on the main thread. */
        fun close() {
            ping?.cancel(false)
            Thread { runCatching { socket.close() } }.start()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    // Recreated on every bind: Android unbinds and rebinds accessibility services when settings change.
    private var io = Executors.newSingleThreadScheduledExecutor()
    private lateinit var wm: WindowManager
    private lateinit var cursor: CursorLayer
    private lateinit var prompt: PairPrompt
    private lateinit var finger: Finger
    private lateinit var keyboard: Keyboard
    private lateinit var power: PowerManager
    private lateinit var transfers: Transfers
    private lateinit var transferCard: TransferCard
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    @Volatile private var server: SSLServerSocket? = null
    @Volatile private var session: Session? = null

    // Main-thread state.
    private var width = 0
    private var height = 0
    private var x = 0f
    private var y = 0f
    private var entryEdge = Proto.LEFT
    private var active = false
    private var scrolling = false
    private var scrollX = 0f
    private var scrollY = 0f
    private var draggingFiles = false

    /** The Mac announced files on its clipboard (Finder copy) rather than text. */
    var macClipboardHasFiles = false
        private set

    // Input from the network thread, merged so a burst of mouse moves costs one cursor update per drain.
    private val inbox = ArrayDeque<Proto.Msg>()
    private var drainPosted = false

    override fun onServiceConnected() {
        Store.init(this)
        if (io.isShutdown) io = Executors.newSingleThreadScheduledExecutor()
        instance = this
        wm = getSystemService(WindowManager::class.java)
        power = getSystemService(PowerManager::class.java)
        cursor = CursorLayer(this, wm)
        prompt = PairPrompt(this, wm)
        finger = Finger(this, main)
        keyboard = Keyboard(this)
        transfers = Transfers(this)
        transferCard = TransferCard(this, wm)
        Glide.observe(transferCard.observer)
        Glide.update { it.copy(phase = Glide.Phase.WAITING, host = null, arrangement = Store.lastArrangement) }
        Thread(::serve, "glide-server").start()
        GlideTile.update(this)
        main.postDelayed(awayOff, AWAY_MS) // nobody connects: don't stay on
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance == null) return
        instance = null
        unregisterNsd()
        Glide.state.pairing?.answer?.invoke(false) // a pending pairing must not hang until its timeout
        server?.let { ss -> Thread { runCatching { ss.close() } }.start() }
        session?.close()
        io.shutdownNow()
        prompt.dismiss()
        Glide.stopObserving(transferCard.observer)
        transferCard.dismiss()
        cursor.remove()
        main.removeCallbacks(awayOff)
        Glide.update { it.copy(phase = Glide.Phase.OFF, host = null, pairing = null) }
        GlideTile.update(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        Guard.protectedAppFor(event)?.let { app ->
            Store.bankMaybeOpen = true // it may stay in Recent apps after this
            return stepAside("$app was opening", "Glide turned off so $app can open safely")
        }
        // A Recents card tap carries no label, so Glide can't tell which app it is. While a banking app may
        // still be in Recents, any unlabeled tap in the launcher turns Glide off first.
        if (Store.bankMaybeOpen && Guard.isUnlabeledLauncherTap(event, homePackage)) {
            stepAside("an app was opened from Recent apps", "Glide turned off: Livin' may still be in Recent apps")
        }
    }

    private val homePackage: String? by lazy {
        packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0,
        )?.activityInfo?.packageName
    }

    /**
     * Turns the Accessibility service off right now (the user turns it back on). Order matters: disable
     * first, explain after, so a banking app starting up sees Glide already gone.
     */
    private fun stepAside(reason: String, toast: String) {
        if (instance == null) return
        Store.lastAutoOff = "${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())}: $reason"
        disableSelf()
        android.widget.Toast.makeText(this, toast, android.widget.Toast.LENGTH_LONG).show()
    }

    private val awayOff = Runnable {
        if (session == null && Store.offWhenAway) stepAside("no Mac for 10 minutes", "Glide turned off: no Mac nearby")
    }
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation changes how much of the Mac's edge the phone covers.
        Glide.state.arrangement?.let { a -> session?.send(layoutMsg(a)) }
        if (!active) return
        // Rotated: keep the cursor at the same relative spot on the new screen.
        val fx = x / width
        val fy = y / height
        readScreen()
        x = (fx * width).coerceIn(0f, width - 1f)
        y = (fy * height).coerceIn(0f, height - 1f)
        cursor.moveTo(x, y)
    }

    val connectedHost: String? get() = if (session != null) Glide.state.host else null

    fun copyToMac(text: String) {
        session?.send(Proto.Clipboard(text))
    }

    fun pasteFilesFromMac() {
        session?.send(Proto.PasteFiles)
    }

    /** From the share sheet. Returns false when no Mac is connected. */
    fun offerToMac(uris: List<android.net.Uri>): Boolean {
        val sess = session ?: return false
        sess.send(transfers.addOffer(uris))
        return true
    }

    /** From the arrangement screen: the user dragged the phone to a new spot beside the Mac. */
    fun arrange(a: Glide.Arrangement) {
        val sess = session ?: return
        Store.setArrangement(sess.fingerprint, a)
        Store.lastArrangement = a
        sess.send(layoutMsg(a))
        Glide.update { it.copy(arrangement = a) }
    }

    /** Phone size in dp; one dp is treated as one Mac point, so the two screens line up like displays. */
    fun screenDp(): Pair<Int, Int> {
        val b = wm.currentWindowMetrics.bounds
        val d = resources.displayMetrics.density
        return (b.width() / d).toInt() to (b.height() / d).toInt()
    }

    private fun layoutMsg(a: Glide.Arrangement): Proto.Layout {
        val (w, h) = screenDp()
        val length = if (a.macEdge == Proto.LEFT || a.macEdge == Proto.RIGHT) h else w
        return Proto.Layout(a.macEdge, a.offset, length)
    }

    /** First time with a Mac: to its right, centred. */
    private fun defaultArrangement(mac: Pair<Int, Int>) =
        Glide.Arrangement(Proto.RIGHT, (mac.second - screenDp().second) / 2)

    /** Called from the setup screen after the user forgets a Mac. */
    fun disconnect(fingerprint: String) {
        session?.takeIf { it.fingerprint == fingerprint }?.close()
    }

    // ---- network (background threads) ----

    private fun serve() {
        try {
            val ss = Pairing.serverSocketFactory().createServerSocket() as SSLServerSocket
            ss.reuseAddress = true
            // After a rebind the previous server may still be closing; give it a moment.
            for (attempt in 1..20) {
                try {
                    ss.bind(InetSocketAddress(Proto.PORT)); break
                } catch (e: java.net.BindException) {
                    if (attempt == 20 || instance !== this) throw e
                    Thread.sleep(250)
                }
            }
            ss.needClientAuth = true
            ss.enabledProtocols = arrayOf("TLSv1.3")
            server = ss
            main.post(::registerNsd)
            while (true) {
                val s = ss.accept() as SSLSocket
                Thread({ runSession(s) }, "glide-session").start()
            }
        } catch (e: Exception) {
            Log.i(TAG, "server stopped: $e")
        }
    }

    private fun runSession(s: SSLSocket) {
        var me: Session? = null
        try {
            s.tcpNoDelay = true
            s.soTimeout = (PairPrompt.PAIR_TIMEOUT_MS + 30_000).toInt() // room for a human to read the code
            s.startHandshake()
            val hostCert = s.session.peerCertificates.first().encoded
            val fp = Pairing.fingerprint(hostCert)
            val input = DataInputStream(BufferedInputStream(s.inputStream))
            val out = BufferedOutputStream(s.outputStream)
            var first = Proto.read(input)
            val macDoesNotKnowUs = first is Proto.PairStart
            if (macDoesNotKnowUs) first = Proto.read(input)
            if (first !is Proto.Hello) {
                // A transfer connection: only from a Mac that is already paired, never a pairing prompt.
                val peer = Store.paired()[fp] ?: return
                val tOut = BufferedOutputStream(s.outputStream, 256 * 1024)
                when (first) {
                    is Proto.Push -> transfers.receive(s, input, tOut, first, peer)
                    is Proto.Pull -> transfers.send(s, input, tOut, first, peer)
                    else -> {}
                }
                return
            }
            val hello = first
            val (w, h) = screenDp()
            val ourHello = Proto.Hello(Proto.VERSION, w, h, deviceName())
            if (hello.version != Proto.VERSION) {
                Proto.write(out, ourHello) // lets the Mac print a clear version message
                return
            }
            val scanned = Store.scanned?.takeIf { it.fingerprint == fp }
            when {
                // We scanned this Mac's QR: prove it, no questions asked on either side.
                scanned != null -> {
                    Proto.write(out, Proto.PairProof(Pairing.proof(scanned.secret, hostCert, Pairing.certificate.encoded)))
                    Store.scanned = null
                    main.post(::refreshDiscovery)
                }
                fp in Store.paired() && !macDoesNotKnowUs -> {}
                // Unknown Mac, or one that forgot us: compare codes on both screens.
                else -> {
                    Proto.write(out, Proto.PairAsk)
                    if (!askToPair(hello.name, Pairing.sas(hostCert, Pairing.certificate.encoded), s, input)) return
                }
            }
            Store.pair(fp, hello.name) // also refreshes the name if the Mac was renamed

            val sess = Session(s, fp, out)
            me = sess
            session?.close() // newest Mac wins
            session = sess
            main.removeCallbacks(awayOff)
            Proto.write(out, ourHello)
            val mac = hello.width to hello.height
            val arrangement = Store.arrangement(fp) ?: defaultArrangement(mac)
            Proto.write(out, layoutMsg(arrangement))
            Store.lastArrangement = arrangement
            s.soTimeout = Proto.TIMEOUT_MS
            sess.ping = io.scheduleWithFixedDelay({ sess.send(Proto.Ping) }, Proto.PING_MS, Proto.PING_MS, TimeUnit.MILLISECONDS)
            Glide.update { it.copy(phase = Glide.Phase.CONNECTED, host = hello.name, macSize = mac, arrangement = arrangement) }
            Log.i(TAG, "connected: ${hello.name}")
            while (true) enqueue(Proto.read(input))
        } catch (e: Exception) {
            Log.i(TAG, "session ended: $e")
        } finally {
            me?.close() ?: runCatching { s.close() }
            if (me != null && session === me) {
                session = null
                main.post {
                    main.removeCallbacks(awayOff)
                    main.postDelayed(awayOff, AWAY_MS)
                    deactivate()
                    Glide.update { it.copy(phase = Glide.Phase.WAITING, host = null, macSize = null) }
                }
            }
        }
    }

    /**
     * Blocks this session thread until the user answers the on-screen card, it times out, or the Mac goes
     * away (then the card disappears instead of waiting out its timeout). The Mac sends nothing while it
     * waits for its own user, so a read here only ever sees end-of-stream.
     */
    private fun askToPair(host: String, code: String, s: SSLSocket, input: DataInputStream): Boolean {
        val done = CountDownLatch(1)
        var ok = false
        val req = Glide.PairRequest(host, code) { answer -> ok = answer; done.countDown() }
        Glide.update { it.copy(pairing = req) }
        main.post { prompt.show(req) }
        val previous = s.soTimeout
        s.soTimeout = 500
        val deadline = System.currentTimeMillis() + PairPrompt.PAIR_TIMEOUT_MS + 1000
        try {
            while (!done.await(0, TimeUnit.MILLISECONDS) && System.currentTimeMillis() < deadline) {
                input.mark(1)
                try {
                    if (input.read() < 0) break // Mac closed the connection
                    input.reset()
                } catch (_: java.net.SocketTimeoutException) {
                    // still connected; keep waiting
                }
            }
        } catch (_: java.io.IOException) {
            // connection broke: treat as not paired
        } finally {
            s.soTimeout = previous
        }
        main.post { prompt.dismiss() }
        Glide.update { if (it.pairing === req) it.copy(pairing = null) else it }
        return ok && done.count == 0L
    }

    private fun enqueue(m: Proto.Msg) {
        if (m is Proto.Ping) return
        synchronized(inbox) {
            val last = inbox.lastOrNull()
            when {
                m is Proto.Move && last is Proto.Move -> inbox[inbox.lastIndex] = Proto.Move(last.dx + m.dx, last.dy + m.dy)
                m is Proto.Scroll && last is Proto.Scroll -> inbox[inbox.lastIndex] = Proto.Scroll(last.dx + m.dx, last.dy + m.dy)
                else -> inbox.addLast(m)
            }
            if (!drainPosted) {
                drainPosted = true
                main.post(::drain)
            }
        }
    }

    // ---- input (main thread) ----

    private fun drain() {
        val batch = synchronized(inbox) {
            drainPosted = false
            inbox.toList().also { inbox.clear() }
        }
        batch.forEach(::handle)
    }

    private fun handle(m: Proto.Msg) {
        when (m) {
            is Proto.Enter -> enter(m.edge, m.pos / 65535f)
            is Proto.Clipboard -> {
                macClipboardHasFiles = false
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Mac clipboard", m.text))
            }
            is Proto.ClipFiles -> macClipboardHasFiles = true
            is Proto.DragFiles -> { draggingFiles = true; cursor.carry(m.count) }
            is Proto.Leave -> if (active) { // the Mac took control back (Ctrl Option Cmd)
                deactivate()
                Glide.update { if (it.phase == Glide.Phase.CONTROLLING) it.copy(phase = Glide.Phase.CONNECTED) else it }
            }
            is Proto.Move -> if (active) move(m.dx, m.dy)
            is Proto.Button -> if (active) button(m.button, m.down)
            is Proto.Scroll -> if (active) scroll(m.dx, m.dy)
            is Proto.Key -> if (active) keyboard.key(m.usage, m.down)
            else -> {}
        }
    }

    private fun enter(edge: Int, frac: Float) {
        if (power.isInteractive) return takeControl(edge, frac)
        // Screen is off: try to wake it; if it stays dark, hand the cursor straight back instead of losing it.
        @Suppress("DEPRECATION")
        power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "glide:enter")
            .acquire(WAKE_CHECK_MS * 3)
        main.postDelayed({
            if (power.isInteractive) takeControl(edge, frac)
            else session?.send(Proto.Leave(edge, (frac * 65535).toInt()))
        }, WAKE_CHECK_MS)
    }

    private fun takeControl(edge: Int, frac: Float) {
        readScreen()
        entryEdge = edge
        when (edge) {
            Proto.LEFT -> { x = 0f; y = frac * height }
            Proto.RIGHT -> { x = width - 1f; y = frac * height }
            Proto.TOP -> { x = frac * width; y = 0f }
            else -> { x = frac * width; y = height - 1f }
        }
        active = true
        cursor.moveTo(x, y)
        cursor.show()
        Glide.update { it.copy(phase = Glide.Phase.CONTROLLING) }
    }

    private fun readScreen() {
        val b = wm.currentWindowMetrics.bounds
        width = b.width()
        height = b.height()
    }

    private fun move(dx: Int, dy: Int) {
        val k = Store.pointerSpeed * resources.displayMetrics.density
        val nx = x + dx * k
        val ny = y + dy * k
        val leaving = when (entryEdge) {
            Proto.LEFT -> nx < 0
            Proto.RIGHT -> nx > width - 1
            Proto.TOP -> ny < 0
            else -> ny > height - 1
        }
        // Never hand back mid-press: the Mac would get a button that is still down on the phone.
        if (leaving && (!finger.isDown || scrolling) && !draggingFiles) {
            val along = if (entryEdge == Proto.LEFT || entryEdge == Proto.RIGHT) y / height else x / width
            session?.send(Proto.Leave(entryEdge, (along.coerceIn(0f, 1f) * 65535).toInt()))
            deactivate()
            Glide.update { if (it.phase == Glide.Phase.CONTROLLING) it.copy(phase = Glide.Phase.CONNECTED) else it }
            return
        }
        x = nx.coerceIn(0f, width - 1f)
        y = ny.coerceIn(0f, height - 1f)
        cursor.moveTo(x, y)
        if (finger.isDown && !scrolling) finger.moveTo(x, y)
    }

    private fun button(button: Int, down: Boolean) {
        when (button) {
            0 -> if (draggingFiles) {
                if (!down) { draggingFiles = false; cursor.carry(0); cursor.click() } // dropped; the Mac sends the files
            } else if (down) {
                endScroll()
                finger.down(x, y)
                cursor.click()
            } else {
                finger.up()
            }
            1 -> if (down) { cursor.click(); performGlobalAction(GLOBAL_ACTION_BACK) }
            2 -> if (down) performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    // Wheel and trackpad scrolling drive a finger that drags from the cursor and lifts shortly after the last
    // event, so lists move continuously and fling naturally. The Mac's own scroll direction setting carries over.
    private fun scroll(dx: Int, dy: Int) {
        if (finger.isDown && !scrolling) return // dragging with the button held
        val k = Store.scrollSpeed * resources.displayMetrics.density
        if (!scrolling) {
            scrolling = true
            scrollX = x; scrollY = y
            finger.down(x, y)
        }
        val margin = resources.displayMetrics.density * 8
        val nx = scrollX + dx * k
        val ny = scrollY + dy * k
        if (nx < margin || ny < margin || nx > width - margin || ny > height - margin) {
            endScroll() // ran out of screen: lift and start again from the cursor on the next event
            return
        }
        scrollX = nx; scrollY = ny
        finger.moveTo(nx, ny)
        main.removeCallbacks(endScrollRunnable)
        main.postDelayed(endScrollRunnable, SCROLL_RELEASE_MS)
    }

    private val endScrollRunnable = Runnable { endScroll() }

    private fun endScroll() {
        if (!scrolling) return
        scrolling = false
        main.removeCallbacks(endScrollRunnable)
        finger.up()
    }

    private fun deactivate() {
        draggingFiles = false
        if (::cursor.isInitialized) cursor.carry(0)
        endScroll()
        finger.up()
        keyboard.reset()
        active = false
        if (::cursor.isInitialized) cursor.hide()
    }

    // ---- discovery ----

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    private fun registerNsd() {
        if (instance == null) return
        val info = NsdServiceInfo().apply {
            serviceName = deviceName()
            serviceType = Proto.SERVICE_TYPE
            port = Proto.PORT
            setAttribute("id", Pairing.fingerprint.take(16))
            // Lets the Mac whose QR we scanned pick this phone out of others on the WiFi.
            Store.scanned?.let { setAttribute("pair", it.fingerprint.take(16)) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.i(TAG, "discoverable as ${info.serviceName}") }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.w(TAG, "mDNS register failed: $code") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        nsd = getSystemService(NsdManager::class.java).also { it.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
        nsdListener = l
    }

    /** Re-announce on the network after a QR scan (or once it is used), so the Mac sees the `pair` hint. */
    fun refreshDiscovery() {
        if (instance == null) return
        unregisterNsd()
        main.postDelayed(::registerNsd, 500)
    }

    private fun unregisterNsd() {
        val l = nsdListener ?: return
        runCatching { nsd?.unregisterService(l) }
        nsdListener = null
    }
}

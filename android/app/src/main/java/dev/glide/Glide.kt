package dev.glide

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Live state shared by the service and the setup screen. Read and changed on the main thread only. */
object Glide {
    enum class Phase { OFF, WAITING, CONNECTED, CONTROLLING }

    /** Latest file transfer, shown on screen while it runs and briefly after. */
    data class Transfer(
        val incoming: Boolean,
        val peer: String,
        val name: String,
        val done: Long,
        val total: Long,
        val files: Int,
        val finished: Boolean,
        val failed: String?,
        /** Set when a single received file can be opened. */
        val open: Uri?,
    )

    /** A Mac asking to pair; [answer] must be called once, on any thread. */
    class PairRequest(val host: String, val code: String, val answer: (Boolean) -> Unit)

    /** Where the phone sits beside the Mac: on [macEdge] of the Mac's screen, [offset] points along it. */
    data class Arrangement(val macEdge: Int, val offset: Int)

    data class State(
        val phase: Phase = Phase.OFF,
        val host: String? = null,
        /** Mac screen size in points, known once connected. */
        val macSize: Pair<Int, Int>? = null,
        val arrangement: Arrangement? = null,
        val pairing: PairRequest? = null,
        val transfer: Transfer? = null,
    ) {
        /** Side of this phone the Mac is on. */
        val hostEdge: Int get() = opposite(arrangement?.macEdge ?: Proto.RIGHT)
    }

    var state = State()
        private set
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(change: (State) -> State) {
        main.post {
            state = change(state)
            listeners.forEach { it(state) }
        }
    }

    fun observe(l: (State) -> Unit) {
        listeners += l
        l(state)
    }

    fun stopObserving(l: (State) -> Unit) {
        listeners -= l
    }
}

/** Everything that survives a restart: paired Macs and how the pointer feels. */
object Store {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("glide", Context.MODE_PRIVATE)
    }

    /** fingerprint → Mac name */
    fun paired(): Map<String, String> =
        prefs.getStringSet("paired", emptySet())!!.mapNotNull { e ->
            e.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()

    fun pair(fp: String, name: String) = savePaired(paired() + (fp to name))

    fun forget(fp: String) = savePaired(paired() - fp)

    private fun savePaired(m: Map<String, String>) {
        prefs.edit().putStringSet("paired", m.map { "${it.key}\t${it.value}" }.toSet()).apply()
    }

    var pointerSpeed: Float
        get() = prefs.getFloat("pointerSpeed", 1f)
        set(v) = prefs.edit().putFloat("pointerSpeed", v).apply()

    /** The user read and agreed to what Glide does with Accessibility (required before turning it on). */
    var accessibilityConsent: Boolean
        get() = prefs.getBoolean("accessibilityConsent", false)
        set(v) = prefs.edit().putBoolean("accessibilityConsent", v).apply()

    var scrollSpeed: Float
        get() = prefs.getFloat("scrollSpeed", 1f)
        set(v) = prefs.edit().putFloat("scrollSpeed", v).apply()

    /** Arrangement chosen for one Mac, by its fingerprint. */
    fun arrangement(fp: String): Glide.Arrangement? {
        val v = prefs.getString("arrangement:$fp", null)?.split(',') ?: return null
        return Glide.Arrangement(v[0].toInt(), v[1].toInt())
    }

    fun setArrangement(fp: String, a: Glide.Arrangement) =
        prefs.edit().putString("arrangement:$fp", "${a.macEdge},${a.offset}").apply()

    /** A Mac whose pairing QR was scanned, until it connects (or the code expires). */
    data class Scanned(val fingerprint: String, val secret: ByteArray, val name: String, val until: Long)

    var scanned: Scanned?
        get() {
            val v = prefs.getString("scanned", null)?.split('|', limit = 4) ?: return null
            val s = Scanned(v[0], v[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray(), v[3], v[2].toLong())
            return s.takeIf { System.currentTimeMillis() < it.until }
        }
        set(s) {
            val e = prefs.edit()
            if (s == null) e.remove("scanned")
            else e.putString("scanned", "${s.fingerprint}|${s.secret.joinToString("") { "%02x".format(it) }}|${s.until}|${s.name}")
            e.apply()
        }

    /** Last arrangement used, to draw the desk before a Mac connects. */
    var lastArrangement: Glide.Arrangement?
        get() = arrangement("last")
        set(v) { if (v != null) setArrangement("last", v) }
}

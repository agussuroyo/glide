package dev.glide

import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Streams files between the Mac and this phone on transfer connections (one per batch, separate from
 * the input connection). A fixed 256 KB buffer means a 50 GB video uses the same memory as a photo.
 */
class Transfers(private val ctx: Context) {
    private val offers = ConcurrentHashMap<Int, List<Uri>>()
    private val nextId = AtomicInteger(1)

    /** Files the user shared to the Mac; the Mac fetches them with Pull(id). */
    fun addOffer(uris: List<Uri>): Proto.Offer {
        val id = nextId.getAndIncrement()
        offers[id] = uris
        val total = uris.sumOf { sizeOf(it).coerceAtLeast(0) }
        return Proto.Offer(id, uris.size, total)
    }

    fun forgetOffer(id: Int) = offers.remove(id)

    /** Mac → phone. Saves into Download/Glide/…, keeping folder structure. */
    fun receive(socket: Socket, input: DataInputStream, out: OutputStream, push: Proto.Push, peer: String) = awake {
        val resolver = ctx.contentResolver
        val progress = Progress(incoming = true, peer = peer, total = push.total, files = push.count)
        var last: Uri? = null
        socket.soTimeout = 30_000 // data flows constantly; 30s of nothing means the Mac is gone
        repeat(push.count) {
            val h = Proto.read(input) as? Proto.FileHeader ?: throw IOException("expected a file")
            val parts = h.name.split('/', '\\').map(::safePart).filter { it.isNotEmpty() }
            val name = parts.lastOrNull() ?: "file"
            val folder = (listOf("Download", "Glide") + parts.dropLast(1)).joinToString("/")
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
                put(MediaStore.Downloads.RELATIVE_PATH, folder)
                put(MediaStore.Downloads.IS_PENDING, 1) // hidden from other apps until complete
            }) ?: throw IOException("storage refused $name")
            try {
                progress.file(name)
                resolver.openOutputStream(uri)!!.use { copyExact(input, it, h.size, progress) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null) // never leave half a file behind
                throw e
            }
            last = uri
        }
        Proto.write(out, Proto.FileDone)
        progress.finish(if (push.count == 1) last else null)
    }

    /** Phone → Mac, for an offer the user made from the share sheet. */
    fun send(socket: Socket, input: DataInputStream, out: OutputStream, pull: Proto.Pull, peer: String) = awake {
        val uris = offers.remove(pull.id) ?: throw IOException("unknown offer ${pull.id}")
        val resolver = ctx.contentResolver
        // Sizes must be known up front; a stream that cannot say its length is staged in cache first.
        val items = uris.map { uri ->
            val size = sizeOf(uri)
            if (size >= 0) Triple(uri, nameOf(uri), size) else stage(uri)
        }
        val progress = Progress(incoming = false, peer = peer, total = items.sumOf { it.third }, files = items.size)
        Proto.write(out, Proto.Push(items.size, progress.total))
        for ((uri, name, size) in items) {
            progress.file(name)
            Proto.write(out, Proto.FileHeader(size, name))
            resolver.openInputStream(uri)!!.use { copyExact(it, out, size, progress) }
            if (uri.scheme == "file") File(uri.path!!).delete()
        }
        out.flush()
        socket.soTimeout = 120_000 // the Mac syncs the last file to disk before confirming
        if (Proto.read(input) != Proto.FileDone) throw IOException("Mac did not confirm")
        progress.finish(null)
    }

    private fun stage(uri: Uri): Triple<Uri, String, Long> {
        val f = File.createTempFile("share", null, ctx.cacheDir)
        ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it, BUF) } }
        return Triple(Uri.fromFile(f), nameOf(uri), f.length())
    }

    private fun sizeOf(uri: Uri): Long {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return runCatching {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it != AssetFileDescriptor.UNKNOWN_LENGTH } ?: -1
    }

    private fun nameOf(uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return safePart(c.getString(0)).ifEmpty { "file" }
        }
        return safePart(uri.lastPathSegment ?: "").ifEmpty { "file" }
    }

    /** Copies exactly [size] bytes; a short source is an error, not a silently truncated file. */
    private fun copyExact(from: InputStream, to: OutputStream, size: Long, p: Progress) {
        val buf = ByteArray(BUF)
        var left = size
        while (left > 0) {
            val n = from.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("connection ended mid-file")
            to.write(buf, 0, n)
            left -= n
            p.add(n.toLong())
        }
    }

    /** Keeps CPU and WiFi awake for the whole transfer, even with the screen off. */
    private fun <T> awake(work: () -> T): T {
        val wake = ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "glide:transfer")
        @Suppress("DEPRECATION")
        val wifi = ctx.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "glide:transfer")
        wake.acquire(6 * 60 * 60 * 1000L) // generous cap; released as soon as the batch ends
        wifi.acquire()
        try {
            return work()
        } catch (e: Exception) {
            Glide.update { s -> s.copy(transfer = s.transfer?.copy(failed = e.message ?: "transfer failed")) }
            throw e
        } finally {
            wifi.release()
            wake.release()
        }
    }

    /** Publishes progress at most every 150 ms so a fast copy does not flood the UI thread. */
    private class Progress(val incoming: Boolean, val peer: String, val total: Long, val files: Int) {
        private var done = 0L
        private var name = ""
        private var shown = 0L

        fun file(n: String) {
            name = n
            publish()
        }

        fun add(n: Long) {
            done += n
            val now = SystemClock.uptimeMillis()
            if (now - shown >= 150) {
                shown = now
                publish()
            }
        }

        private fun publish(open: Uri? = null, finished: Boolean = false) {
            val t = Glide.Transfer(incoming, peer, name, done, total, files, finished, null, open)
            Glide.update { it.copy(transfer = t) }
        }

        fun finish(open: Uri?) = publish(open, finished = true)
    }

    companion object {
        private const val BUF = 256 * 1024

        /** One path component from the other side: no separators, no "..", nothing hidden. */
        fun safePart(s: String): String =
            s.filter { !it.isISOControl() && it != '/' && it != '\\' && it != ':' }.trim().trimStart('.')

        fun mimeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
    }
}

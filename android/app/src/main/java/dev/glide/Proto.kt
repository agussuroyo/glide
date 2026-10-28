package dev.glide

import android.view.KeyEvent
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream

// Wire format: [u16 len][u8 type][payload], big-endian. Must stay in sync with desktop/src/proto.rs.
object Proto {
    const val VERSION = 2
    const val PORT = 24242
    const val SERVICE_TYPE = "_glide._tcp"
    const val PING_MS = 1000L
    const val TIMEOUT_MS = 4000

    const val LEFT = 0
    const val RIGHT = 1
    const val TOP = 2
    const val BOTTOM = 3

    sealed interface Msg
    data class Hello(val version: Int, val width: Int, val height: Int, val name: String) : Msg
    data class Enter(val edge: Int, val pos: Int) : Msg
    data class Move(val dx: Int, val dy: Int) : Msg
    data class Button(val button: Int, val down: Boolean) : Msg
    data class Scroll(val dx: Int, val dy: Int) : Msg
    data class Key(val usage: Int, val down: Boolean) : Msg
    data class Leave(val edge: Int, val pos: Int) : Msg
    data object Ping : Msg
    data class Clipboard(val text: String) : Msg
    data class ClipFiles(val count: Int, val total: Long) : Msg
    data object PasteFiles : Msg
    data class DragFiles(val count: Int, val total: Long) : Msg
    data class Offer(val id: Int, val count: Int, val total: Long) : Msg
    /** The phone sits on [edge] of the Mac's screen, [offset] points along it, covering [length] points. */
    data class Layout(val edge: Int, val offset: Int, val length: Int) : Msg
    // Transfer connection only; raw file bytes follow each FileHeader.
    data class Push(val count: Int, val total: Long) : Msg
    data class Pull(val id: Int) : Msg
    data class FileHeader(val size: Long, val name: String) : Msg
    data object FileDone : Msg

    const val MAX_CLIPBOARD = 60_000

    fun read(input: DataInputStream): Msg {
        val len = input.readUnsignedShort()
        if (len == 0) throw IOException("empty frame")
        val b = ByteArray(len)
        input.readFully(b)
        val p = DataInputStream(b.inputStream(1, len - 1))
        return when (b[0].toInt()) {
            1 -> Hello(p.readUnsignedByte(), p.readUnsignedShort(), p.readUnsignedShort(), String(p.readBytes()))
            2 -> Enter(p.readUnsignedByte(), p.readUnsignedShort())
            3 -> Move(p.readShort().toInt(), p.readShort().toInt())
            4 -> Button(p.readUnsignedByte(), p.readUnsignedByte() != 0)
            5 -> Scroll(p.readShort().toInt(), p.readShort().toInt())
            6 -> Key(p.readUnsignedShort(), p.readUnsignedByte() != 0)
            7 -> Leave(p.readUnsignedByte(), p.readUnsignedShort())
            8 -> Ping
            9 -> Clipboard(String(p.readBytes()))
            10 -> ClipFiles(p.readUnsignedShort(), p.readLong())
            11 -> PasteFiles
            12 -> DragFiles(p.readUnsignedShort(), p.readLong())
            13 -> Offer(p.readInt(), p.readUnsignedShort(), p.readLong())
            14 -> Layout(p.readUnsignedByte(), p.readInt(), p.readUnsignedShort())
            20 -> Push(p.readUnsignedShort(), p.readLong())
            21 -> Pull(p.readInt())
            22 -> FileHeader(p.readLong(), String(p.readBytes()))
            23 -> FileDone
            else -> throw IOException("bad type ${b[0]}")
        }
    }

    // What the phone sends.
    fun write(out: OutputStream, msg: Msg) {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).apply {
            when (msg) {
                is Hello -> {
                    writeByte(1); writeByte(msg.version); writeShort(msg.width); writeShort(msg.height)
                    write(msg.name.toByteArray())
                }
                is Leave -> {
                    writeByte(7); writeByte(msg.edge); writeShort(msg.pos)
                }
                is Ping -> writeByte(8)
                is Clipboard -> { writeByte(9); write(msg.text.toByteArray()) }
                is PasteFiles -> writeByte(11)
                is Offer -> { writeByte(13); writeInt(msg.id); writeShort(msg.count); writeLong(msg.total) }
                is Layout -> { writeByte(14); writeByte(msg.edge); writeInt(msg.offset); writeShort(msg.length) }
                is Push -> { writeByte(20); writeShort(msg.count); writeLong(msg.total) }
                is FileHeader -> { writeByte(22); writeLong(msg.size); write(msg.name.toByteArray()) }
                is FileDone -> writeByte(23)
                else -> throw IllegalArgumentException("phone does not send $msg")
            }
        }
        val frame = ByteArrayOutputStream()
        DataOutputStream(frame).apply { writeShort(body.size()); write(body.toByteArray()) }
        out.write(frame.toByteArray())
        out.flush()
    }

    /** USB HID keyboard usage → Android keycode, or 0 if unmapped. */
    fun hidToKeycode(u: Int): Int = when (u) {
        in 0x04..0x1D -> KeyEvent.KEYCODE_A + (u - 0x04)
        in 0x1E..0x26 -> KeyEvent.KEYCODE_1 + (u - 0x1E)
        0x27 -> KeyEvent.KEYCODE_0
        0x28 -> KeyEvent.KEYCODE_ENTER
        0x29 -> KeyEvent.KEYCODE_ESCAPE
        0x2A -> KeyEvent.KEYCODE_DEL
        0x2B -> KeyEvent.KEYCODE_TAB
        0x2C -> KeyEvent.KEYCODE_SPACE
        0x2D -> KeyEvent.KEYCODE_MINUS
        0x2E -> KeyEvent.KEYCODE_EQUALS
        0x2F -> KeyEvent.KEYCODE_LEFT_BRACKET
        0x30 -> KeyEvent.KEYCODE_RIGHT_BRACKET
        0x31 -> KeyEvent.KEYCODE_BACKSLASH
        0x33 -> KeyEvent.KEYCODE_SEMICOLON
        0x34 -> KeyEvent.KEYCODE_APOSTROPHE
        0x35 -> KeyEvent.KEYCODE_GRAVE
        0x36 -> KeyEvent.KEYCODE_COMMA
        0x37 -> KeyEvent.KEYCODE_PERIOD
        0x38 -> KeyEvent.KEYCODE_SLASH
        0x39 -> KeyEvent.KEYCODE_CAPS_LOCK
        in 0x3A..0x45 -> KeyEvent.KEYCODE_F1 + (u - 0x3A)
        0x4A -> KeyEvent.KEYCODE_MOVE_HOME
        0x4B -> KeyEvent.KEYCODE_PAGE_UP
        0x4C -> KeyEvent.KEYCODE_FORWARD_DEL
        0x4D -> KeyEvent.KEYCODE_MOVE_END
        0x4E -> KeyEvent.KEYCODE_PAGE_DOWN
        0x4F -> KeyEvent.KEYCODE_DPAD_RIGHT
        0x50 -> KeyEvent.KEYCODE_DPAD_LEFT
        0x51 -> KeyEvent.KEYCODE_DPAD_DOWN
        0x52 -> KeyEvent.KEYCODE_DPAD_UP
        0xE0 -> KeyEvent.KEYCODE_CTRL_LEFT
        0xE1 -> KeyEvent.KEYCODE_SHIFT_LEFT
        0xE2 -> KeyEvent.KEYCODE_ALT_LEFT
        0xE3 -> KeyEvent.KEYCODE_META_LEFT
        0xE4 -> KeyEvent.KEYCODE_CTRL_RIGHT
        0xE5 -> KeyEvent.KEYCODE_SHIFT_RIGHT
        0xE6 -> KeyEvent.KEYCODE_ALT_RIGHT
        0xE7 -> KeyEvent.KEYCODE_META_RIGHT
        else -> 0
    }
}

fun opposite(edge: Int) = when (edge) {
    Proto.LEFT -> Proto.RIGHT
    Proto.RIGHT -> Proto.LEFT
    Proto.TOP -> Proto.BOTTOM
    else -> Proto.TOP
}

package dev.glide

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.KeyEvent.*

/**
 * Turns host key presses into typing on the phone through the accessibility service's own input
 * connection (Android 13+), so the user keeps their normal on-screen keyboard.
 * Mac habits are translated: Cmd is the shortcut key, Option jumps by word, Cmd+arrows jump to line ends.
 */
class Keyboard(private val service: GlideService) {
    private var shift = false
    private var ctrl = false
    private var alt = false
    private var cmd = false
    private var caps = false
    private val chars = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
    /** usage → the exact (keycode, meta) sent on down, so the matching up is identical. */
    private val held = HashMap<Int, Pair<Int, Int>>()

    fun key(usage: Int, down: Boolean) {
        when (usage) {
            0xE1, 0xE5 -> { shift = down; return }
            0xE0, 0xE4 -> { ctrl = down; return }
            0xE2, 0xE6 -> { alt = down; return }
            0xE3, 0xE7 -> { cmd = down; return }
            0x39 -> { if (down) caps = !caps; return }
        }
        if (!down) {
            held.remove(usage)?.let { (code, meta) -> send(ACTION_UP, code, meta) }
            return
        }
        val code = Proto.hidToKeycode(usage)
        if (code == 0) return

        // System shortcuts work everywhere, with or without a text field.
        when {
            code == KEYCODE_ESCAPE -> return global(AccessibilityService.GLOBAL_ACTION_BACK)
            cmd && ctrl && code == KEYCODE_Q -> return global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            cmd && code == KEYCODE_H -> return global(AccessibilityService.GLOBAL_ACTION_HOME)
            cmd && code == KEYCODE_TAB -> return global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            cmd && code == KEYCODE_N -> return global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            // Files copied in Finder: Cmd+V anywhere on the phone brings them over.
            cmd && code == KEYCODE_V && service.macClipboardHasFiles -> return service.pasteFilesFromMac()
        }

        val ic = service.inputMethod?.currentInputConnection ?: return
        val sel = if (shift) META_SHIFT_ON or META_SHIFT_LEFT_ON else 0
        when {
            cmd -> when (code) {
                KEYCODE_A -> ic.performContextMenuAction(android.R.id.selectAll)
                KEYCODE_C -> { shareSelection(ic); ic.performContextMenuAction(android.R.id.copy) }
                KEYCODE_X -> { shareSelection(ic); ic.performContextMenuAction(android.R.id.cut) }
                KEYCODE_V -> ic.performContextMenuAction(android.R.id.paste)
                KEYCODE_DPAD_LEFT -> press(usage, KEYCODE_MOVE_HOME, sel)
                KEYCODE_DPAD_RIGHT -> press(usage, KEYCODE_MOVE_END, sel)
                KEYCODE_DPAD_UP -> press(usage, KEYCODE_MOVE_HOME, sel or CTRL)
                KEYCODE_DPAD_DOWN -> press(usage, KEYCODE_MOVE_END, sel or CTRL)
                KEYCODE_DEL -> { // delete to start of line
                    tap(KEYCODE_MOVE_HOME, META_SHIFT_ON or META_SHIFT_LEFT_ON)
                    tap(KEYCODE_DEL, 0)
                }
                else -> press(usage, code, sel or CTRL) // undo, redo and app shortcuts
            }
            alt && code in WORD_KEYS -> press(usage, code, sel or CTRL)
            !ctrl && commitChar(code) -> {}
            else -> press(usage, code, sel or (if (ctrl) CTRL else 0) or (if (alt) ALT else 0))
        }
    }

    /** Android does not let background apps read the clipboard, so the selection is read directly here. */
    private fun shareSelection(ic: android.accessibilityservice.InputMethod.AccessibilityInputConnection) {
        val st = ic.getSurroundingText(0, 0, 0) ?: return
        val sel = st.text.subSequence(
            st.selectionStart.coerceIn(0, st.text.length), st.selectionEnd.coerceIn(0, st.text.length),
        ).toString()
        if (sel.isNotEmpty() && sel.toByteArray().size <= Proto.MAX_CLIPBOARD) service.copyToMac(sel)
    }

    /** Printable keys go in as text, which works in every text field and respects Shift and Caps Lock. */
    private fun commitChar(code: Int): Boolean {
        var meta = if (shift) META_SHIFT_ON else 0
        if (caps) meta = meta or META_CAPS_LOCK_ON
        if (alt) meta = meta or META_ALT_ON
        val c = chars.get(code, meta)
        if (c == 0 || c and KeyCharacterMap.COMBINING_ACCENT != 0) return false
        service.inputMethod?.currentInputConnection?.commitText(String(Character.toChars(c)), 1, null)
        return true
    }

    private fun press(usage: Int, code: Int, meta: Int) {
        held[usage] = code to meta
        send(ACTION_DOWN, code, meta)
    }

    private fun tap(code: Int, meta: Int) {
        send(ACTION_DOWN, code, meta)
        send(ACTION_UP, code, meta)
    }

    private fun send(action: Int, code: Int, meta: Int) {
        val now = SystemClock.uptimeMillis()
        service.inputMethod?.currentInputConnection?.sendKeyEvent(KeyEvent(now, now, action, code, 0, meta))
    }

    private fun global(action: Int) {
        service.performGlobalAction(action)
    }

    /** Host went away or control moved back: forget held modifiers so nothing sticks. */
    fun reset() {
        shift = false; ctrl = false; alt = false; cmd = false
        held.clear()
    }

    companion object {
        private const val CTRL = META_CTRL_ON or META_CTRL_LEFT_ON
        private const val ALT = META_ALT_ON or META_ALT_LEFT_ON
        private val WORD_KEYS = setOf(KEYCODE_DEL, KEYCODE_FORWARD_DEL, KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT)
    }
}

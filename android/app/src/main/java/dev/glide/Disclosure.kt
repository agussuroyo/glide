package dev.glide

import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.glide.Ui.dpi
import dev.glide.Ui.pressable
import dev.glide.Ui.shape
import dev.glide.Ui.text

/**
 * Prominent disclosure shown before the user is sent to Accessibility settings. It says, in the app's
 * normal flow, what the service does, what it does not do, and where data goes; the user must agree.
 */
object Disclosure {
    private val points = listOf(
        "Glide uses Android's Accessibility service so a Mac you pair can control this phone: it shows the " +
            "Mac's cursor, taps, swipes, scrolls, types, and presses Back, Home and Recents.",
        "It only acts while a paired Mac is connected and its cursor is on this phone. It does not read or " +
            "record what is on your screen.",
        "Nothing is sent to us or to any server. Your Mac sends the cursor and keys; text you copy with Cmd C " +
            "and files you share go only to your paired Mac, encrypted, over your WiFi.",
        "Banking apps such as Livin' by Mandiri block accounts while services like this are on. To protect " +
            "you, Glide notices when you tap or open such an app and turns itself off first. It only looks at " +
            "the app's name for this and keeps no record of the apps you use.",
    )

    fun show(ctx: Context, done: (Boolean) -> Unit) {
        val dialog = Dialog(ctx)
        var answered = false
        fun answer(ok: Boolean) {
            if (answered) return
            answered = true
            dialog.dismiss()
            done(ok)
        }
        val pad = ctx.dpi(24)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, ctx.dpi(16))
            background = ctx.shape(R.color.surface, 24f, R.color.line)
            addView(ctx.text(22f, 600, R.color.ink, "Before you turn on Glide"))
            points.forEach { p ->
                addView(ctx.text(15f, 400, R.color.muted, p).apply {
                    setLineSpacing(0f, 1.3f)
                    setPadding(0, ctx.dpi(12), 0, 0)
                })
            }
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.END
                setPadding(0, ctx.dpi(20), 0, 0)
                addView(button(ctx, R.color.surface, R.color.ink, "Not now") { answer(false) })
                addView(button(ctx, R.color.accent, R.color.on_accent, "Agree and continue") { answer(true) }.apply {
                    (layoutParams as LinearLayout.LayoutParams).marginStart = ctx.dpi(8)
                })
            })
        }
        dialog.setContentView(ScrollView(ctx).apply { addView(body) })
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH_PARENT, WRAP_CONTENT)
            decorView.setPadding(ctx.dpi(12), 0, ctx.dpi(12), 0)
        }
        dialog.setOnCancelListener { answer(false) }
        dialog.show()
    }

    private fun button(ctx: Context, fill: Int, ink: Int, label: String, onClick: () -> Unit) =
        ctx.text(16f, 600, ink, label).apply {
            background = ctx.shape(fill, 14f)
            setPadding(ctx.dpi(18), ctx.dpi(14), ctx.dpi(18), ctx.dpi(14))
            minHeight = ctx.dpi(48)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            pressable()
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }
}

/**
 * The Accessibility list with Glide highlighted where the phone supports it. (The per-app page needs a
 * system-only permission, so apps cannot open it directly.)
 */
fun accessibilitySettingsIntent(ctx: Context): Intent {
    val key = ComponentName(ctx, GlideService::class.java).flattenToString()
    return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .putExtra(":settings:fragment_args_key", key)
        .putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", key) })
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

fun openAccessibilitySettings(ctx: Context) = ctx.startActivity(accessibilitySettingsIntent(ctx))

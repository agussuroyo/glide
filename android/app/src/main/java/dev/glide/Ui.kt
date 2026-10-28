package dev.glide

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView

/** Small shared vocabulary for the hand-built views. Radii: 24dp containers, 14dp controls. */
object Ui {
    /** Quick out, long settle: things arrive with weight instead of a linear slide. */
    val settle = PathInterpolator(0.32f, 0.72f, 0f, 1f)

    fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
    fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    fun Context.color(id: Int): Int = getColor(id)

    private val faces = HashMap<Int, Typeface>()

    /** Instrument Sans at an exact weight from the variable font. */
    fun Context.face(weight: Int): Typeface = faces.getOrPut(weight) {
        Typeface.create(resources.getFont(R.font.instrument_sans), weight, false)
    }

    fun Context.text(sizeSp: Float, weight: Int, color: Int, value: CharSequence = ""): TextView =
        TextView(this).apply {
            textSize = sizeSp
            typeface = face(weight)
            fontVariationSettings = "'wght' $weight"
            setTextColor(color(color))
            text = value
            // Instrument Sans is drawn wide; a hair of negative tracking on large sizes keeps headlines tight.
            letterSpacing = if (sizeSp >= 24) -0.02f else 0f
        }

    fun Context.shape(fill: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color(fill))
            cornerRadius = dp(radiusDp)
            if (stroke != null) setStroke(dpi(1), color(stroke))
        }

    /** Press feedback only; returns false so the normal click (and its accessibility action) still fires. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun View.pressable() {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).setInterpolator(settle).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(settle).start()
            }
            false
        }
    }
}

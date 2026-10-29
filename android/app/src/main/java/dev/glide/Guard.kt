package dev.glide

import android.view.accessibility.AccessibilityEvent

/**
 * Banking apps such as Livin' by Mandiri block accounts while a non-official accessibility service is on.
 * Glide turns itself off before they get the chance to see it.
 *
 * Earliest signal: the tap that launches the app (a launcher icon, a recents card) carries its label,
 * and arrives before the app's process has even started. Later signals (its window, its notification)
 * are kept as a backstop, though by then the app may already have checked.
 */
object Guard {
    /** Package names of apps Glide must never be on for. */
    private val PACKAGES = setOf("id.bmri.livin")

    /** Labels whose tap launches one of them (matched case-insensitively). */
    private val LABELS = listOf("livin")

    /** Returns the app's display name if this event means a protected app is about to open. */
    fun protectedAppFor(e: AccessibilityEvent): String? {
        if (e.packageName?.toString() in PACKAGES) return "Livin' by Mandiri"
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val label = buildString {
                e.text.forEach { append(it).append(' ') }
                e.contentDescription?.let { append(it) }
            }.lowercase()
            if (LABELS.any { it in label }) return "Livin' by Mandiri"
        }
        return null
    }

    /** A tap in the home app with no label: in practice a Recents card. */
    fun isUnlabeledLauncherTap(e: AccessibilityEvent, homePackage: String?): Boolean =
        e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            homePackage != null && e.packageName?.toString() == homePackage &&
            e.text.all { it.isNullOrBlank() } && e.contentDescription.isNullOrBlank()
}

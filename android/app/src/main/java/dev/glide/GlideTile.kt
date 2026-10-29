package dev.glide

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile. On: one tap turns Glide off completely (e.g. before opening a banking app).
 * Off: one tap opens Glide's Accessibility switch, since Android lets only the user turn a service on.
 */
class GlideTile : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val service = GlideService.instance
        if (service != null) {
            service.disableSelf()
            refresh(on = false)
            return
        }
        val target = if (Store.run { init(this@GlideTile); accessibilityConsent }) {
            accessibilitySettingsIntent(this)
        } else {
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // the disclosure comes first
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, target, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(target)
        }
    }

    private fun refresh(on: Boolean = GlideService.instance != null) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (on) "On" else "Off"
        tile.updateTile()
    }

    companion object {
        /** Ask the system to redraw the tile after the service starts or stops. */
        fun update(ctx: Context) = requestListeningState(ctx, ComponentName(ctx, GlideTile::class.java))
    }
}

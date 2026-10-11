package com.macrosniper.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** O trong bang Cai dat nhanh: bat / tat giao dien noi ma khong can mo app. */
class QuickTileService : TileService() {

    companion object {
        fun refresh(ctx: Context) {
            try {
                TileService.requestListeningState(ctx, ComponentName(ctx, QuickTileService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun onStartListening() { update() }

    override fun onClick() {
        val canDraw = try { Settings.canDrawOverlays(this) } catch (_: Exception) { true }
        if (!canDraw) { openApp(); return }
        if (OverlayService.uiRunning(this)) {
            OverlayService.requestStop(this)
        } else {
            OverlayService.requestStart(this)
        }
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        val on = OverlayService.uiRunning(this)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Macro Touch"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "Dang bat" else "Da tat"
        t.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                startActivityAndCollapse(i)
            }
        } catch (_: Exception) {
        }
    }
}

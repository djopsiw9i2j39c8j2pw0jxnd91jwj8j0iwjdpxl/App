package com.macrosniper.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Ô trong bảng Cài đặt nhanh (kéo thanh thông báo xuống): bật / tắt giao diện nổi mà không cần mở app. */
class QuickTileService : TileService() {

    companion object {
        /** Gọi khi giao diện nổi bật / tắt để ô cập nhật lại trạng thái. */
        fun refresh(ctx: Context) {
            try {
                TileService.requestListeningState(ctx, ComponentName(ctx, QuickTileService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun onStartListening() {
        update()
    }

    override fun onClick() {
        if (MacroService.instance != null && Store.isRunning(this)) {
            MacroService.instance?.stopOverlay()
            update()
            return
        }
        val canDraw = try {
            Settings.canDrawOverlays(this)
        } catch (_: Exception) {
            true
        }
        // thiếu quyền vẽ nổi, hoặc chế độ Trợ năng mà chưa bật Trợ năng -> mở app để xử lý
        if (!canDraw || (Store.tapMode(this) == TAP_ACC && TouchAccessService.instance == null)) {
            openApp()
            return
        }
        if (!MacroService.start(this)) openApp()
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        val on = MacroService.instance != null && Store.isRunning(this)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Macro Touch"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "Đang bật" else "Đã tắt"
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

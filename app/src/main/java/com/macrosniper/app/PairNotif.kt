package com.macrosniper.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build

/**
 * Thông báo có ô trả lời nhanh để nhập mã ghép cặp 6 số.
 * Dùng khi hộp thoại "Ghép nối thiết bị bằng mã" đang mở: kéo thanh thông báo xuống, gõ mã
 * vào thông báo là ghép được, không cần rời khỏi màn hình Cài đặt.
 */
object PairNotif {
    const val CHANNEL = "pair"
    const val ACTION = "com.macrosniper.app.PAIR_CODE"
    const val KEY = "code"
    private const val ID = 4711

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Hiện thông báo có ô nhập mã. Trả về false nếu chưa có quyền thông báo / máy dưới Android 11. */
    fun show(ctx: Context, status: String? = null): Boolean {
        if (Build.VERSION.SDK_INT < 30 || !canPost(ctx)) return false
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        val ri = RemoteInput.Builder(KEY).setLabel("Mã 6 số").build()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, PairReceiver::class.java).setAction(ACTION), flags
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(ctx, android.R.drawable.ic_menu_edit), "Nhập mã", pi
        ).addRemoteInput(ri).build()
        val text = status ?: "Mở Gỡ lỗi không dây → \"Ghép nối thiết bị bằng mã\", rồi bấm \"Nhập mã\" ở đây và gõ mã 6 số."
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Macro Touch · Ghép cặp")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .addAction(action)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        nm.notify(ID, n)
        return true
    }

    /** Thay thông báo bằng 1 dòng kết quả (không còn ô nhập). */
    fun result(ctx: Context, text: String) {
        if (Build.VERSION.SDK_INT < 30 || !canPost(ctx)) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Macro Touch · Ghép cặp")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000L)
            .build()
        nm.notify(ID, n)
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Ghép cặp Gỡ lỗi WiFi", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }
}

class PairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PairNotif.ACTION) return
        val app = context.applicationContext
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairNotif.KEY)?.toString().orEmpty().filter { it.isDigit() }
        if (code.length < 6) {
            PairNotif.show(app, "Mã phải đủ 6 số. Bấm \"Nhập mã\" để nhập lại.")
            return
        }
        val pr = goAsync()
        PairNotif.result(app, "Đang ghép cặp…")
        AdbClient.pair(app, code) { ok, msg ->
            if (ok) {
                PairNotif.result(app, msg)
                AdbClient.connect(app)
            } else {
                PairNotif.show(app, msg + "\nBấm \"Nhập mã\" để thử lại.")
            }
            pr.finish()
        }
    }
}

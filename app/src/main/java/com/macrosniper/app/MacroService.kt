package com.macrosniper.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.view.accessibility.AccessibilityEvent

/**
 * Dịch vụ Trợ năng MỎNG: chỉ dùng cho chế độ chạm "Trợ năng" (bắn cú chạm bằng dispatchGesture).
 *
 * - KHÔNG vẽ, KHÔNG quản lý giao diện nổi (toàn bộ việc đó do [OverlayService] làm bằng quyền
 *   "Hiển thị trên ứng dụng khác").
 * - KHÔNG nghe sự kiện, KHÔNG đọc nội dung màn hình.
 * - Chế độ "Gỡ lỗi WiFi" hoàn toàn không đụng tới dịch vụ này: có thể để nó TẮT trong Cài đặt → Trợ năng.
 */
class MacroService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MacroService? = null

        /** Thời gian đè xuống của mỗi cú chạm (ms). */
        private const val TAP_MS = 30L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // báo cho giao diện nổi biết: động cơ chạm Trợ năng đã sẵn sàng -> các nút trở lại bình thường
        OverlayService.instance?.onAccessibilityChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        if (instance === this) instance = null
        OverlayService.instance?.onAccessibilityChanged()
    }

    /**
     * Bắn 1 cú chạm tại (x, y) px. [done] được gọi khi cử chỉ hoàn tất hoặc bị huỷ (để chuỗi đi tuần tự).
     * Trả về false nếu hệ thống từ chối cử chỉ.
     */
    fun tap(x: Float, y: Float, handler: Handler, done: (Boolean) -> Unit): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                done(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                done(false)
            }
        }
        return try {
            dispatchGesture(gesture, cb, handler)
        } catch (_: Exception) {
            false
        }
    }
}

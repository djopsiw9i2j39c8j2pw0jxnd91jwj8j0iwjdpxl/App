package com.macrosniper.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Dịch vụ Trợ năng — CHỈ dùng cho chế độ chạm "Trợ năng" (dispatchGesture).
 * Chế độ "Gỡ lỗi WiFi" không cần bật dịch vụ này; giao diện nổi chạy bằng [MacroService] (dịch vụ thường).
 */
class TouchAccessService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TouchAccessService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}

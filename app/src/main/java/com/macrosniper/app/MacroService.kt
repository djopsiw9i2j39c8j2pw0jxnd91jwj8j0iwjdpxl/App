package com.macrosniper.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.view.accessibility.AccessibilityEvent

/**
 * Dich vu Tro nang CUA RIENG mode "Tro nang".
 *
 * - Mode "Tro nang" (dung dispatchGesture): dich vu nay phai duoc bat.
 * - Mode "Go loi WiFi" (dung ADB tu nhung + GhostTouch): KHONG can dich vu nay chay. Ngan hang
 *   kiem tra "co app nao bat Tro nang khong" se khong thay Macro Touch -> khong bi chan.
 *
 * Toan bo viec tao / ve / quan li UI noi va cu cham qua Wi-Fi deu nam o OverlayService.
 */
class MacroService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MacroService? = null

        /**
         * Gui 1 cu cham tai (x, y) px man hinh hien tai bang quyen Tro nang.
         * Tra ve false neu dich vu Tro nang chua bat -> OverlayService hien thong bao, KHONG tu doi mode.
         */
        fun dispatchTap(x: Float, y: Float, handler: Handler, done: () -> Unit): Boolean {
            val svc = instance ?: return false
            return try {
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0L, 10L)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                val cb = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { done() }
                    override fun onCancelled(g: GestureDescription?) { done() }
                }
                svc.dispatchGesture(gesture, cb, handler)
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}

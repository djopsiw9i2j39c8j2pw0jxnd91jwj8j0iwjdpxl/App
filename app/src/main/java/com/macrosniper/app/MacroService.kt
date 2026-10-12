package com.macrosniper.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
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

        /** Độ dài mỗi đoạn của cú vuốt liên tục (ms). */
        private const val DRAG_SEG_MS = 40L
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

    // ------------------------------------------------------------------ vuốt liên tục (cụm nút vuốt tâm, cần Android 8.0+)

    private var dStroke: GestureDescription.StrokeDescription? = null
    private var dCurX = 0f
    private var dCurY = 0f
    private var dTgtX = 0f
    private var dTgtY = 0f
    private var dActive = false
    private var dBusy = false
    private var dEnding = false
    private var dFinalSent = false
    private var dHandler: Handler? = null

    fun canDrag(): Boolean = Build.VERSION.SDK_INT >= 26

    /** Đang có một cú vuốt liên tục còn sống (false nếu bị hệ thống huỷ -> bộ điều khiển sẽ đặt lại). */
    fun dragAlive(): Boolean = dActive

    fun dragBegin(x: Float, y: Float, handler: Handler): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        dHandler = handler
        dStroke = null
        dCurX = x
        dCurY = y
        dTgtX = x
        dTgtY = y
        dActive = true
        dBusy = false
        dEnding = false
        dFinalSent = false
        dispatchDragSeg()
        return dActive
    }

    /** Chỉ ghi nhận đích mới; đoạn vuốt kế tiếp sẽ đi tới đích này. */
    fun dragMove(x: Float, y: Float) {
        dTgtX = x
        dTgtY = y
    }

    fun dragEnd() {
        if (!dActive) return
        dEnding = true
        if (!dBusy) dispatchDragSeg()
    }

    private fun dispatchDragSeg() {
        if (!dActive || Build.VERSION.SDK_INT < 26) return
        val tx = dTgtX
        val ty = dTgtY
        val path = Path()
        path.moveTo(dCurX, dCurY)
        if (tx != dCurX || ty != dCurY) path.lineTo(tx, ty)
        val cont = !dEnding
        val prev = dStroke
        val stroke = if (prev == null) {
            GestureDescription.StrokeDescription(path, 0L, DRAG_SEG_MS, cont)
        } else {
            prev.continueStroke(path, 0L, DRAG_SEG_MS, cont)
        }
        dStroke = stroke
        dFinalSent = dEnding
        dBusy = true
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                dCurX = tx
                dCurY = ty
                dBusy = false
                if (dFinalSent) {
                    dActive = false
                    dStroke = null
                } else {
                    dispatchDragSeg()
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                dBusy = false
                dActive = false
                dStroke = null
            }
        }
        val ok = try {
            dispatchGesture(stroke.let { GestureDescription.Builder().addStroke(it).build() }, cb, dHandler)
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            dBusy = false
            dActive = false
            dStroke = null
        }
    }
}

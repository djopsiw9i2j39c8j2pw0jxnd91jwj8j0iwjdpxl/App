package com.macrosniper.app

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

/**
 * Tiến trình nền chạy bằng QUYỀN SHELL (do AdbClient mở qua Gỡ lỗi không dây):
 *
 *   CLASSPATH=<đường dẫn APK> app_process /system/bin com.macrosniper.app.TouchServer
 *
 * Nó sống suốt cùng kết nối ADB, đọc lệnh từng dòng từ stdin và bơm MotionEvent thẳng vào
 * InputManager (giống cách scrcpy làm). Khác `input tap` (mỗi lần chạm khởi động cả 1 tiến trình
 * Java, chậm và dễ khựng), ở đây chạm là gọi hàm trực tiếp, nhanh và liền mạch.
 *
 * Giao thức:
 *   vào : "<id> T <x> <y>"  -> chạm 1 cú tại (x, y) theo toạ độ màn hình hiện tại
 *         "Q"               -> thoát
 *   ra  : "__MSREADY"       khi sẵn sàng
 *         "__MSERR <lý do>" nếu không khởi động được
 *         "__MSDONE_<id>"   sau mỗi cú chạm
 *
 * Chú ý: file này chạy trong tiến trình shell, KHÔNG có Context/Application.
 */
object TouchServer {

    private const val SOURCE_TOUCHSCREEN = InputDevice.SOURCE_TOUCHSCREEN
    private const val HOLD_MS = 15L

    private var target: Any? = null
    private var inject: Method? = null

    @JvmStatic
    fun main(args: Array<String>) {
        val out = System.out
        try {
            if (!init()) {
                out.println("__MSERR không tìm thấy InputManager")
                out.flush()
                return
            }
        } catch (t: Throwable) {
            out.println("__MSERR " + t.javaClass.simpleName + " " + (t.message ?: ""))
            out.flush()
            return
        }
        out.println("__MSREADY")
        out.flush()

        val r = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = r.readLine() ?: break // kết nối ADB đóng -> tự thoát
            val p = line.trim().split(' ')
            if (p.isEmpty()) continue
            if (p[0] == "Q") break
            if (p.size >= 4 && p[1] == "T") {
                val x = p[2].toFloatOrNull()
                val y = p[3].toFloatOrNull()
                if (x != null && y != null) {
                    try {
                        tap(x, y)
                    } catch (_: Throwable) {
                    }
                }
                out.println("__MSDONE_" + p[0])
                out.flush()
            }
        }
    }

    /** Tìm InputManager (tên lớp khác nhau tuỳ đời Android) và hàm injectInputEvent(InputEvent, int). */
    private fun init(): Boolean {
        val candidates = arrayOf(
            "android.hardware.input.InputManagerGlobal", // Android 14+
            "android.hardware.input.InputManager"
        )
        for (name in candidates) {
            try {
                val c = Class.forName(name)
                val g = c.getDeclaredMethod("getInstance")
                g.isAccessible = true
                val inst = g.invoke(null) ?: continue
                val m = c.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                m.isAccessible = true
                target = inst
                inject = m
                return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun send(action: Int, downTime: Long, x: Float, y: Float) {
        val props = arrayOf(MotionEvent.PointerProperties().also {
            it.id = 0
            it.toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().also {
            it.x = x
            it.y = y
            it.pressure = 1f
            it.size = 1f
        })
        val ev = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action,
            1, props, coords,
            0, 0, 1f, 1f,
            0, 0, SOURCE_TOUCHSCREEN, 0
        )
        try {
            inject!!.invoke(target, ev, 0) // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
        } finally {
            ev.recycle()
        }
    }

    private fun tap(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, t, x, y)
        Thread.sleep(HOLD_MS)
        send(MotionEvent.ACTION_UP, t, x, y)
    }
}

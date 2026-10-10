package com.macrosniper.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot

enum class Mode { OFF, RUN, EDIT }

class MacroService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MacroService? = null
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val chainToken = Any()
    private var density = 1f

    private var mode = Mode.OFF
    private var buttons = mutableListOf<MacroButton>()
    private var selectedId = -1

    // mọi cửa sổ nổi đang quản lý (để tự gắn lại nếu bị mất)
    private val live = ArrayList<Pair<View, WindowManager.LayoutParams>>()
    private val btnViews = HashMap<Int, BtnView>()
    private val btnLps = HashMap<Int, WindowManager.LayoutParams>()
    private var backdrop: BackdropView? = null
    private var bubble: BubbleView? = null
    private var panelRoot: PanelLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var panelContent: LinearLayout? = null
    private var panelTitle: TextView? = null
    private var panelX = -1
    private var panelY = -1
    private var nameDraft = ""
    private var listOpen = false
    private var addTargetMain = 0 // main đang được chọn để thêm nút số vào
    private var builtTapMode = -1 // chế độ chạm lúc dựng overlay (cờ cửa sổ phụ thuộc vào nó)
    private var builtType = 0 // loại cửa sổ nổi đang dùng (vẽ trên app / Trợ năng dự phòng)
    private var builtSize: Pair<Int, Int>? = null // cỡ màn hình lúc dựng overlay
    private var backdropLp: WindowManager.LayoutParams? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var crossView: CrosshairView? = null
    private var crossLp: WindowManager.LayoutParams? = null
    private var panelW = 0
    private var missTicks = 0 // số nhịp watchdog liên tiếp thấy cửa sổ nổi bị mất
    private var pairOpen = false
    private var pairCode = ""
    private var lastAdbWarn = 0L
    private val adbListener: () -> Unit = {
        if (mode == Mode.EDIT && selectedId == -1) refreshPanel()
        // trạng thái Gỡ lỗi WiFi đổi -> nút main "xuyên" có thể bật / tắt được -> cập nhật cờ cảm ứng
        syncPassFlags()
    }
    private var logoBmp: Bitmap? = null

    // ---------------------------------------------------------------- vòng đời

    private val watchdog = object : Runnable {
        override fun run() {
            try {
                if (Store.isRunning(this@MacroService)) {
                    if (mode == Mode.OFF) {
                        startOverlay()
                    } else {
                        checkHealth()
                        syncPassFlags() // hết thời gian "trễ" thì trả lại trạng thái đúng
                    }
                    if (Store.tapMode(this@MacroService) == TAP_ADB) AdbClient.tick(applicationContext)
                }
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 1000)
        }
    }

    /**
     * Chỉ dựng lại TOÀN BỘ giao diện nổi khi thật sự cần:
     *  - cửa sổ nổi bị hệ thống gỡ mất (mất liên tục >= 2 nhịp ~ 1-2 giây),
     *  - đổi hướng / cỡ màn hình,
     *  - đổi loại cửa sổ (vừa cấp / thu hồi quyền "hiển thị trên ứng dụng khác").
     * Còn lại (đổi chế độ chạm, thêm/xóa nút, vào/ra setup...) chỉ cập nhật tại chỗ, không dựng lại.
     */
    private fun checkHealth() {
        val size = screenSize()
        val ll = lastLand
        if ((ll != null && ll != isLand()) || (builtSize != null && builtSize != size)) {
            rebuildAll()
            return
        }
        if (builtType != overlayType()) {
            rebuildAll()
            return
        }
        val lost = live.any { !it.first.isAttachedToWindow }
        if (!lost) {
            missTicks = 0
        } else {
            missTicks++
            if (missTicks >= 2) {
                // mất một lúc rồi vẫn chưa quay lại -> dựng lại sạch toàn bộ
                missTicks = 0
                rebuildAll()
                return
            }
            ensureAttached() // lần đầu: thử gắn lại nhẹ nhàng, chưa cần dựng lại
        }
        if (builtTapMode != Store.tapMode(this)) onTapModeChanged()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 1000)
        AdbClient.listeners.remove(adbListener)
        AdbClient.listeners.add(adbListener)
        AdbClient.fingerListener = { pts -> handler.post { onFingers(pts) } }
        if (Store.isRunning(this)) startOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        density = resources.displayMetrics.density
        if (mode != Mode.OFF) {
            // Cấu hình đổi không phải lúc nào cũng là xoay màn hình (chế độ tối, cỡ chữ, bàn phím...).
            // Chỉ dựng lại khi cỡ màn hình thật sự đổi.
            handler.removeCallbacks(configCheck)
            handler.postDelayed(configCheck, 350)
        }
    }

    private val configCheck = Runnable {
        if (mode != Mode.OFF) {
            if (builtSize != screenSize()) rebuildAll() else syncArm()
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        AdbClient.fingerListener = null
        AdbClient.listeners.remove(adbListener)
        AdbClient.disconnect()
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(configCheck)
        handler.removeCallbacksAndMessages(chainToken)
        mode = Mode.OFF
        removeAll()
        instance = null
    }

    // ---------------------------------------------------------------- API cho MainActivity

    fun startOverlay() {
        Store.setRunning(this, true)
        if (logoBmp == null) {
            logoBmp = BitmapFactory.decodeResource(resources, R.drawable.logo_bubble)
        }
        buttons = Store.loadCurrent(this)
        lastLand = null
        normalize()
        selectedId = -1
        listOpen = false
        mode = Mode.RUN
        if (Store.tapMode(this) == TAP_ADB) AdbClient.connect(applicationContext) else AdbClient.disconnect()
        rebuildAll()
    }

    fun stopOverlay() {
        Store.setRunning(this, false)
        cancelChain()
        AdbClient.disconnect()
        mode = Mode.OFF
        removeAll()
    }

    // ---------------------------------------------------------------- tiện ích

    private fun dp(v: Number): Int = (v.toFloat() * density + 0.5f).toInt()

    private fun screenSize(): Pair<Int, Int> {
        try {
            val dmg = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val d = dmg.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            if (d != null) {
                val m = DisplayMetrics()
                @Suppress("DEPRECATION")
                d.getRealMetrics(m)
                if (m.widthPixels > 0 && m.heightPixels > 0) return Pair(m.widthPixels, m.heightPixels)
            }
        } catch (_: Exception) {
        }
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    /** Surface.ROTATION_0..3 của màn hình chính. */
    private fun displayRotation(): Int = try {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: 0
    } catch (_: Exception) {
        0
    }

    /**
     * Vẽ giao diện nổi BẰNG QUYỀN "Hiển thị trên ứng dụng khác" (TYPE_APPLICATION_OVERLAY) thay vì cửa sổ Trợ năng
     * (cửa sổ Trợ năng chặn cảm ứng ở thoát / thông báo / tin nhắn...). Chưa cấp quyền thì dùng Trợ năng tạm.
     */
    @Suppress("DEPRECATION")
    private fun overlayType(): Int {
        val can = try {
            Settings.canDrawOverlays(this)
        } catch (_: Exception) {
            false
        }
        return if (can) {
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
    }

    /**
     * Bật/tắt khả năng nhận chạm của một cửa sổ nổi tại chỗ. Android 12+ chặn cú chạm xuyên qua cửa sổ nổi
     * của app khác nếu độ mờ cửa sổ > 0.8 -> cửa sổ không nhận chạm (nút số...) để alpha 0.8 cho cú chạm macro đi xuyên.
     */
    private fun applyTouchable(lp: WindowManager.LayoutParams, touchable: Boolean) {
        lp.flags = if (touchable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        lp.alpha = if (!touchable && Build.VERSION.SDK_INT >= 31 &&
            lp.type != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        ) 0.8f else 1f
    }

    private fun updateWin(v: View, lp: WindowManager.LayoutParams) {
        try {
            wm.updateViewLayout(v, lp)
        } catch (_: Exception) {
        }
    }

    /**
     * Cửa sổ KHÔNG nhận chạm khi chạy macro (nút số, nút main "xuyên", tâm / vòng) dùng loại cửa sổ Trợ năng (cửa sổ "tin cậy").
     * Android 12+ cộng dồn độ mờ của MỌI cửa sổ nổi chồng lên điểm chạm (app khác): vượt 0.8 là CHẶN cú chạm xuống game
     * (vd. nút số nằm trong vòng tròn quanh tâm -> chặn ống nhòm). Cửa sổ Trợ năng tin cậy thì không bị tính, nên cú chạm đi xuyên hoàn toàn.
     * Lúc setup (cần nhận chạm + nằm dưới bảng / bong bóng) thì dùng lại loại cửa sổ thường.
     */
    private fun passType(): Int =
        if (mode == Mode.RUN && Build.VERSION.SDK_INT >= 31 &&
            builtType != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        ) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else builtType

    /** Nút main đang ở chế độ "cảm ứng xuyên" thật sự: bật cài đặt + có luồng đọc ngón thật (Gỡ lỗi WiFi đã kết nối). */
    private var lastFingersOk = 0L

    /**
     * Có chút "trễ" (4 giây): luồng đọc ngón chớp tắt ngắn (kết nối lại ADB...) thì nút main vẫn giữ chế độ KHÔNG nhận chạm,
     * tránh việc cửa sổ nút main lúc nhận lúc không -> khi thì chỉ chạm được UI app, khi thì chỉ chạm được game.
     */
    private fun isPass(b: MacroButton): Boolean {
        if (b.kind != Kind.MAIN || !b.passThru || Store.tapMode(this) != TAP_ADB) return false
        val now = SystemClock.uptimeMillis()
        if (AdbClient.fingersAvailable()) {
            lastFingersOk = now
            return true
        }
        return lastFingersOk != 0L && now - lastFingersOk < 4000L
    }

    /** Đồng bộ lại cờ cảm ứng của các nút main "xuyên" nếu trạng thái thật sự đổi (gọi từ watchdog / ADB listener). */
    private fun syncPassFlags() {
        if (mode != Mode.RUN) return
        val mismatch = buttons.any {
            it.kind == Kind.MAIN && it.passThru &&
                (isPass(it) == (((btnLps[it.id]?.flags ?: 0) and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0))
        }
        if (mismatch) applyModeUi(animate = false)
    }

    private fun buttonType(b: MacroButton): Int =
        if (b.kind == Kind.NUM || isPass(b)) passType() else builtType

    /** Gắn lại cửa sổ với loại mới (loại cửa sổ không đổi tại chỗ được). */
    private var retyped = false

    private fun retypeWindow(v: View, lp: WindowManager.LayoutParams) {
        try {
            wm.removeViewImmediate(v)
        } catch (_: Exception) {
        }
        try {
            wm.addView(v, lp)
        } catch (_: Exception) {
        }
        retyped = true
    }

    private fun baseLp(w: Int, h: Int, touchable: Boolean, type: Int = builtType): WindowManager.LayoutParams {
        // FLAG_SPLIT_TOUCH: cho phép ngón này chạm nút nổi, ngón kia chạm game (cửa sổ khác) CÙNG LÚC.
        // Thiếu cờ này thì khi 1 ngón đang đè nút main, mọi ngón khác bị Android dồn hết vào nút main
        // -> không xoay được camera / bấm được nút khác của game.
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        // Chế độ Trợ năng: giữ cờ y như bản cũ (đã chạy tốt). Chỉ chế độ ADB mới cần tách cảm ứng.
        if (Store.tapMode(this) == TAP_ADB) flags = flags or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
        val lp = WindowManager.LayoutParams(
            w, h,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        )
        applyTouchable(lp, touchable)
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return lp
    }

    private fun addOverlay(v: View, lp: WindowManager.LayoutParams) {
        live.add(Pair(v, lp))
        try {
            wm.addView(v, lp)
        } catch (_: Exception) {
            // watchdog sẽ thử gắn lại
        }
    }

    private fun removeAll() {
        if (!::wm.isInitialized) return
        setPanelFocusable(false)
        for ((v, _) in live) {
            try {
                wm.removeViewImmediate(v)
            } catch (_: Exception) {
            }
        }
        live.clear()
        btnViews.clear()
        btnLps.clear()
        backdrop = null
        backdropLp = null
        bubble = null
        bubbleLp = null
        crossView = null
        crossLp = null
        panelRoot = null
        panelLp = null
        panelContent = null
        panelTitle = null
    }

    /** Gỡ riêng cửa sổ của MỘT nút (không đụng tới các cửa sổ khác). */
    private fun removeButtonWindow(id: Int) {
        val v = btnViews.remove(id)
        btnLps.remove(id)
        if (v != null) {
            live.removeAll { it.first === v }
            try {
                wm.removeViewImmediate(v)
            } catch (_: Exception) {
            }
        }
    }

    /** Đổi chế độ chạm: chỉ cập nhật cờ cửa sổ tại chỗ (FLAG_SPLIT_TOUCH), KHÔNG dựng lại giao diện. */
    private fun onTapModeChanged() {
        builtTapMode = Store.tapMode(this)
        val adb = builtTapMode == TAP_ADB
        for ((v, lp) in live) {
            lp.flags = if (adb) lp.flags or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
            else lp.flags and WindowManager.LayoutParams.FLAG_SPLIT_TOUCH.inv()
            updateWin(v, lp)
        }
        syncArm()
        if (buttons.any { it.kind == Kind.MAIN && it.passThru }) applyModeUi(animate = false)
    }

    /** Tự động gắn lại mọi giao diện bị hệ thống gỡ mất (máy nóng, lag, thiếu RAM...). */
    private fun ensureAttached() {
        for ((v, lp) in live.toList()) {
            if (!v.isAttachedToWindow) {
                try {
                    wm.addView(v, lp)
                } catch (_: Exception) {
                }
            }
        }
    }

    // ---- vị trí nút nhớ RIÊNG cho hướng dọc / ngang (không bị kẹt vị trí của hướng kia)
    private var lastLand: Boolean? = null

    private fun isLand(): Boolean {
        val (w, h) = screenSize()
        return w > h
    }

    private fun storePos(b: MacroButton, land: Boolean) {
        if (land) {
            b.lX = b.x
            b.lY = b.y
        } else {
            b.pX = b.x
            b.pY = b.y
        }
    }

    /** Ghi vị trí đang hiển thị vào ô của hướng hiện tại. */
    private fun syncSlots() {
        val land = lastLand ?: return
        for (b in buttons) storePos(b, land)
    }

    /**
     * Gọi mỗi lần dựng lại giao diện: lưu vị trí của hướng cũ, rồi nạp vị trí của hướng mới.
     * Hướng mới chưa từng đặt -> suy ra theo TỈ LỆ từ hướng kia (rồi nhớ lại, kéo đi đâu thì nhớ đó).
     */
    private fun applyOrientation(sw: Int, sh: Int) {
        val land = sw > sh
        val prev = lastLand
        if (prev != null) for (b in buttons) storePos(b, prev)
        val sho = minOf(sw, sh).toFloat()
        val lng = maxOf(sw, sh).toFloat()
        for (b in buttons) {
            if (land) {
                if (b.lX >= 0 && b.lY >= 0) {
                    b.x = b.lX
                    b.y = b.lY
                } else if (b.pX >= 0 && b.pY >= 0) {
                    b.x = Math.round(b.pX * lng / sho)
                    b.y = Math.round(b.pY * sho / lng)
                }
            } else {
                if (b.pX >= 0 && b.pY >= 0) {
                    b.x = b.pX
                    b.y = b.pY
                } else if (b.lX >= 0 && b.lY >= 0) {
                    b.x = Math.round(b.lX * sho / lng)
                    b.y = Math.round(b.lY * lng / sho)
                }
            }
            storePos(b, land)
        }
        lastLand = land
    }

    private fun persist() {
        syncSlots()
        Store.saveCurrent(this, buttons)
    }

    /** Độ trong hiển thị: khi chạy theo đúng cài đặt (có thể 0); lúc setup luôn hiện tối thiểu 20%. */
    private fun viewAlpha(b: MacroButton): Float =
        (if (mode == Mode.EDIT) maxOf(b.alphaPct, 20) else b.alphaPct) / 100f

    /** Đánh lại số 1,2,3... RIÊNG cho từng main. */
    private fun renumber(mainNo: Int) {
        buttons.filter { it.kind == Kind.NUM && it.mainNo == mainNo }
            .sortedWith(compareBy({ it.number }, { it.id }))
            .forEachIndexed { i, x -> x.number = i + 1 }
    }

    private fun normalize() {
        for (m in buttons.filter { it.kind == Kind.NUM }.map { it.mainNo }.distinct()) renumber(m)
    }

    private fun mainNumbers(): List<Int> =
        buttons.filter { it.kind == Kind.MAIN }.map { it.number }.sorted()

    private fun targetMain(): Int {
        val mains = mainNumbers()
        return if (addTargetMain in mains) addTargetMain else (mains.firstOrNull() ?: 0)
    }

    /** Chuyển nút số sang main khác; nó thành số cuối của main mới, main cũ được đánh số lại. */
    private fun relink(b: MacroButton, newMain: Int) {
        val old = b.mainNo
        if (old == newMain) return
        b.mainNo = newMain
        b.number = (buttons.filter { it.kind == Kind.NUM && it.mainNo == newMain && it !== b }
            .maxOfOrNull { it.number } ?: 0) + 1
        renumber(old)
        if (newMain > 0) addTargetMain = newMain
        persist()
        for (v in btnViews.values) v.invalidate()
        applyFocus()
        backdrop?.invalidate()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- dựng giao diện

    /** Báo GhostTouch bật "tiếp quản sớm" khi đang chạy macro bằng Gỡ lỗi WiFi (kèm hướng + cỡ màn hình). */
    private fun syncArm() {
        val on = mode == Mode.RUN && Store.tapMode(this) == TAP_ADB
        val (sw, sh) = screenSize()
        AdbClient.arm(on, displayRotation(), sw, sh)
    }

    /** Dựng lại TOÀN BỘ giao diện nổi. Chỉ dùng khi khởi động, xoay màn hình, hoặc khi giao diện bị mất. */
    private fun rebuildAll() {
        removeAll()
        builtTapMode = Store.tapMode(this)
        builtType = overlayType()
        builtSize = screenSize()
        missTicks = 0
        syncArm()
        if (mode == Mode.OFF) return
        val (sw, sh) = screenSize()
        applyOrientation(sw, sh)
        // thứ tự cửa sổ (dưới -> trên): nền, các nút, bảng, bong bóng. Nền + bảng luôn có sẵn (ẩn khi chạy macro)
        // nên vào/ra setup chỉ đổi kích thước / hiển thị, không phải gắn lại cửa sổ.
        addBackdrop()
        addCrosshair()
        for (b in buttons) addButton(b, sw, sh)
        addPanel(sw, sh)
        addBubble(sw, sh)
        applyModeUi(animate = false)
    }

    /** Chuyển RUN <-> EDIT tại chỗ: đổi cờ cảm ứng / hiển thị của các cửa sổ đang có, không dựng lại. */
    private fun applyModeUi(animate: Boolean = true) {
        val editing = mode == Mode.EDIT
        syncArm()
        setBackdropActive(editing)
        for (b in buttons) {
            val v = btnViews[b.id] ?: continue
            val lp = btnLps[b.id] ?: continue
            configureButton(b, v, lp, update = true)
        }
        val cv = crossView
        val cl = crossLp
        if (cv != null && cl != null && cl.type != passType()) {
            cl.type = passType()
            applyTouchable(cl, false)
            retypeWindow(cv, cl)
        }
        if (retyped) {
            // cửa sổ vừa gắn lại nằm đè lên bảng / bong bóng -> đưa 2 thứ này lên trên lại
            retyped = false
            val pr = panelRoot
            val pl = panelLp
            if (pr != null && pl != null) refront(pr, pl)
            val bv = bubble
            val bl = bubbleLp
            if (bv != null && bl != null) refront(bv, bl)
        }
        bubble?.editing = editing
        setPanelShown(editing, animate)
    }

    // ---------------------------------------------------------------- tâm ảo + vòng tròn

    private fun crossSidePx(): Int {
        val c = if (Store.crossOn(this)) dp(Store.crossSize(this)) else 0
        val r = if (Store.ringOn(this)) dp(Store.ringSize(this)) else 0
        return maxOf(c, r, dp(8)) + dp(6)
    }

    private fun styleCross(v: CrosshairView) {
        v.showCross = Store.crossOn(this)
        v.crossPx = dp(Store.crossSize(this)).toFloat()
        v.crossAlpha = Store.crossAlpha(this) / 100f
        v.showRing = Store.ringOn(this)
        v.ringPx = dp(Store.ringSize(this)).toFloat()
        v.strokePx = dp(Store.ringStroke(this)).toFloat()
        v.ringAlpha = Store.ringAlpha(this) / 100f
        v.invalidate()
    }

    private fun placeCross(lp: WindowManager.LayoutParams) {
        val (sw, sh) = screenSize()
        val side = crossSidePx()
        lp.width = side
        lp.height = side
        lp.x = sw / 2 - side / 2
        lp.y = sh / 2 - side / 2
    }

    private fun addCrosshair() {
        if (!Store.crossOn(this) && !Store.ringOn(this)) return
        val v = CrosshairView(this)
        styleCross(v)
        val lp = baseLp(crossSidePx(), crossSidePx(), false, passType()) // không nhận chạm, cú chạm đi xuyên xuống game
        placeCross(lp)
        crossView = v
        crossLp = lp
        addOverlay(v, lp)
    }

    /** Cài đặt tâm / vòng đổi: chỉ gắn, gỡ hoặc vẽ lại đúng cửa sổ tâm, không đụng phần còn lại. */
    private fun updateCrosshair() {
        val want = Store.crossOn(this) || Store.ringOn(this)
        val v = crossView
        val lp = crossLp
        if (!want) {
            if (v != null) {
                live.removeAll { it.first === v }
                try {
                    wm.removeViewImmediate(v)
                } catch (_: Exception) {
                }
            }
            crossView = null
            crossLp = null
            return
        }
        if (v == null || lp == null) {
            addCrosshair()
            crossLp?.let { keepChromeOnTop(it) }
            return
        }
        styleCross(v)
        placeCross(lp)
        updateWin(v, lp)
    }

    private fun toggleRow(name: String, on: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(label(name, 12f, Theme.MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = ToggleSwitch(this)
        sw.checked = on
        sw.onToggle = {
            setPanelFocusable(false)
            onChange(it)
        }
        row.addView(sw, LinearLayout.LayoutParams(dp(46), dp(26)))
        return row
    }

    private fun buildCrossSection(c: LinearLayout) {
        val lpRow = {
            val l = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            l.topMargin = dp(6)
            l
        }
        c.addView(toggleRow("Tâm ảo", Store.crossOn(this)) {
            Store.setCrossOn(this, it)
            updateCrosshair()
            refreshPanel()
        }, lpRow())
        if (Store.crossOn(this)) {
            c.addView(sliderRow("Kích thước", 8, 100, Store.crossSize(this), { "${it}dp" }, stepKey = "cross_size") { v, done ->
                Store.setCrossSize(this, v)
                updateCrosshair()
            })
            c.addView(sliderRow("Độ mờ", 5, 100, Store.crossAlpha(this), { "$it%" }, stepKey = "cross_alpha") { v, done ->
                Store.setCrossAlpha(this, v)
                updateCrosshair()
            })
        }
        c.addView(toggleRow("Vòng quanh tâm", Store.ringOn(this)) {
            Store.setRingOn(this, it)
            updateCrosshair()
            refreshPanel()
        }, lpRow())
        if (Store.ringOn(this)) {
            c.addView(sliderRow("Độ to nhỏ", 30, 500, Store.ringSize(this), { "${it}dp" }) { v, done ->
                Store.setRingSize(this, v)
                updateCrosshair()
            })
            c.addView(sliderRow("Nét vòng", 1, 16, Store.ringStroke(this), { "${it}dp" }) { v, done ->
                Store.setRingStroke(this, v)
                updateCrosshair()
            })
            c.addView(sliderRow("Độ mờ vòng", 5, 100, Store.ringAlpha(this), { "$it%" }) { v, done ->
                Store.setRingAlpha(this, v)
                updateCrosshair()
            })
        }
    }

    private fun addBackdrop() {
        val v = BackdropView(this)
        // trạng thái chờ: 1x1 px, không nhận chạm (không che gì cả). Vào setup thì phóng ra toàn màn hình.
        val lp = baseLp(1, 1, false)
        lp.x = 0
        lp.y = 0
        v.visibility = View.INVISIBLE
        v.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                setPanelFocusable(false)
                if (selectedId != -1) deselect()
            }
            true
        }
        backdrop = v
        backdropLp = lp
        addOverlay(v, lp)
    }

    private fun setBackdropActive(on: Boolean) {
        val v = backdrop ?: return
        val lp = backdropLp ?: return
        if (on) {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.MATCH_PARENT
            applyTouchable(lp, true)
            v.visibility = View.VISIBLE
        } else {
            lp.width = 1
            lp.height = 1
            applyTouchable(lp, false)
            v.visibility = View.INVISIBLE
        }
        updateWin(v, lp)
        v.invalidate()
    }

    private fun addButton(b: MacroButton, sw: Int, sh: Int) {
        val size = dp(b.sizeDp)
        val lp = baseLp(size, size, true, buttonType(b))
        b.x = b.x.coerceIn(size / 2, maxOf(size / 2, sw - size / 2))
        b.y = b.y.coerceIn(size / 2, maxOf(size / 2, sh - size / 2))
        lp.x = b.x - size / 2
        lp.y = b.y - size / 2

        val v = BtnView(this, b)
        configureButton(b, v, lp, update = false)
        btnViews[b.id] = v
        btnLps[b.id] = lp
        addOverlay(v, lp)
    }

    /** Thêm 1 nút mới khi đang chạy: chỉ gắn thêm đúng 1 cửa sổ + hiệu ứng hiện ra, không đụng các nút khác. */
    private fun addButtonLive(b: MacroButton) {
        val (sw, sh) = screenSize()
        addButton(b, sw, sh)
        val v = btnViews[b.id]
        val lp = btnLps[b.id]
        if (v != null) {
            v.scaleX = 0.5f
            v.scaleY = 0.5f
            v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        if (lp != null) keepChromeOnTop(lp)
        backdrop?.invalidate()
    }

    /** Nút mới được gắn SAU bảng/bong bóng nên nằm đè lên; nếu chồng nhau thì đưa bảng + bong bóng lên trên lại. */
    private fun keepChromeOnTop(lp: WindowManager.LayoutParams) {
        val r = Rect(lp.x, lp.y, lp.x + lp.width, lp.y + lp.height)
        var moved = false
        val pr = panelRoot
        val pl = panelLp
        if (pr != null && pl != null && mode == Mode.EDIT) {
            val pRect = Rect(pl.x, pl.y, pl.x + pl.width, pl.y + maxOf(pr.height, dp(80)))
            if (Rect.intersects(r, pRect)) {
                setPanelFocusable(false)
                refront(pr, pl)
                moved = true
            }
        }
        val bv = bubble
        val bl = bubbleLp
        if (bv != null && bl != null) {
            val bRect = Rect(bl.x, bl.y, bl.x + bl.width, bl.y + bl.height)
            if (moved || Rect.intersects(r, bRect)) refront(bv, bl)
        }
    }

    private fun refront(v: View, lp: WindowManager.LayoutParams) {
        try {
            wm.removeViewImmediate(v)
        } catch (_: Exception) {
        }
        try {
            wm.addView(v, lp)
        } catch (_: Exception) {
        }
    }

    private fun mainPress(b: MacroButton, bv: BtnView?) {
        bv?.pressedFx = true
        when (b.trigger) {
            TRIG_PRESS -> runChain(b.number)
            TRIG_HOLD -> runChain(b.number, true)
        }
    }

    private fun mainRelease(b: MacroButton, bv: BtnView?) {
        bv?.pressedFx = false
        when (b.trigger) {
            TRIG_RELEASE -> runChain(b.number)
            TRIG_HOLD -> cancelChain() // thả tay -> dừng lặp
        }
    }

    // ---- nút main "cảm ứng xuyên": cửa sổ không nhận chạm, nên kích hoạt bằng vị trí ngón THẬT đọc từ /dev/input (GhostTouch)
    private val fingerMain = HashMap<Int, Int>() // tracking id -> id nút main đang bị ngón đó giữ
    private val fingerGone = HashMap<Int, Long>() // tracking id vừa biến mất -> thời điểm; chờ ngắn rồi mới coi là nhấc tay
    private val FINGER_GRACE_MS = 50L

    private val fingerSweep = object : Runnable {
        override fun run() {
            sweepFingers()
        }
    }

    /** Thả những ngón đã biến mất quá thời gian chờ. */
    private fun sweepFingers() {
        val now = SystemClock.uptimeMillis()
        val it = fingerGone.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value < FINGER_GRACE_MS) continue
            it.remove()
            val bid = fingerMain.remove(e.key) ?: continue
            val b = buttons.firstOrNull { x -> x.id == bid } ?: continue
            mainRelease(b, btnViews[b.id])
        }
        if (fingerGone.isNotEmpty()) handler.postDelayed(fingerSweep, 20)
    }

    private fun onFingers(pts: List<IntArray>) {
        if (mode != Mode.RUN) {
            if (fingerMain.isNotEmpty()) releaseAllFingers()
            return
        }
        val (sw, sh) = screenSize()
        val rot = displayRotation() and 3
        val seen = HashSet<Int>()
        for (p in pts) {
            val tid = p[0]
            seen.add(tid)
            fingerGone.remove(tid) // ngón quay lại trong thời gian chờ -> vẫn là cú giữ cũ
            if (fingerMain.containsKey(tid)) continue
            val rx = p[1] / 1000.0
            val ry = p[2] / 1000.0
            val nx: Double
            val ny: Double
            when (rot) {
                1 -> { nx = ry; ny = 1.0 - rx }
                2 -> { nx = 1.0 - rx; ny = 1.0 - ry }
                3 -> { nx = 1.0 - ry; ny = rx }
                else -> { nx = rx; ny = ry }
            }
            val x = nx * sw
            val y = ny * sh
            val hit = buttons.firstOrNull {
                isPass(it) && hypot(x - it.x, y - it.y) <= dp(it.sizeDp) / 2.0
            } ?: continue
            // ngón mới xuất hiện đúng trong nút vừa có ngón biến mất (do tiếp quản / gửi lại ngón) -> nhận nối tiếp, KHÔNG bấm lại
            val old = fingerGone.keys.firstOrNull { fingerMain[it] == hit.id }
            if (old != null) {
                fingerGone.remove(old)
                fingerMain.remove(old)
                fingerMain[tid] = hit.id
                continue
            }
            fingerMain[tid] = hit.id
            mainPress(hit, btnViews[hit.id])
        }
        val now = SystemClock.uptimeMillis()
        var added = false
        for (k in fingerMain.keys) {
            if (k !in seen && !fingerGone.containsKey(k)) {
                fingerGone[k] = now
                added = true
            }
        }
        if (added) {
            handler.removeCallbacks(fingerSweep)
            handler.postDelayed(fingerSweep, 20)
        }
    }

    private fun releaseAllFingers() {
        val ids = fingerMain.values.toList()
        fingerMain.clear()
        fingerGone.clear()
        handler.removeCallbacks(fingerSweep)
        for (id in ids) {
            val b = buttons.firstOrNull { it.id == id } ?: continue
            mainRelease(b, btnViews[b.id])
        }
    }

    // ---- chế độ "tập trung": đang chỉnh / kéo nút của main nào thì ẩn các NÚT SỐ của main khác (nút main thì vẫn hiện)
    private var dragId = -1

    private fun focusMain(): Int {
        if (mode != Mode.EDIT) return -1
        val id = if (dragId != -1) dragId else selectedId
        val b = buttons.firstOrNull { it.id == id } ?: return -1
        val m = if (b.kind == Kind.MAIN) b.number else b.mainNo
        return if (m > 0) m else -1
    }

    private fun hiddenByFocus(b: MacroButton, f: Int = focusMain()): Boolean =
        f > 0 && b.kind == Kind.NUM && b.mainNo > 0 && b.mainNo != f

    /** Cập nhật ẩn / hiện các nút theo nút đang tương tác. Ra khỏi chỉnh sửa -> hiện lại tất cả. */
    private fun applyFocus() {
        val f = focusMain()
        for (b in buttons) {
            val v = btnViews[b.id] ?: continue
            val lp = btnLps[b.id] ?: continue
            val hide = hiddenByFocus(b, f)
            val wantVis = if (hide) View.GONE else View.VISIBLE
            if (v.visibility == wantVis) continue
            v.visibility = wantVis
            applyTouchable(lp, !hide && (mode == Mode.EDIT || (b.kind == Kind.MAIN && !isPass(b))))
            updateWin(v, lp)
        }
        backdrop?.invalidate()
    }

    /** Gán lại cảm ứng / độ trong / nhãn cho một nút theo chế độ hiện tại (RUN hoặc EDIT). */
    private fun configureButton(b: MacroButton, v: BtnView, lp: WindowManager.LayoutParams, update: Boolean) {
        val editing = mode == Mode.EDIT
        val wantType = buttonType(b)
        val typeChanged = lp.type != wantType
        lp.type = wantType
        val hide = hiddenByFocus(b)
        applyTouchable(lp, !hide && (editing || (b.kind == Kind.MAIN && !isPass(b))))
        v.visibility = if (hide) View.GONE else View.VISIBLE
        v.alpha = viewAlpha(b)
        v.showTag = editing
        v.hilite = editing && b.id == selectedId
        v.pressedFx = false
        v.setOnTouchListener(null)

        if (editing) {
            v.setOnTouchListener(
                DragListener(
                    lp = lp,
                    wm = wm,
                    slop = dp(6),
                    limit = {
                        val s = screenSize()
                        Pair(s.first - lp.width, s.second - lp.height)
                    },
                    onMove = {
                        b.x = lp.x + lp.width / 2
                        b.y = lp.y + lp.height / 2
                        if (dragId != b.id) {
                            dragId = b.id
                            applyFocus()
                        }
                        backdrop?.invalidate()
                    },
                    onTap = { selectButton(b.id) },
                    onDragEnd = {
                        dragId = -1
                        persist()
                        applyFocus()
                    }
                )
            )
        } else if (b.kind == Kind.MAIN) {
            // Chế độ "tiếp quản ngón thật": khi macro chạm, hệ thống HUỶ ngón thật 1 lần rồi app bơm lại đúng ngón đó.
            // Nên nút main sẽ thấy CANCEL rồi DOWN lặp lại ngay -> bỏ qua cặp đó, đừng dừng/khởi động lại chuỗi.
            var held = false
            var cancelAt = 0L
            v.setOnTouchListener { view, e ->
                val bv = view as BtnView
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val replay = held && SystemClock.uptimeMillis() - cancelAt < 400L
                        held = true
                        if (replay) return@setOnTouchListener true
                        mainPress(b, bv)
                    }
                    MotionEvent.ACTION_UP -> {
                        held = false
                        mainRelease(b, bv)
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (held && AdbClient.takeover && Store.tapMode(this) == TAP_ADB) {
                            // do tiếp quản: giữ nguyên trạng thái, chờ DOWN lặp lại / UP thật
                            cancelAt = SystemClock.uptimeMillis()
                            return@setOnTouchListener true
                        }
                        held = false
                        bv.pressedFx = false
                        if (b.trigger == TRIG_HOLD) cancelChain()
                    }
                }
                true
            }
        }
        if (update) {
            if (typeChanged) retypeWindow(v, lp) else updateWin(v, lp)
        }
    }

    private fun addBubble(sw: Int, sh: Int) {
        val size = dp(54)
        val lp = baseLp(size, size, true)
        val saved = Store.bubblePos(this, sw > sh)
        lp.x = (saved?.first ?: dp(10)).coerceIn(0, maxOf(0, sw - size))
        lp.y = (saved?.second ?: (sh / 3)).coerceIn(0, maxOf(0, sh - size))
        val v = BubbleView(this, logoBmp)
        v.editing = mode == Mode.EDIT
        v.setOnTouchListener(
            DragListener(
                lp = lp,
                wm = wm,
                slop = dp(6),
                limit = {
                    val s = screenSize()
                    Pair(s.first - size, s.second - size)
                },
                onMove = {},
                onTap = { onBubbleTap() },
                onDragEnd = { Store.setBubblePos(this, lp.x, lp.y, isLand()) }
            )
        )
        bubble = v
        bubbleLp = lp
        addOverlay(v, lp)
    }

    private fun onBubbleTap() {
        if (mode == Mode.RUN) enterEdit() else if (mode == Mode.EDIT) exitEdit()
    }

    private fun enterEdit() {
        cancelChain()
        mode = Mode.EDIT
        selectedId = -1
        listOpen = false
        applyModeUi()
    }

    private fun exitEdit() {
        setPanelFocusable(false)
        mode = Mode.RUN
        selectedId = -1
        dragId = -1
        listOpen = false
        persist()
        applyModeUi()
    }

    // ---------------------------------------------------------------- nền + đường nối main -> 1 -> 2 -> 3

    inner class BackdropView(ctx: Context) : View(ctx) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Theme.ACCENT
            alpha = 190
        }
        private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Theme.ACCENT
            alpha = 220
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.parseColor("#55000000"))
            line.strokeWidth = 3f * density
            line.pathEffect = DashPathEffect(floatArrayOf(14f * density, 10f * density), 0f)
            val f = focusMain()
            for (m in buttons.filter { it.kind == Kind.MAIN }) {
                if (f > 0 && m.number != f) continue // đang tập trung 1 main: ẩn luôn đường nối của main khác
                val seq = buttons
                    .filter { it.kind == Kind.NUM && it.mainNo == m.number }
                    .sortedBy { it.number }
                var prev: MacroButton = m
                for (n in seq) {
                    drawLink(c, prev, n)
                    prev = n
                }
            }
        }

        private fun drawLink(c: Canvas, a: MacroButton, b: MacroButton) {
            val dx = (b.x - a.x).toFloat()
            val dy = (b.y - a.y).toFloat()
            val len = hypot(dx, dy)
            val ra = dp(a.sizeDp) / 2f * 0.84f
            val rb = dp(b.sizeDp) / 2f * 0.84f
            if (len < ra + rb + 6f * density) return
            val ux = dx / len
            val uy = dy / len
            val sx = a.x + ux * ra
            val sy = a.y + uy * ra
            val ex = b.x - ux * rb
            val ey = b.y - uy * rb
            c.drawLine(sx, sy, ex, ey, line)
            // mũi tên
            val s = 9f * density
            val px = -uy
            val py = ux
            val path = Path()
            path.moveTo(ex, ey)
            path.lineTo(ex - ux * s * 1.6f + px * s, ey - uy * s * 1.6f + py * s)
            path.lineTo(ex - ux * s * 1.6f - px * s, ey - uy * s * 1.6f - py * s)
            path.close()
            c.drawPath(path, head)
        }
    }

    // ---------------------------------------------------------------- bảng điều khiển (GUI setup)

    inner class PanelLayout(ctx: Context) : LinearLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) setPanelFocusable(false)
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        return t
    }

    private fun actionBtn(text: String, filled: Boolean, danger: Boolean = false, onClick: () -> Unit): TextView {
        val t = label(text, 13f, if (filled) Color.parseColor("#0B120A") else if (danger) Theme.DANGER else Theme.ACCENT, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(8), 0, dp(8), 0)
        val stroke = if (danger) Theme.DANGER else Theme.STROKE
        t.background = if (filled) {
            roundedBg(Theme.ACCENT, dp(12).toFloat())
        } else {
            roundedBg(Theme.FIELD, dp(12).toFloat(), stroke, dp(1))
        }
        t.isClickable = true
        t.setOnClickListener {
            setPanelFocusable(false)
            onClick()
        }
        return t
    }

    private fun weighted(h: Int, left: Int = 0, right: Int = 0): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, h, 1f)
        lp.setMargins(left, 0, right, 0)
        return lp
    }

    private fun addPanel(sw: Int, sh: Int) {
        val width = minOf(sw - dp(16), dp(400))
        panelW = width
        val lp = baseLp(width, WindowManager.LayoutParams.WRAP_CONTENT, true)
        lp.x = if (panelX >= 0) panelX.coerceIn(0, maxOf(0, sw - width)) else (sw - width) / 2
        lp.y = if (panelY >= 0) panelY.coerceIn(0, maxOf(0, sh - dp(80))) else dp(8)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN

        val root = PanelLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = roundedBg(Theme.PANEL, dp(20).toFloat(), Theme.STROKE, dp(1))
        root.setPadding(dp(12), dp(8), dp(12), dp(12))

        // ---- thanh tiêu đề (kéo để di chuyển bảng)
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(0, 0, 0, dp(6))

        val logo = ImageView(this)
        logo.setImageResource(R.drawable.logo_bubble)
        header.addView(logo, LinearLayout.LayoutParams(dp(28), dp(28)))

        val title = label("MACRO TOUCH", 15f, Theme.ACCENT, true)
        title.letterSpacing = 0.06f
        title.setPadding(dp(8), 0, 0, 0)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val close = label("✕", 16f, Theme.TEXT, true)
        close.gravity = Gravity.CENTER
        close.background = roundedBg(Color.parseColor("#33FF5A5A"), dp(17).toFloat(), Theme.DANGER, dp(1))
        close.setOnClickListener {
            setPanelFocusable(false)
            exitEdit()
        }
        header.addView(close, LinearLayout.LayoutParams(dp(34), dp(34)))

        header.setOnTouchListener(
            DragListener(
                lp = lp,
                wm = wm,
                slop = dp(4),
                target = root,
                limit = {
                    val s = screenSize()
                    Pair(s.first - width, s.second - dp(80))
                },
                onMove = {
                    panelX = lp.x
                    panelY = lp.y
                },
                onTap = {},
                onDragEnd = {
                    panelX = lp.x
                    panelY = lp.y
                }
            )
        )
        root.addView(header)

        val scroll = MaxHeightScroll(this, sh - dp(110))
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        scroll.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(scroll)

        panelRoot = root
        panelLp = lp
        panelContent = content
        panelTitle = title
        refreshPanel()
        addOverlay(root, lp)
    }

    /** Hiện / ẩn bảng setup tại chỗ (ẩn = cửa sổ 1x1 không nhận chạm), không gỡ / gắn lại cửa sổ. */
    private fun setPanelShown(on: Boolean, animate: Boolean) {
        val lp = panelLp ?: return
        val root = panelRoot ?: return
        if (on) {
            lp.width = panelW
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            applyTouchable(lp, true)
            root.visibility = View.VISIBLE
            refreshPanel()
            if (animate) {
                root.alpha = 0f
                root.translationY = -dp(12).toFloat()
                root.animate().alpha(1f).translationY(0f).setDuration(160).start()
            } else {
                root.alpha = 1f
                root.translationY = 0f
            }
        } else {
            setPanelFocusable(false)
            root.animate().cancel()
            lp.width = 1
            lp.height = 1
            applyTouchable(lp, false)
            root.visibility = View.GONE
        }
        updateWin(root, lp)
    }

    private fun refreshPanel() {
        val content = panelContent ?: return
        val b = buttons.firstOrNull { it.id == selectedId }
        if (b == null) {
            selectedId = -1
            panelTitle?.text = "MACRO TOUCH  ·  SETUP"
            buildAddContent(content)
        } else {
            panelTitle?.text = if (b.kind == Kind.MAIN) "Chỉnh main${b.number}" else if (b.mainNo > 0) "Chỉnh nút ${b.number}  ·  main${b.mainNo}" else "Chỉnh nút ${b.number}"
            buildEditContent(content, b)
        }
    }

    // ---- chế độ chạm: Trợ năng <-> Gỡ lỗi WiFi (đổi nhanh ngay trên bảng nổi)

    private fun setTapMode(m: Int) {
        Store.setTapMode(this, m)
        cancelChain()
        if (m == TAP_ADB) AdbClient.connect(applicationContext) else AdbClient.disconnect()
        onTapModeChanged() // đổi cờ cửa sổ tại chỗ, không dựng lại giao diện
        refreshPanel()
    }

    private fun numField(hint: String, value: String, onText: (String) -> Unit): EditText =
        textField(hint, value, InputType.TYPE_CLASS_NUMBER, 0, onText)

    private fun textField(
        hint: String, value: String, type: Int, maxLen: Int, onText: (String) -> Unit
    ): EditText {
        val et = EditText(this)
        if (maxLen > 0) et.filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(maxLen))
        et.setText(value)
        et.hint = hint
        et.setHintTextColor(Theme.MUTED)
        et.setTextColor(Theme.TEXT)
        et.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        et.setSingleLine(true)
        et.inputType = type
        et.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        et.setPadding(dp(10), 0, dp(10), 0)
        et.background = roundedBg(Theme.FIELD, dp(12).toFloat(), Theme.STROKE, dp(1))
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c1: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c1: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onText(s?.toString() ?: "")
            }
        })
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                setPanelFocusable(false)
                true
            } else false
        }
        et.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                nameInput = et
                setPanelFocusable(true)
                handler.postDelayed({
                    et.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
                }, 150)
            }
            false
        }
        return et
    }

    private fun buildModeSection(c: LinearLayout) {
        val cur = Store.tapMode(this)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(label("Chế độ chạm", 12f, Theme.MUTED), LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(actionBtn("Trợ năng", cur == TAP_ACC) { setTapMode(TAP_ACC) }, weighted(dp(36), 0, dp(4)))
        row.addView(actionBtn("Gỡ lỗi WiFi", cur == TAP_ADB) { setTapMode(TAP_ADB) }, weighted(dp(36), dp(4), 0))
        c.addView(row)
        if (cur != TAP_ADB) return

        val st = label(AdbClient.statusText(), 12f, AdbClient.statusColor(), true)
        st.setPadding(dp(2), dp(6), dp(2), dp(4))
        c.addView(st)
        if (!AdbClient.supported()) return

        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        val off = AdbClient.state == AdbClient.State.OFF
        btns.addView(actionBtn("Kết nối", off) { AdbClient.connect(applicationContext) }, weighted(dp(36), 0, dp(2)))
        btns.addView(actionBtn("Ngắt", false, true) { AdbClient.disconnect() }, weighted(dp(36), dp(2), dp(2)))
        btns.addView(
            actionBtn(if (pairOpen) "Ghép cặp ▴" else "Ghép cặp ▾", false) {
                pairOpen = !pairOpen
                refreshPanel()
            },
            weighted(dp(36), dp(2), 0)
        )
        c.addView(btns)

        if (!pairOpen) return

        val hint = label(
            "Ghép cặp (làm 1 lần): vào Tùy chọn nhà phát triển → Gỡ lỗi không dây → \"Ghép nối thiết bị bằng mã\" " +
                    "và để hộp thoại đó MỞ. Chỉ cần nhập MÃ 6 số (app tự tìm cổng). " +
                    "Gõ ở ô dưới, hoặc bấm \"Gửi thông báo nhập mã\" rồi gõ mã ngay trong thông báo.",
            11f, Theme.MUTED
        )
        hint.setPadding(dp(2), dp(8), dp(2), dp(4))
        c.addView(hint)

        val pr = LinearLayout(this)
        pr.orientation = LinearLayout.HORIZONTAL
        pr.gravity = Gravity.CENTER_VERTICAL
        val codeEt = numField("Mã 6 số", pairCode) { pairCode = it }
        val l2 = LinearLayout.LayoutParams(0, dp(40), 1f)
        l2.setMargins(0, 0, dp(6), 0)
        pr.addView(codeEt, l2)
        pr.addView(actionBtn("Ghép", true) { doPair() }, LinearLayout.LayoutParams(dp(72), dp(40)))
        c.addView(pr)

        val notif = actionBtn("Gửi thông báo nhập mã", false) { sendPairNotif() }
        val nl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))
        nl.topMargin = dp(6)
        c.addView(notif, nl)

        val dev = actionBtn("Mở Tùy chọn nhà phát triển", false) {
            try {
                val i = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } catch (_: Exception) {
                toast("Không mở được Tùy chọn nhà phát triển")
            }
        }
        val dl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))
        dl.topMargin = dp(6)
        c.addView(dev, dl)
    }

    private fun sendPairNotif() {
        if (PairNotif.show(applicationContext)) {
            toast("Đã gửi thông báo · kéo thanh thông báo xuống, bấm \"Nhập mã\"")
        } else {
            toast("Cần cấp quyền thông báo: mở app chính → Chế độ chạm → bấm \"Gửi thông báo nhập mã\" 1 lần")
        }
    }

    private fun doPair() {
        val code = pairCode.trim()
        if (code.length < 6) {
            toast("Nhập mã 6 số trên hộp thoại ghép nối")
            return
        }
        toast("Đang ghép cặp...")
        AdbClient.pair(applicationContext, code) { ok, msg ->
            toast(msg)
            if (ok) {
                pairOpen = false
                pairCode = ""
                AdbClient.connect(applicationContext)
            }
            if (mode == Mode.EDIT && selectedId == -1) refreshPanel()
        }
    }

    // ---- nội dung: thêm nút / lưu / chọn macro

    private fun buildAddContent(c: LinearLayout) {
        c.removeAllViews()
        val rowH = dp(42)

        buildModeSection(c)
        c.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        buildCrossSection(c)
        c.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))

        val r1 = LinearLayout(this)
        r1.orientation = LinearLayout.HORIZONTAL
        r1.addView(actionBtn("+  Nút macro", true) { addNum() }, weighted(rowH, 0, dp(4)))
        r1.addView(actionBtn("◎  Nút trung tâm", false) { addMain() }, weighted(rowH, dp(4), 0))
        c.addView(r1)

        // chọn main để nối nút số vào (mỗi main có số 1, 2, 3... riêng)
        val mains = mainNumbers()
        val tm = targetMain()
        val r1b = LinearLayout(this)
        r1b.orientation = LinearLayout.HORIZONTAL
        r1b.gravity = Gravity.CENTER_VERTICAL
        r1b.addView(label("Nút số thuộc", 12f, Theme.MUTED), LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
        r1b.addView(
            actionBtn(if (tm == 0) "Chưa có main — tạo nút trung tâm" else "main$tm   ⟳", false) {
                if (mains.size > 1) {
                    addTargetMain = mains[(mains.indexOf(tm) + 1) % mains.size]
                    refreshPanel()
                }
            },
            LinearLayout.LayoutParams(0, dp(36), 1f)
        )
        val r1blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        r1blp.topMargin = dp(8)
        c.addView(r1b, r1blp)

        // tên macro + lưu
        val r2 = LinearLayout(this)
        r2.orientation = LinearLayout.HORIZONTAL
        r2.gravity = Gravity.CENTER_VERTICAL
        val et = EditText(this)
        et.setText(nameDraft)
        et.hint = "Tên macro..."
        et.setHintTextColor(Theme.MUTED)
        et.setTextColor(Theme.TEXT)
        et.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        et.setSingleLine(true)
        et.inputType = InputType.TYPE_CLASS_TEXT
        et.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        et.setPadding(dp(12), 0, dp(12), 0)
        et.background = roundedBg(Theme.FIELD, dp(12).toFloat(), Theme.STROKE, dp(1))
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c1: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c1: Int) {}
            override fun afterTextChanged(s: Editable?) {
                nameDraft = s?.toString() ?: ""
            }
        })
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                setPanelFocusable(false)
                true
            } else false
        }
        et.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                setPanelFocusable(true)
                handler.postDelayed({
                    et.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
                }, 150)
            }
            false
        }
        nameInput = et
        val r2lp = LinearLayout.LayoutParams(0, rowH, 1f)
        r2lp.setMargins(0, 0, dp(6), 0)
        r2.addView(et, r2lp)
        r2.addView(actionBtn("Lưu macro", true) { saveMacro() }, LinearLayout.LayoutParams(dp(104), rowH))
        val r2wrap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        r2wrap.topMargin = dp(8)
        c.addView(r2, r2wrap)

        // dropdown chọn macro
        val names = Store.macroNames(this)
        val dd = label(
            (if (names.isEmpty()) "Chưa có macro nào đã lưu" else "Chọn macro đã lưu") + (if (listOpen) "   ▴" else "   ▾"),
            13f, Theme.TEXT
        )
        dd.gravity = Gravity.CENTER_VERTICAL
        dd.setPadding(dp(12), 0, dp(12), 0)
        dd.background = roundedBg(Theme.FIELD, dp(12).toFloat(), Theme.STROKE, dp(1))
        dd.setOnClickListener {
            setPanelFocusable(false)
            if (names.isNotEmpty()) {
                listOpen = !listOpen
                refreshPanel()
            }
        }
        val r3 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH)
        r3.topMargin = dp(8)
        c.addView(dd, r3)

        if (listOpen && names.isNotEmpty()) {
            val list = LinearLayout(this)
            list.orientation = LinearLayout.VERTICAL
            list.background = roundedBg(Theme.FIELD, dp(12).toFloat(), Theme.STROKE, dp(1))
            for (n in names) {
                val item = label("•  $n", 14f, Theme.ACCENT)
                item.setPadding(dp(14), dp(10), dp(14), dp(10))
                item.setOnClickListener {
                    val loaded = Store.loadMacro(this, n)
                    if (loaded != null) {
                        replaceButtons(loaded)
                        nameDraft = n
                        selectedId = -1
                        listOpen = false
                        persist()
                        deselect()
                        toast("Đã tải macro: $n")
                    }
                }
                list.addView(item)
            }
            val sv = MaxHeightScroll(this, dp(130))
            sv.addView(list)
            val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            slp.topMargin = dp(4)
            c.addView(sv, slp)
        }

        // xoá macro đã lưu / xoá tất cả nút
        val r4 = LinearLayout(this)
        r4.orientation = LinearLayout.HORIZONTAL
        r4.addView(actionBtn("Xóa macro đã lưu", false, true) { deleteSavedMacro() }, weighted(dp(38), 0, dp(4)))
        r4.addView(actionBtn("Xóa hết nút", false, true) { clearAll() }, weighted(dp(38), dp(4), 0))
        val r4lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        r4lp.topMargin = dp(8)
        c.addView(r4, r4lp)

        val hint = label(
            "Kéo nút để đặt vị trí  ·  Chạm vào nút để chỉnh  ·  Nút số được đánh số riêng theo từng main  ·  Lúc setup nút luôn hiện tối thiểu 20%  ·  Bấm ✕ để chạy macro",
            11f, Theme.MUTED
        )
        hint.setPadding(dp(2), dp(8), dp(2), 0)
        c.addView(hint)
    }

    private var nameInput: EditText? = null

    // ---- nội dung: chỉnh một nút

    // mỗi thanh trượt có bước -/+ RIÊNG: 10 (mặc định) hoặc 1; lưu theo từng thanh
    private fun sliderRow(
        name: String, min: Int, max: Int, value: Int,
        fmt: (Int) -> String, stepKey: String = name, onChange: (Int, Boolean) -> Unit
    ): View {
        var step = Store.sliderStep(this, stepKey)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        // hàng trên: tên | [-] giá trị [+] | [×10 / ×1]
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(label(name, 12f, Theme.MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sb = SeekBar(this)
        val vt = label(fmt(value), 12f, Theme.ACCENT, true)
        vt.gravity = Gravity.CENTER

        // đặt giá trị mới (từ nút -/+): cập nhật thanh, chữ, rồi báo ra ngoài
        fun setValue(nv: Int, done: Boolean) {
            val c = nv.coerceIn(min, max)
            sb.progress = c - min
            vt.text = fmt(c)
            onChange(c, done)
        }

        // giữ nút -/+ để chạy liên tục
        fun stepBtn(sym: String, dir: Int): TextView {
            val t = label(sym, 16f, Theme.ACCENT, true)
            t.gravity = Gravity.CENTER
            t.background = roundedBg(Theme.FIELD, dp(8).toFloat(), Theme.STROKE, dp(1))
            t.isClickable = true
            val rep = object : Runnable {
                override fun run() {
                    setValue(sb.progress + min + dir * step, false)
                    handler.postDelayed(this, 90)
                }
            }
            t.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        setPanelFocusable(false)
                        v.isPressed = true
                        setValue(sb.progress + min + dir * step, false)
                        handler.postDelayed(rep, 420)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        handler.removeCallbacks(rep)
                        onChange(sb.progress + min, true)
                    }
                }
                true
            }
            return t
        }

        top.addView(stepBtn("−", -1), LinearLayout.LayoutParams(dp(32), dp(28)))
        top.addView(vt, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        top.addView(stepBtn("+", 1), LinearLayout.LayoutParams(dp(32), dp(28)))

        val chip = label("×$step", 12f, Color.parseColor("#0B120A"), true)
        chip.gravity = Gravity.CENTER
        chip.background = roundedBg(Theme.ACCENT, dp(8).toFloat())
        chip.isClickable = true
        chip.setOnClickListener {
            setPanelFocusable(false)
            step = if (step == 10) 1 else 10
            Store.setSliderStep(this, stepKey, step)
            chip.text = "×$step"
        }
        val cl = LinearLayout.LayoutParams(dp(40), dp(28))
        cl.leftMargin = dp(6)
        top.addView(chip, cl)
        col.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        sb.max = max - min
        sb.progress = (value - min).coerceIn(0, max - min)
        sb.progressTintList = ColorStateList.valueOf(Theme.ACCENT)
        sb.thumbTintList = ColorStateList.valueOf(Theme.ACCENT)
        sb.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#3A4A38"))
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val v = p + min
                vt.text = fmt(v)
                if (fromUser) onChange(v, false)
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {
                onChange(s.progress + min, true)
            }
        })
        col.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
        return col
    }

    private fun buildEditContent(c: LinearLayout, b: MacroButton) {
        c.removeAllViews()

        // tên tuỳ chỉnh (hiện trên nút, tự co chữ cho vừa nút — không tràn ra ngoài)
        val nameRow = LinearLayout(this)
        nameRow.orientation = LinearLayout.HORIZONTAL
        nameRow.gravity = Gravity.CENTER_VERTICAL
        nameRow.addView(label("Tên nút", 12f, Theme.MUTED), LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
        val nameEt = textField(
            if (b.kind == Kind.MAIN) "main${b.number}" else b.number.toString(),
            b.name,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            16
        ) { t ->
            b.name = t.trim()
            btnViews[b.id]?.invalidate()
            persist()
        }
        nameRow.addView(nameEt, LinearLayout.LayoutParams(0, dp(36), 1f))
        val nl0 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        nl0.bottomMargin = dp(4)
        c.addView(nameRow, nl0)

        c.addView(sliderRow("Kích thước", 28, 140, b.sizeDp, { "${it}dp" }) { v, done ->
            b.sizeDp = v
            applySize(b)
            if (done) persist()
        })
        c.addView(sliderRow("Độ trong", 0, 100, b.alphaPct, { "$it%" }) { v, done ->
            b.alphaPct = v
            btnViews[b.id]?.alpha = viewAlpha(b) // lúc setup vẫn hiện tối thiểu 20%
            if (done) persist()
        })

        if (b.kind == Kind.NUM) {
            c.addView(sliderRow("Tốc độ ấn", 1, 1000, b.delayMs, { "${it}ms" }) { v, done ->
                b.delayMs = v
                if (done) persist()
            })

            // thuộc main nào (số thứ tự được đánh riêng trong từng main)
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(label("Thuộc main", 12f, Theme.MUTED), LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
            val mains = mainNumbers()
            val options = if (b.mainNo == 0) listOf(0) + mains else mains
            val cur = options.indexOf(b.mainNo).coerceAtLeast(0)
            val txt = if (b.mainNo == 0) "Chưa nối main" else "main${b.mainNo}  →  ${b.number}"
            row.addView(
                actionBtn("$txt   ⟳", false) {
                    if (options.size > 1) {
                        relink(b, options[(cur + 1) % options.size])
                        refreshPanel()
                    }
                },
                LinearLayout.LayoutParams(0, dp(36), 1f)
            )
            val rl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            rl.topMargin = dp(4)
            c.addView(row, rl)
        } else {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(label("Kích hoạt", 12f, Theme.MUTED), LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
            val opts = listOf(TRIG_PRESS to "Khi ấn", TRIG_RELEASE to "Khi thả", TRIG_HOLD to "Giữ lặp")
            for ((i, o) in opts.withIndex()) {
                row.addView(
                    actionBtn(o.second, b.trigger == o.first) {
                        b.trigger = o.first
                        persist()
                        refreshPanel()
                    },
                    weighted(dp(36), if (i == 0) 0 else dp(2), if (i == opts.size - 1) 0 else dp(2))
                )
            }
            val rl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            rl.topMargin = dp(4)
            c.addView(row, rl)
            val pl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            pl.topMargin = dp(4)
            c.addView(toggleRow("Chạm xuyên", b.passThru) { on ->
                b.passThru = on
                persist()
                applyModeUi(animate = false)
                refreshPanel()
            }, pl)
            val passNote = label(
                if (!b.passThru) "Tắt: nút main nhận chạm (che phần game bên dưới)."
                else if (Store.tapMode(this) == TAP_ADB && AdbClient.fingersAvailable()) "Bật: nút main chỉ là vùng kiểm tra cảm ứng trong đúng vòng tròn — không nhận chạm, game / app bên dưới vẫn chạm bình thường."
                else "Bật nhưng CHƯA hoạt động: cần chế độ Gỡ lỗi WiFi đã kết nối (để đọc ngón tay). Tạm thời nút vẫn chặn cảm ứng.",
                11f, Theme.MUTED
            )
            passNote.setPadding(dp(2), dp(6), dp(2), 0)
            c.addView(passNote)
            if (b.trigger == TRIG_HOLD) {
                val note = label("Giữ ngón tay trên nút main: chuỗi cứ lặp đi lặp lại, thả tay là dừng.", 11f, Theme.MUTED)
                note.setPadding(dp(2), dp(6), dp(2), 0)
                c.addView(note)
            }
        }

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.addView(actionBtn("Xong", true) { deselect() }, weighted(dp(40), 0, dp(4)))
        bottom.addView(actionBtn("Xóa nút này", false, true) { deleteButton(b) }, weighted(dp(40), dp(4), 0))
        val bl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        bl.topMargin = dp(8)
        c.addView(bottom, bl)
    }

    private fun applySize(b: MacroButton) {
        val v = btnViews[b.id] ?: return
        val lp = btnLps[b.id] ?: return
        val size = dp(b.sizeDp)
        lp.width = size
        lp.height = size
        lp.x = b.x - size / 2
        lp.y = b.y - size / 2
        try {
            wm.updateViewLayout(v, lp)
        } catch (_: Exception) {
        }
        backdrop?.invalidate()
    }

    // ---------------------------------------------------------------- thao tác trên bảng

    private fun selectButton(id: Int) {
        setPanelFocusable(false)
        selectedId = id
        listOpen = false
        buttons.firstOrNull { it.id == id }?.let {
            val m = if (it.kind == Kind.MAIN) it.number else it.mainNo
            if (m > 0) addTargetMain = m
        }
        for ((bid, v) in btnViews) v.hilite = (bid == id)
        applyFocus()
        refreshPanel()
    }

    private fun deselect() {
        selectedId = -1
        for (v in btnViews.values) v.hilite = false
        applyFocus()
        refreshPanel()
    }

    /** Thay toàn bộ danh sách nút: chỉ gỡ / gắn các cửa sổ NÚT, bảng + bong bóng + nền giữ nguyên. */
    private fun replaceButtons(list: MutableList<MacroButton>) {
        for (id in btnViews.keys.toList()) removeButtonWindow(id)
        buttons = list
        lastLand = null
        normalize()
        val (sw, sh) = screenSize()
        applyOrientation(sw, sh)
        for (b in buttons) addButton(b, sw, sh)
        for (b in buttons) btnLps[b.id]?.let { keepChromeOnTop(it) }
        backdrop?.invalidate()
    }

    private fun nextId(): Int = (buttons.maxOfOrNull { it.id } ?: 0) + 1

    private fun addNum() {
        val mainNo = targetMain()
        if (mainNo == 0) {
            toast("Hãy tạo nút trung tâm (main) trước, rồi chọn main để nối nút số vào")
            return
        }
        val (sw, sh) = screenSize()
        val n = buttons.count { it.kind == Kind.NUM && it.mainNo == mainNo } + 1
        val idx = buttons.count { it.kind == Kind.NUM } // chỉ để xếp vị trí cho khỏi chồng nhau
        buttons.add(
            MacroButton(
                id = nextId(), kind = Kind.NUM, number = n,
                x = sw / 2 + ((idx % 7) - 3) * dp(38),
                y = sh / 2 + (idx / 7) * dp(46) - dp(20),
                sizeDp = 56, alphaPct = 85, delayMs = 120,
                mainNo = mainNo, trigger = TRIG_PRESS
            )
        )
        addTargetMain = mainNo
        persist()
        addButtonLive(buttons.last())
        refreshPanel() // cập nhật nhãn "Nút số thuộc ..."
    }

    private fun addMain() {
        val (sw, sh) = screenSize()
        val n = (buttons.filter { it.kind == Kind.MAIN }.maxOfOrNull { it.number } ?: 0) + 1
        buttons.add(
            MacroButton(
                id = nextId(), kind = Kind.MAIN, number = n,
                x = sw - dp(110), y = sh / 2 + (n - 1) * dp(80),
                sizeDp = 72, alphaPct = 90, delayMs = 0,
                mainNo = 0, trigger = TRIG_PRESS
            )
        )
        // không tự nối nút số nào vào main mới; các nút số tạo sau sẽ vào main này
        addTargetMain = n
        persist()
        addButtonLive(buttons.last())
        refreshPanel()
    }

    private fun deleteButton(b: MacroButton) {
        buttons.remove(b)
        if (b.kind == Kind.MAIN) {
            // các nút số của main này thành "chưa nối main" (có thể nối lại bằng mục "Thuộc main")
            for (x in buttons) if (x.kind == Kind.NUM && x.mainNo == b.number) x.mainNo = 0
            normalize()
        } else {
            renumber(b.mainNo)
        }
        selectedId = -1
        persist()
        removeButtonWindow(b.id)
        // số thứ tự của các nút còn lại có thể đổi -> chỉ vẽ lại chúng
        for (v in btnViews.values) v.invalidate()
        backdrop?.invalidate()
        deselect()
    }

    private fun clearAll() {
        for (id in btnViews.keys.toList()) removeButtonWindow(id)
        buttons.clear()
        selectedId = -1
        persist()
        backdrop?.invalidate()
        deselect()
        toast("Đã xóa hết nút")
    }

    private fun saveMacro() {
        val name = nameDraft.trim()
        if (name.isEmpty()) {
            toast("Hãy nhập tên macro trước khi lưu")
            return
        }
        syncSlots()
        Store.saveMacro(this, name, buttons)
        persist()
        toast("Đã lưu macro: $name")
        refreshPanel()
    }

    private fun deleteSavedMacro() {
        val name = nameDraft.trim()
        if (name.isEmpty() || !Store.macroNames(this).contains(name)) {
            toast("Nhập đúng tên macro đã lưu (hoặc chọn từ danh sách) để xóa")
            return
        }
        Store.deleteMacro(this, name)
        nameDraft = ""
        listOpen = false
        toast("Đã xóa macro: $name")
        refreshPanel()
    }

    /** Bật/tắt khả năng nhận bàn phím của bảng (chỉ khi nhập tên macro). */
    fun setPanelFocusable(f: Boolean) {
        val lp = panelLp ?: return
        val root = panelRoot ?: return
        val isFocusable = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
        if (isFocusable == f) return
        if (!f) {
            try {
                nameInput?.clearFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(root.windowToken, 0)
            } catch (_: Exception) {
            }
        }
        lp.flags = if (f) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try {
            wm.updateViewLayout(root, lp)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- chạy macro

    private var chainGen = 0
    private var chainRunning = false
    private var holdMain = -1 // main đang được giữ tay (chế độ lặp); -1 = không lặp
    private var chainLastStep = 0L // lần cuối chuỗi tiến được 1 bước (để phát hiện chuỗi bị kẹt)
    private var pendingMain = -1 // bấm main lúc chuỗi trước còn đang chạy -> nhớ 1 lần, chạy ngay khi chuỗi trước xong

    private fun cancelChain() {
        chainGen++
        chainRunning = false
        holdMain = -1
        pendingMain = -1
        handler.removeCallbacksAndMessages(chainToken)
    }

    /**
     * main số [mainNo] được kích hoạt -> lần lượt bấm vào các nút 1, 2, 3... của CHÍNH main đó.
     * [repeat] = true (kiểu "giữ"): chạy xong chuỗi thì tự chạy lại cho tới khi cancelChain() (thả tay).
     * Các cú chạm đi TUẦN TỰ: cú sau chỉ được gửi khi cú trước đã xong (tránh nghẽn hàng đợi
     * cử chỉ làm đơ màn hình / không xoay được camera).
     */
    private fun runChain(mainNo: Int, repeat: Boolean = false) {
        if (chainRunning) {
            // Chuỗi trước KẸT (shell / ADB không báo lại): tự dọn rồi chạy lại, không để "lần ấn tiếp theo" bị nuốt
            if (SystemClock.uptimeMillis() - chainLastStep > 2500L) {
                cancelChain()
            } else {
                // chuỗi trước còn đang chạy bình thường: nhớ 1 lần bấm để chạy ngay sau đó (không dồn thành hàng dài)
                if (!repeat) pendingMain = mainNo
                return
            }
        }
        val ids = buttons
            .filter { it.kind == Kind.NUM && it.mainNo == mainNo }
            .sortedBy { it.number }
            .map { it.id }
        if (ids.isEmpty()) return
        holdMain = if (repeat) mainNo else -1
        chainGen++
        chainRunning = true
        chainLastStep = SystemClock.uptimeMillis()
        stepChain(chainGen, ids, 0)
    }

    private fun stepChain(gen: Int, ids: List<Int>, i: Int) {
        if (gen != chainGen) return
        chainLastStep = SystemClock.uptimeMillis()
        if (i >= ids.size || mode != Mode.RUN) {
            chainRunning = false
            val pm = pendingMain
            pendingMain = -1
            if (pm != -1 && mode == Mode.RUN) {
                runChain(pm)
                return
            }
            val m = holdMain
            if (m != -1 && mode == Mode.RUN) {
                // vẫn đang giữ tay -> lặp lại chuỗi
                handler.postAtTime({
                    if (gen == chainGen && holdMain == m && mode == Mode.RUN) runChain(m, true)
                }, chainToken, SystemClock.uptimeMillis() + 20)
            }
            return
        }
        val b = buttons.firstOrNull { it.id == ids[i] }
        if (b == null) {
            stepChain(gen, ids, i + 1)
            return
        }
        val wait = maxOf(b.delayMs, 1).toLong()
        handler.postAtTime({
            if (gen == chainGen) {
                tapButton(b.id) { stepChain(gen, ids, i + 1) }
            }
        }, chainToken, SystemClock.uptimeMillis() + wait)
    }

    private fun tapButton(id: Int, next: () -> Unit) {
        var done = false
        fun finish() {
            if (!done) {
                done = true
                next()
            }
        }

        val v = btnViews[id]
        if (mode != Mode.RUN || v == null || !v.isAttachedToWindow) {
            finish()
            return
        }
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)

        if (Store.tapMode(this) == TAP_ADB) {
            // chạm qua Gỡ lỗi WiFi: báo xong thì mới đi tiếp. Kèm hướng xoay + kích thước màn hình để
            // "ngón tay phụ" đổi đúng toạ độ sang tấm cảm ứng (kể cả khi game chạy ngang).
            val (sw, sh) = screenSize()
            val ok = AdbClient.tap(
                Math.round(loc[0] + v.width / 2f),
                Math.round(loc[1] + v.height / 2f),
                displayRotation(), sw, sh
            ) { handler.post { finish() } }
            if (ok) {
                v.flashFx()
                // đề phòng shell không báo lại: tự đi tiếp sau 0,6 giây (cú chạm thật chỉ ~16ms)
                handler.postAtTime({ finish() }, chainToken, SystemClock.uptimeMillis() + 600)
                return
            }
            // ADB chưa kết nối: KHÔNG im lặng nữa -> báo 1 lần và chạm tạm bằng Trợ năng (chạy tiếp xuống dưới)
            val now = SystemClock.uptimeMillis()
            if (now - lastAdbWarn > 3000) {
                lastAdbWarn = now
                toast("Gỡ lỗi WiFi chưa kết nối · đang chạm tạm bằng Trợ năng")
            }
        }

        val path = Path()
        path.moveTo(loc[0] + v.width / 2f, loc[1] + v.height / 2f)
        val stroke = GestureDescription.StrokeDescription(path, 0L, 10L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                finish()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                finish()
            }
        }
        val ok = try {
            dispatchGesture(gesture, cb, handler)
        } catch (_: Exception) {
            false
        }
        v.flashFx()
        if (!ok) {
            finish()
        } else {
            // đề phòng hệ thống không gọi callback: tự đi tiếp sau 400ms để chuỗi không bị kẹt
            handler.postAtTime({ finish() }, chainToken, SystemClock.uptimeMillis() + 400)
        }
    }
}

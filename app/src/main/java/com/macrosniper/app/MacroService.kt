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
    private var pairOpen = false
    private var pairCode = ""
    private var lastAdbWarn = 0L
    private val adbListener: () -> Unit = {
        if (mode == Mode.EDIT && selectedId == -1) refreshPanel()
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
                        ensureAttached()
                    }
                    if (Store.tapMode(this@MacroService) == TAP_ADB) AdbClient.tick(applicationContext)
                }
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 1000)
        }
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
        if (Store.isRunning(this)) startOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        density = resources.displayMetrics.density
        if (mode != Mode.OFF) {
            handler.postDelayed({ rebuildAll() }, 350)
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
        AdbClient.listeners.remove(adbListener)
        AdbClient.disconnect()
        handler.removeCallbacks(watchdog)
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
        normalize()
        selectedId = -1
        listOpen = false
        mode = Mode.RUN
        if (Store.tapMode(this) == TAP_ADB) AdbClient.connect(applicationContext)
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

    private fun baseLp(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
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
        bubble = null
        panelRoot = null
        panelLp = null
        panelContent = null
        panelTitle = null
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

    private fun persist() {
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
        backdrop?.invalidate()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- dựng giao diện

    private fun rebuildAll() {
        removeAll()
        if (mode == Mode.OFF) return
        val (sw, sh) = screenSize()
        if (mode == Mode.EDIT) addBackdrop()
        for (b in buttons) addButton(b, sw, sh)
        if (mode == Mode.EDIT) addPanel(sw, sh)
        addBubble(sw, sh)
    }

    private fun addBackdrop() {
        val v = BackdropView(this)
        val lp = baseLp(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            true
        )
        lp.x = 0
        lp.y = 0
        v.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                setPanelFocusable(false)
                if (selectedId != -1) deselect()
            }
            true
        }
        backdrop = v
        addOverlay(v, lp)
    }

    private fun addButton(b: MacroButton, sw: Int, sh: Int) {
        val size = dp(b.sizeDp)
        val editing = mode == Mode.EDIT
        val touchable = editing || b.kind == Kind.MAIN
        val lp = baseLp(size, size, touchable)
        b.x = b.x.coerceIn(size / 2, maxOf(size / 2, sw - size / 2))
        b.y = b.y.coerceIn(size / 2, maxOf(size / 2, sh - size / 2))
        lp.x = b.x - size / 2
        lp.y = b.y - size / 2

        val v = BtnView(this, b)
        v.alpha = viewAlpha(b)
        v.showTag = editing
        v.hilite = editing && b.id == selectedId

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
                        backdrop?.invalidate()
                    },
                    onTap = { selectButton(b.id) },
                    onDragEnd = { persist() }
                )
            )
        } else if (b.kind == Kind.MAIN) {
            v.setOnTouchListener { view, e ->
                val bv = view as BtnView
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        bv.pressedFx = true
                        when (b.trigger) {
                            TRIG_PRESS -> runChain(b.number)
                            TRIG_HOLD -> runChain(b.number, true)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        bv.pressedFx = false
                        when (b.trigger) {
                            TRIG_RELEASE -> runChain(b.number)
                            TRIG_HOLD -> cancelChain() // thả tay -> dừng lặp
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        bv.pressedFx = false
                        if (b.trigger == TRIG_HOLD) cancelChain()
                    }
                }
                true
            }
        }
        btnViews[b.id] = v
        btnLps[b.id] = lp
        addOverlay(v, lp)
    }

    private fun addBubble(sw: Int, sh: Int) {
        val size = dp(54)
        val lp = baseLp(size, size, true)
        val saved = Store.bubblePos(this)
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
                onDragEnd = { Store.setBubblePos(this, lp.x, lp.y) }
            )
        )
        bubble = v
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
        rebuildAll()
    }

    private fun exitEdit() {
        setPanelFocusable(false)
        mode = Mode.RUN
        selectedId = -1
        listOpen = false
        persist()
        rebuildAll()
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
            for (m in buttons.filter { it.kind == Kind.MAIN }) {
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

        val title = label("MACRO SNIPER", 15f, Theme.ACCENT, true)
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

    private fun refreshPanel() {
        val content = panelContent ?: return
        val b = buttons.firstOrNull { it.id == selectedId }
        if (b == null) {
            selectedId = -1
            panelTitle?.text = "MACRO SNIPER  ·  SETUP"
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
        refreshPanel()
    }

    private fun numField(hint: String, value: String, onText: (String) -> Unit): EditText {
        val et = EditText(this)
        et.setText(value)
        et.hint = hint
        et.setHintTextColor(Theme.MUTED)
        et.setTextColor(Theme.TEXT)
        et.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        et.setSingleLine(true)
        et.inputType = InputType.TYPE_CLASS_NUMBER
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
                        buttons = loaded
                        normalize()
                        nameDraft = n
                        selectedId = -1
                        listOpen = false
                        persist()
                        rebuildAll()
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

    private fun sliderRow(
        name: String, min: Int, max: Int, value: Int,
        fmt: (Int) -> String, onChange: (Int, Boolean) -> Unit
    ): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val tv = label(name, 12f, Theme.MUTED)
        row.addView(tv, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))

        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = (value - min).coerceIn(0, max - min)
        sb.progressTintList = ColorStateList.valueOf(Theme.ACCENT)
        sb.thumbTintList = ColorStateList.valueOf(Theme.ACCENT)
        sb.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#3A4A38"))
        val vt = label(fmt(value), 12f, Theme.ACCENT, true)
        vt.gravity = Gravity.END
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
        row.addView(sb, LinearLayout.LayoutParams(0, dp(36), 1f))
        row.addView(vt, LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun buildEditContent(c: LinearLayout, b: MacroButton) {
        c.removeAllViews()

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
            c.addView(sliderRow("Tốc độ ấn", 30, 1000, b.delayMs, { "${it}ms" }) { v, done ->
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
        refreshPanel()
    }

    private fun deselect() {
        selectedId = -1
        for (v in btnViews.values) v.hilite = false
        refreshPanel()
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
        rebuildAll()
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
        rebuildAll()
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
        rebuildAll()
    }

    private fun clearAll() {
        buttons.clear()
        selectedId = -1
        persist()
        rebuildAll()
        toast("Đã xóa hết nút")
    }

    private fun saveMacro() {
        val name = nameDraft.trim()
        if (name.isEmpty()) {
            toast("Hãy nhập tên macro trước khi lưu")
            return
        }
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

    private fun cancelChain() {
        chainGen++
        chainRunning = false
        holdMain = -1
        handler.removeCallbacksAndMessages(chainToken)
    }

    /**
     * main số [mainNo] được kích hoạt -> lần lượt bấm vào các nút 1, 2, 3... của CHÍNH main đó.
     * [repeat] = true (kiểu "giữ"): chạy xong chuỗi thì tự chạy lại cho tới khi cancelChain() (thả tay).
     * Các cú chạm đi TUẦN TỰ: cú sau chỉ được gửi khi cú trước đã xong (tránh nghẽn hàng đợi
     * cử chỉ làm đơ màn hình / không xoay được camera).
     */
    private fun runChain(mainNo: Int, repeat: Boolean = false) {
        if (chainRunning) return // đang chạy dở thì bỏ qua lần bấm dồn
        val ids = buttons
            .filter { it.kind == Kind.NUM && it.mainNo == mainNo }
            .sortedBy { it.number }
            .map { it.id }
        if (ids.isEmpty()) return
        holdMain = if (repeat) mainNo else -1
        chainGen++
        chainRunning = true
        stepChain(chainGen, ids, 0)
    }

    private fun stepChain(gen: Int, ids: List<Int>, i: Int) {
        if (gen != chainGen) return
        if (i >= ids.size || mode != Mode.RUN) {
            chainRunning = false
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
        val wait = maxOf(b.delayMs, 30).toLong()
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
            // chạm qua Gỡ lỗi WiFi: shell báo xong thì mới đi tiếp
            // hướng màn hình + kích thước thật: cần để đổi tọa độ sang tấm cảm ứng khi xoay ngang
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            @Suppress("DEPRECATION")
            val rot = wm.defaultDisplay.rotation
            val ok = AdbClient.tap(
                (loc[0] + v.width / 2f).toInt(),
                (loc[1] + v.height / 2f).toInt(),
                rot, dm.widthPixels, dm.heightPixels
            ) { handler.post { finish() } }
            v.flashFx()
            if (!ok) {
                cancelChain()
                val now = SystemClock.uptimeMillis()
                if (now - lastAdbWarn > 3000) {
                    lastAdbWarn = now
                    toast("Gỡ lỗi WiFi chưa kết nối · chạm bong bóng, đổi sang chế độ Trợ năng để dùng tạm")
                }
            } else {
                // đề phòng shell không báo lại: tự đi tiếp sau 1,5 giây
                handler.postAtTime({ finish() }, chainToken, SystemClock.uptimeMillis() + 1500)
            }
            return
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

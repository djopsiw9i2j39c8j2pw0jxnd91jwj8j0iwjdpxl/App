package com.macrosniper.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private lateinit var toggleBtn: TextView
    private lateinit var modeAcc: TextView
    private lateinit var modeAdb: TextView
    private lateinit var adbBox: LinearLayout
    private lateinit var adbStatus: TextView
    private val adbListener: () -> Unit = { refreshMode() }

    private fun dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun pressFx(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0C100C"))

        // quầng sáng xanh phía trên
        val glow = View(this)
        val gd = GradientDrawable()
        gd.gradientType = GradientDrawable.RADIAL_GRADIENT
        gd.setGradientCenter(0.5f, 0.0f)
        gd.gradientRadius = dp(420).toFloat()
        gd.colors = intArrayOf(Color.parseColor("#4D6BFF2E"), Color.parseColor("#00000000"))
        glow.background = gd
        root.addView(glow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(460)))

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(22), dp(56), dp(22), dp(32))
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // logo
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.logo_full)
        logo.scaleType = ImageView.ScaleType.CENTER_CROP
        logo.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.24f)
            }
        }
        logo.clipToOutline = true
        logo.elevation = dp(10).toFloat()
        col.addView(logo, LinearLayout.LayoutParams(dp(132), dp(132)))

        // tên app
        val title = text("MACRO SNIPER", 32f, Theme.ACCENT, true)
        title.letterSpacing = 0.14f
        title.gravity = Gravity.CENTER
        val tlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.topMargin = dp(22)
        col.addView(title, tlp)

        val sub = text("Chuỗi chạm thông minh · Nút nổi · Chính xác từng mili-giây", 13f, Theme.MUTED)
        sub.gravity = Gravity.CENTER
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.topMargin = dp(6)
        col.addView(sub, slp)

        // thẻ trạng thái
        val status = LinearLayout(this)
        status.orientation = LinearLayout.HORIZONTAL
        status.gravity = Gravity.CENTER_VERTICAL
        status.setPadding(dp(16), dp(14), dp(16), dp(14))
        status.background = roundedBg(Color.parseColor("#14201A12"), dp(18).toFloat(), Color.parseColor("#2A4A1C"), dp(1))
        statusDot = View(this)
        val dotBg = GradientDrawable()
        dotBg.shape = GradientDrawable.OVAL
        dotBg.setColor(Theme.DANGER)
        statusDot.background = dotBg
        status.addView(statusDot, LinearLayout.LayoutParams(dp(12), dp(12)))
        val stCol = LinearLayout(this)
        stCol.orientation = LinearLayout.VERTICAL
        stCol.setPadding(dp(14), 0, 0, 0)
        statusText = text("", 15f, Theme.TEXT, true)
        statusSub = text("", 12f, Theme.MUTED)
        stCol.addView(statusText)
        stCol.addView(statusSub)
        status.addView(stCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val stlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        stlp.topMargin = dp(30)
        col.addView(status, stlp)

        // thẻ chọn chế độ chạm
        val modeCard = LinearLayout(this)
        modeCard.orientation = LinearLayout.VERTICAL
        modeCard.setPadding(dp(16), dp(14), dp(16), dp(14))
        modeCard.background = roundedBg(Color.parseColor("#0FFFFFFF"), dp(18).toFloat(), Color.parseColor("#1FFFFFFF"), dp(1))
        modeCard.addView(text("CHẾ ĐỘ CHẠM", 12f, Theme.ACCENT, true))
        val seg = LinearLayout(this)
        seg.orientation = LinearLayout.HORIZONTAL
        modeAcc = text("Trợ năng", 13f, Theme.ACCENT, true)
        modeAdb = text("Gỡ lỗi WiFi", 13f, Theme.ACCENT, true)
        for (t in listOf(modeAcc, modeAdb)) {
            t.gravity = Gravity.CENTER
            t.isClickable = true
            pressFx(t)
        }
        modeAcc.setOnClickListener { setTapModeUi(TAP_ACC) }
        modeAdb.setOnClickListener { setTapModeUi(TAP_ADB) }
        val sl1 = LinearLayout.LayoutParams(0, dp(44), 1f)
        sl1.setMargins(0, 0, dp(4), 0)
        val sl2 = LinearLayout.LayoutParams(0, dp(44), 1f)
        sl2.setMargins(dp(4), 0, 0, 0)
        seg.addView(modeAcc, sl1)
        seg.addView(modeAdb, sl2)
        val segLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        segLp.topMargin = dp(10)
        modeCard.addView(seg, segLp)

        adbBox = LinearLayout(this)
        adbBox.orientation = LinearLayout.VERTICAL
        adbStatus = text("", 12f, Theme.MUTED, true)
        adbStatus.setPadding(0, dp(10), 0, dp(6))
        adbBox.addView(adbStatus)
        val ar = LinearLayout(this)
        ar.orientation = LinearLayout.HORIZONTAL
        val cb = text("Kết nối", 13f, Color.parseColor("#0B120A"), true)
        cb.gravity = Gravity.CENTER
        cb.background = roundedBg(Theme.ACCENT, dp(12).toFloat())
        cb.isClickable = true
        pressFx(cb)
        cb.setOnClickListener { AdbClient.connect(applicationContext) }
        val db = text("Ngắt", 13f, Theme.DANGER, true)
        db.gravity = Gravity.CENTER
        db.background = roundedBg(Theme.FIELD, dp(12).toFloat(), Theme.DANGER, dp(1))
        db.isClickable = true
        pressFx(db)
        db.setOnClickListener { AdbClient.disconnect() }
        val bl1 = LinearLayout.LayoutParams(0, dp(40), 1f)
        bl1.setMargins(0, 0, dp(4), 0)
        val bl2 = LinearLayout.LayoutParams(0, dp(40), 1f)
        bl2.setMargins(dp(4), 0, 0, 0)
        ar.addView(cb, bl1)
        ar.addView(db, bl2)
        adbBox.addView(ar)
        val an = text(
            "Cần Android 11+. Ghép cặp lần đầu: bật Tùy chọn nhà phát triển → Gỡ lỗi không dây, bấm BẮT ĐẦU, " +
                    "chạm bong bóng → Chế độ chạm → Ghép cặp (bảng nổi nằm trên Cài đặt nên nhập mã được). " +
                    "Mất Wi-Fi thì đổi về Trợ năng ngay trên bảng nổi hoặc ở đây.",
            11f, Theme.MUTED
        )
        an.setPadding(0, dp(8), 0, 0)
        adbBox.addView(an)
        modeCard.addView(adbBox)
        val mcl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        mcl.topMargin = dp(16)
        col.addView(modeCard, mcl)

        // nút bật/tắt (chỉ 1 nút duy nhất, đổi chữ + màu theo trạng thái)
        toggleBtn = text("", 16f, Color.parseColor("#0B120A"), true)
        toggleBtn.gravity = Gravity.CENTER
        toggleBtn.letterSpacing = 0.05f
        toggleBtn.isClickable = true
        pressFx(toggleBtn)
        toggleBtn.setOnClickListener { onToggleClicked() }
        val tgl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62))
        tgl.topMargin = dp(16)
        col.addView(toggleBtn, tgl)

        // hướng dẫn
        val guide = LinearLayout(this)
        guide.orientation = LinearLayout.VERTICAL
        guide.setPadding(dp(18), dp(16), dp(18), dp(16))
        guide.background = roundedBg(Color.parseColor("#0FFFFFFF"), dp(18).toFloat(), Color.parseColor("#1FFFFFFF"), dp(1))
        guide.addView(text("HƯỚNG DẪN NHANH", 12f, Theme.ACCENT, true))
        val steps = listOf(
            "1.  Bật dịch vụ \"Macro Sniper\" trong Cài đặt → Trợ năng.",
            "2.  Quay lại đây, bấm BẮT ĐẦU. Bong bóng logo sẽ nổi trên màn hình (kéo thả được).",
            "3.  Chạm bong bóng để mở bảng setup: tạo nút trung tâm main1, rồi chọn main đó để thêm nút số 1, 2, 3... (mỗi main có số riêng: main2 → 1, 2...).",
            "4.  Kéo các nút đến đúng vị trí cần bấm, chạm vào nút để chỉnh size / độ trong (kéo về 0 là tàng hình) / tốc độ. Lúc setup nút luôn hiện tối thiểu 20%.",
            "5.  Nút main có 3 kiểu kích hoạt: khi ấn, khi thả, hoặc giữ tay để chuỗi tự lặp lại. Chế độ chạm (Trợ năng / Gỡ lỗi WiFi) chọn ở thẻ phía trên hoặc ngay trên bảng nổi.",
            "6.  Bấm ✕ để khóa nút. Mở game rồi bấm main1 → máy tự chạm 1 → 2 → 3... đúng tốc độ.",
            "7.  Muốn gỡ toàn bộ giao diện nổi: mở lại app này và bấm nút TẮT (cũng là nút bật ở trên)."
        )
        for (s in steps) {
            val t = text(s, 13f, Theme.TEXT)
            t.setLineSpacing(0f, 1.15f)
            t.setPadding(0, dp(8), 0, 0)
            guide.addView(t)
        }
        val note = text(
            "Android 13+ cài từ file APK: nếu công tắc Trợ năng bị mờ, vào Cài đặt → Ứng dụng → Macro Sniper → ⋮ → \"Cho phép cài đặt bị hạn chế\", rồi bật lại.",
            12f, Theme.MUTED
        )
        note.setPadding(0, dp(14), 0, 0)
        guide.addView(note)
        val gl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        gl.topMargin = dp(24)
        col.addView(guide, gl)

        val warn = text(
            "Lưu ý: dùng công cụ tự động trong game có thể vi phạm điều khoản của nhà phát hành. Hãy tự cân nhắc rủi ro.",
            11f, Theme.MUTED
        )
        warn.gravity = Gravity.CENTER
        val wl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        wl.topMargin = dp(18)
        col.addView(warn, wl)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        AdbClient.listeners.remove(adbListener)
        AdbClient.listeners.add(adbListener)
        refreshStatus()
    }

    override fun onPause() {
        AdbClient.listeners.remove(adbListener)
        super.onPause()
    }

    private fun setTapModeUi(m: Int) {
        Store.setTapMode(this, m)
        if (m == TAP_ADB) AdbClient.connect(applicationContext) else AdbClient.disconnect()
        refreshMode()
    }

    private fun refreshMode() {
        val adb = Store.tapMode(this) == TAP_ADB
        fun style(t: TextView, on: Boolean) {
            t.setTextColor(if (on) Color.parseColor("#0B120A") else Theme.ACCENT)
            t.background = if (on) roundedBg(Theme.ACCENT, dp(12).toFloat())
            else roundedBg(Theme.FIELD, dp(12).toFloat(), Theme.STROKE, dp(1))
        }
        style(modeAcc, !adb)
        style(modeAdb, adb)
        adbBox.visibility = if (adb) View.VISIBLE else View.GONE
        adbStatus.text = AdbClient.statusText()
        adbStatus.setTextColor(AdbClient.statusColor())
    }

    private fun serviceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return list.any { it.resourceInfo() == packageName }
    }

    private fun AccessibilityServiceInfo.resourceInfo(): String? =
        this.resolveInfo?.serviceInfo?.packageName

    private fun isRunning(): Boolean =
        serviceEnabled() && Store.isRunning(this) && MacroService.instance != null

    /** Đổi giao diện nút bật/tắt theo trạng thái. */
    private fun applyToggle(running: Boolean) {
        if (running) {
            toggleBtn.text = "■   TẮT MACRO & GỠ GIAO DIỆN NỔI"
            toggleBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            toggleBtn.setTextColor(Theme.DANGER)
            toggleBtn.background = roundedBg(Color.parseColor("#181010"), dp(20).toFloat(), Theme.DANGER, dp(1))
            toggleBtn.elevation = 0f
        } else {
            toggleBtn.text = "▶   BẮT ĐẦU DÙNG MACRO"
            toggleBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            toggleBtn.setTextColor(Color.parseColor("#0B120A"))
            val sg = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#B6FF5E"), Color.parseColor("#3FD91C"))
            )
            sg.cornerRadius = dp(20).toFloat()
            toggleBtn.background = sg
            toggleBtn.elevation = dp(6).toFloat()
        }
    }

    private fun refreshStatus() {
        val enabled = serviceEnabled()
        val running = isRunning()
        applyToggle(running)
        refreshMode()
        val dotBg = statusDot.background as GradientDrawable
        when {
            running -> {
                dotBg.setColor(Theme.ACCENT)
                statusText.text = "Đang chạy"
                statusSub.text = "Giao diện nổi đang hiển thị trên màn hình"
            }
            enabled -> {
                dotBg.setColor(Theme.LIME)
                statusText.text = "Sẵn sàng"
                statusSub.text = "Dịch vụ Trợ năng đã bật · bấm BẮT ĐẦU để hiện nút nổi"
            }
            else -> {
                dotBg.setColor(Theme.DANGER)
                statusText.text = "Chưa bật dịch vụ Trợ năng"
                statusSub.text = "Cần bật để app hiển thị nút nổi và tự chạm màn hình"
            }
        }
    }

    private fun onToggleClicked() {
        if (isRunning()) onStopClicked() else onStartClicked()
    }

    private fun onStartClicked() {
        val svc = MacroService.instance
        if (svc != null) {
            svc.startOverlay()
            refreshStatus()
            Toast.makeText(this, "Đã bật! Chạm bong bóng logo để setup macro.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        } else if (!serviceEnabled()) {
            showEnableDialog()
        } else {
            Toast.makeText(this, "Dịch vụ đang khởi động, thử lại sau vài giây.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onStopClicked() {
        val svc = MacroService.instance
        if (svc != null) {
            svc.stopOverlay()
        } else {
            Store.setRunning(this, false)
        }
        refreshStatus()
        Toast.makeText(this, "Đã tắt macro và gỡ toàn bộ giao diện nổi.", Toast.LENGTH_SHORT).show()
    }

    private fun showEnableDialog() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Bật dịch vụ Trợ năng")
            .setMessage(
                "Macro Sniper cần quyền Trợ năng để hiển thị nút nổi và tự chạm màn hình.\n\n" +
                        "Vào Cài đặt → Trợ năng → Ứng dụng đã tải xuống → Macro Sniper → Bật.\n\n" +
                        "Nếu công tắc bị mờ (Android 13+): mở \"Thông tin ứng dụng\" → ⋮ → \"Cho phép cài đặt bị hạn chế\", rồi bật lại."
            )
            .setPositiveButton("Mở Cài đặt Trợ năng") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("Thông tin ứng dụng") { _, _ ->
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                i.data = Uri.parse("package:$packageName")
                startActivity(i)
            }
            .setNegativeButton("Đóng", null)
            .show()
    }
}

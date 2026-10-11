package com.macrosniper.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.Manifest
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
import android.text.InputType
import android.widget.EditText
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

        val title = text("MACRO TOUCH", 32f, Theme.ACCENT, true)
        title.letterSpacing = 0.14f
        title.gravity = Gravity.CENTER
        val tlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.topMargin = dp(22)
        col.addView(title, tlp)

        val sub = text("Chuoi cham thong minh - Nut noi - Khong can Tro nang (mode Go loi WiFi)", 13f, Theme.MUTED)
        sub.gravity = Gravity.CENTER
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.topMargin = dp(6)
        col.addView(sub, slp)

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

        val modeCard = LinearLayout(this)
        modeCard.orientation = LinearLayout.VERTICAL
        modeCard.setPadding(dp(16), dp(14), dp(16), dp(14))
        modeCard.background = roundedBg(Color.parseColor("#0FFFFFFF"), dp(18).toFloat(), Color.parseColor("#1FFFFFFF"), dp(1))
        modeCard.addView(text("CHE DO CHAM", 12f, Theme.ACCENT, true))
        val seg = LinearLayout(this)
        seg.orientation = LinearLayout.HORIZONTAL
        modeAcc = text("Tro nang", 13f, Theme.ACCENT, true)
        modeAdb = text("Go loi WiFi (khong Tro nang)", 12f, Theme.ACCENT, true)
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
        val cb = text("Ket noi", 13f, Color.parseColor("#0B120A"), true)
        cb.gravity = Gravity.CENTER
        cb.background = roundedBg(Theme.ACCENT, dp(12).toFloat())
        cb.isClickable = true
        pressFx(cb)
        cb.setOnClickListener { AdbClient.connect(applicationContext) }
        val db = text("Ngat", 13f, Theme.DANGER, true)
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
            "Can Android 11+. Ghep cap (lam 1 lan): vao Tuy chon nha phat trien -> Go loi khong day -> " +
                    "\"Ghep noi thiet bi bang ma\" va de hop thoai do MO. Chi can nhap MA 6 so, app tu tim cong. " +
                    "Vi hop thoai nam o Cai dat, hay bam \"Gui thong bao nhap ma\" roi go ma ngay trong thong bao " +
                    "(hoac dung chia doi man hinh de go o o duoi). " +
                    "MAT WI-FI: cu cham bi bo qua (bao toast); app tu noi lai khi co Wi-Fi — KHONG tu doi sang Tro nang.",
            11f, Theme.MUTED
        )
        an.setPadding(0, dp(10), 0, 0)
        adbBox.addView(an)

        val pr = LinearLayout(this)
        pr.orientation = LinearLayout.HORIZONTAL
        pr.gravity = Gravity.CENTER_VERTICAL
        val codeInput = EditText(this)
        codeInput.hint = "Ma 6 so"
        codeInput.setHintTextColor(Theme.MUTED)
        codeInput.setTextColor(Theme.TEXT)
        codeInput.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        codeInput.setSingleLine(true)
        codeInput.inputType = InputType.TYPE_CLASS_NUMBER
        codeInput.setPadding(dp(12), 0, dp(12), 0)
        codeInput.background = roundedBg(Theme.FIELD, dp(12).toFloat(), Theme.STROKE, dp(1))
        val pb = text("Ghep cap", 13f, Color.parseColor("#0B120A"), true)
        pb.gravity = Gravity.CENTER
        pb.background = roundedBg(Theme.ACCENT, dp(12).toFloat())
        pb.isClickable = true
        pressFx(pb)
        pb.setOnClickListener {
            val code = codeInput.text.toString().trim()
            if (code.length < 6) {
                Toast.makeText(this, "Nhap ma 6 so tren hop thoai ghep noi", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Dang ghep cap...", Toast.LENGTH_SHORT).show()
                AdbClient.pair(applicationContext, code) { ok, msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    if (ok) {
                        codeInput.setText("")
                        AdbClient.connect(applicationContext)
                    }
                    refreshMode()
                }
            }
        }
        val pl1 = LinearLayout.LayoutParams(0, dp(44), 1f)
        pl1.setMargins(0, 0, dp(6), 0)
        pr.addView(codeInput, pl1)
        pr.addView(pb, LinearLayout.LayoutParams(dp(104), dp(44)))
        val prl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        prl.topMargin = dp(10)
        adbBox.addView(pr, prl)

        val nb = text("Gui thong bao nhap ma", 13f, Theme.ACCENT, true)
        nb.gravity = Gravity.CENTER
        nb.background = roundedBg(Theme.FIELD, dp(12).toFloat(), Theme.STROKE, dp(1))
        nb.isClickable = true
        pressFx(nb)
        nb.setOnClickListener { sendPairNotification() }
        val nbl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
        nbl.topMargin = dp(8)
        adbBox.addView(nb, nbl)

        val ob = text("Mo Tuy chon nha phat trien", 13f, Theme.ACCENT, true)
        ob.gravity = Gravity.CENTER
        ob.background = roundedBg(Theme.FIELD, dp(12).toFloat(), Theme.STROKE, dp(1))
        ob.isClickable = true
        pressFx(ob)
        ob.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Khong mo duoc Tuy chon nha phat trien", Toast.LENGTH_SHORT).show()
            }
        }
        val obl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
        obl.topMargin = dp(6)
        adbBox.addView(ob, obl)
        modeCard.addView(adbBox)
        val mcl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        mcl.topMargin = dp(16)
        col.addView(modeCard, mcl)

        toggleBtn = text("", 16f, Color.parseColor("#0B120A"), true)
        toggleBtn.gravity = Gravity.CENTER
        toggleBtn.letterSpacing = 0.05f
        toggleBtn.isClickable = true
        pressFx(toggleBtn)
        toggleBtn.setOnClickListener { onToggleClicked() }
        val tgl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62))
        tgl.topMargin = dp(16)
        col.addView(toggleBtn, tgl)

        val guide = LinearLayout(this)
        guide.orientation = LinearLayout.VERTICAL
        guide.setPadding(dp(18), dp(16), dp(18), dp(16))
        guide.background = roundedBg(Color.parseColor("#0FFFFFFF"), dp(18).toFloat(), Color.parseColor("#1FFFFFFF"), dp(1))
        guide.addView(text("HUONG DAN NHANH (V9.0 - khong can Tro nang)", 12f, Theme.ACCENT, true))
        val steps = listOf(
            "1.  Cap quyen \"Hien thi tren cac ung dung khac\" (bam BAT DAU, app se mo trang cap quyen).",
            "2.  Chon che do cham o the ben tren:",
            "    - Go loi WiFi (khuyen dung, GIONG panda touch pro): KHONG can bat Tro nang, ngan hang khong chan.",
            "    - Tro nang: dung khi may khong co Go loi khong day (Android < 11). Can bat dich vu Macro Touch trong Cai dat -> Tro nang.",
            "3.  Bam BAT DAU. Bong bong logo noi tren man hinh (keo tha duoc). Cham bong bong de mo bang setup.",
            "4.  Tao nut trung tam main1, chon main do roi them nut so 1, 2, 3... Keo vao vi tri, cham de chinh size / do trong / toc do.",
            "5.  Nut main co 3 kieu kich hoat: Khi an, Khi tha, Giu lap. Bat \"Cham xuyen\" neu muon cham xuyen xuong game (can Go loi WiFi).",
            "6.  Bam X de khoa nut. Bam main1 -> app tu cham 1 -> 2 -> 3... dung toc do.",
            "7.  Muon go toan bo giao dien noi: mo lai app va bam nut TAT (cung la nut bat o tren)."
        )
        for (s in steps) {
            val t = text(s, 13f, Theme.TEXT)
            t.setLineSpacing(0f, 1.15f)
            t.setPadding(0, dp(8), 0, 0)
            guide.addView(t)
        }
        val note = text(
            "DIEM MOI o V9.0: dich vu \"Macro Touch\" trong Tro nang GIO CHI DUOC DUNG khi ban chon mode Tro nang. " +
                    "Mode Go loi WiFi la mot Foreground Service doc lap, khong dang ky quyen Tro nang -> app ngan hang kiem tra se khong thay bat ki thu gi.",
            12f, Theme.MUTED
        )
        note.setPadding(0, dp(14), 0, 0)
        guide.addView(note)
        val gl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        gl.topMargin = dp(24)
        col.addView(guide, gl)

        val warn = text(
            "Luu y: cong cu nay chi la mot dang macro co cac chuc nang touch tuong tu nhu panda touch pro.",
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

    private fun sendPairNotification() {
        if (!PairNotif.canPost(this)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
            return
        }
        if (PairNotif.show(applicationContext)) {
            Toast.makeText(this, "Da gui thong bao - mo Go loi khong day -> Ghep noi bang ma roi nhap ma trong thong bao", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Che do nay can Android 11 tro len", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                sendPairNotification()
            } else {
                Toast.makeText(this, "Chua cap quyen thong bao nen khong gui duoc", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setTapModeUi(m: Int) {
        Store.setTapMode(this, m)
        if (m == TAP_ADB) AdbClient.connect(applicationContext) else AdbClient.disconnect()
        refreshMode()
        refreshStatus()
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

    private fun accessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return list.any { it.resolveInfo?.serviceInfo?.packageName == packageName }
    }

    private fun uiRunning(): Boolean = OverlayService.uiRunning(this)

    private fun applyToggle(running: Boolean) {
        if (running) {
            toggleBtn.text = "[STOP]   TAT MACRO & GO GIAO DIEN NOI"
            toggleBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            toggleBtn.setTextColor(Theme.DANGER)
            toggleBtn.background = roundedBg(Color.parseColor("#181010"), dp(20).toFloat(), Theme.DANGER, dp(1))
            toggleBtn.elevation = 0f
        } else {
            toggleBtn.text = ">   BAT DAU DUNG MACRO"
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
        val adbMode = Store.tapMode(this) == TAP_ADB
        val running = uiRunning()
        applyToggle(running)
        refreshMode()
        val dotBg = statusDot.background as GradientDrawable
        when {
            running -> {
                dotBg.setColor(Theme.ACCENT)
                statusText.text = "Dang chay"
                statusSub.text = if (adbMode) "Giao dien noi: dung Hien thi tren app khac - Cham: Go loi WiFi (khong Tro nang)"
                else "Giao dien noi: dung Hien thi tren app khac - Cham: Tro nang"
            }
            canOverlay() -> {
                dotBg.setColor(Theme.LIME)
                statusText.text = "San sang"
                statusSub.text = if (adbMode) "Bam BAT DAU - mode Go loi WiFi, khong can Tro nang"
                else "Bam BAT DAU - mode Tro nang can bat dich vu Macro Touch trong Cai dat Tro nang"
            }
            else -> {
                dotBg.setColor(Theme.DANGER)
                statusText.text = "Chua cap quyen \"Hien thi tren ung dung khac\""
                statusSub.text = "Can cap quyen nay de ve nut noi (khong con dung Tro nang de ve)"
            }
        }
    }

    private fun onToggleClicked() {
        if (uiRunning()) onStopClicked() else onStartClicked()
    }

    private fun canOverlay(): Boolean = try {
        Settings.canDrawOverlays(this)
    } catch (_: Exception) {
        true
    }

    private fun showOverlayDialog() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Cap quyen \"Hien thi tren ung dung khac\"")
            .setMessage(
                "V9.0 KHONG con dung Tro nang de ve nut noi. Toan bo giao dien noi (nut, bong bong, bang setup) " +
                        "duoc ve bang quyen \"Hien thi tren cac ung dung khac\" cua Android.\\n\\n" +
                        "Bam \"Cap quyen\", bat cong tac cho Macro Touch roi quay lai bam BAT DAU."
            )
            .setPositiveButton("Cap quyen") { _, _ ->
                try {
                    val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    i.data = Uri.parse("package:$packageName")
                    startActivity(i)
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }
            }
            .setNegativeButton("Dong", null)
            .show()
    }

    private fun onStartClicked() {
        if (!canOverlay()) {
            showOverlayDialog()
            return
        }
        // Mode Tro nang: can dich vu Tro nang chay thi cu cham moi hoat dong
        if (Store.tapMode(this) == TAP_ACC && !accessibilityEnabled()) {
            showEnableAccessibilityDialog()
            return
        }
        OverlayService.requestStart(applicationContext)
        // tre nhe de dich vu kip dang ky instance
        toggleBtn.postDelayed({
            refreshStatus()
            if (uiRunning()) {
                Toast.makeText(this, "Da bat! Cham bong bong logo de setup macro.", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            }
        }, 400)
    }

    private fun onStopClicked() {
        OverlayService.requestStop(applicationContext)
        Store.setRunning(this, false)
        toggleBtn.postDelayed({ refreshStatus() }, 300)
        Toast.makeText(this, "Da tat macro va go toan bo giao dien noi.", Toast.LENGTH_SHORT).show()
    }

    private fun showEnableAccessibilityDialog() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Mode Tro nang can bat dich vu Tro nang")
            .setMessage(
                "Ban dang chon mode cham = Tro nang. Mode nay dung quyen Tro nang de tao cu cham (dispatchGesture).\\n\\n" +
                        "Neu khong muon dung Tro nang (de ngan hang khong chan): o the \"CHE DO CHAM\" chon \"Go loi WiFi\" roi bam BAT DAU lai.\\n\\n" +
                        "Van muon mode Tro nang? Vao Cai dat -> Tro nang -> Ung dung da tai xuong -> Macro Touch -> Bat."
            )
            .setPositiveButton("Mo Cai dat Tro nang") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("Doi sang Go loi WiFi") { _, _ ->
                setTapModeUi(TAP_ADB)
            }
            .setNegativeButton("Dong", null)
            .show()
    }
}

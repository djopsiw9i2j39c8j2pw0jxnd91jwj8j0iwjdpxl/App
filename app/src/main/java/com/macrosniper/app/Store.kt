package com.macrosniper.app

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

enum class Kind { NUM, MAIN, AIM }

/** Cách app tạo cú chạm. */
const val TAP_ACC = 0 // dịch vụ Trợ năng (dispatchGesture)
const val TAP_ADB = 1 // Gỡ lỗi không dây (ADB tự nhúng, lệnh input tap)

/** Kiểu kích hoạt của nút main. */
const val TRIG_PRESS = 0    // chạy 1 lần khi ấn xuống
const val TRIG_RELEASE = 1  // chạy 1 lần khi thả tay
const val TRIG_HOLD = 2     // giữ tay: chuỗi lặp đi lặp lại, thả tay là dừng

/**
 * Một nút trên màn hình.
 * - NUM : nút macro số 1, 2, 3...  (là vị trí sẽ được "bấm"; số được đánh RIÊNG trong từng main)
 * - MAIN: nút trung tâm main1, main2... (nút bạn bấm để kích hoạt chuỗi)
 * - AIM : nút "Vuốt tâm" — vòng tròn chạm xuyên; ngón đặt xuống trong vòng rồi vuốt đi đâu thì quỹ đạo được làm mượt / chống rung / tăng lực
 */
data class MacroButton(
    val id: Int,
    var kind: Kind,
    var number: Int,       // NUM: thứ tự trong main của nó · MAIN: số của main
    var x: Int,            // tâm nút (px, toạ độ màn hình)
    var y: Int,
    var sizeDp: Int,
    var alphaPct: Int,     // 0..100 (0 = tàng hình khi chạy; lúc setup luôn hiện tối thiểu 20%)
    var delayMs: Int,      // chỉ NUM: độ trễ trước khi bấm (tốc độ ấn)
    var mainNo: Int,       // chỉ NUM: thuộc main số mấy (0 = chưa nối main nào)
    var trigger: Int,      // chỉ MAIN: TRIG_PRESS / TRIG_RELEASE / TRIG_HOLD
    var passThru: Boolean = false, // chỉ MAIN: cho cảm ứng xuyên xuống game (cần chế độ Gỡ lỗi WiFi để vẫn kích hoạt được)
    var name: String = "",  // tên tuỳ chỉnh hiện trên nút (rỗng = hiện số / mainN mặc định)
    // Vị trí nhớ RIÊNG cho từng hướng màn hình (-1 = chưa đặt). x,y ở trên là vị trí của hướng đang hiển thị.
    var pX: Int = -1, var pY: Int = -1,   // hướng dọc
    var lX: Int = -1, var lY: Int = -1,   // hướng ngang
    // chỉ AIM (Vuốt tâm)
    var aimSmooth: Int = AIM_SMOOTH_DEF,  // 0..100: độ mượt (0 = không lọc)
    var aimDead: Int = AIM_DEAD_DEF,      // 0..100: chống rung tay (vùng chết, 100 = ~24dp)
    var aimGain: Int = AIM_GAIN_DEF       // 100..400 (%): tăng lực vuốt
)

const val AIM_SMOOTH_DEF = 60
const val AIM_DEAD_DEF = 25
const val AIM_GAIN_DEF = 130

object Store {
    private fun p(ctx: Context) =
        ctx.getSharedPreferences("macro_sniper", Context.MODE_PRIVATE)

    fun isRunning(ctx: Context): Boolean = p(ctx).getBoolean("running", false)

    fun setRunning(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("running", v).apply()
    }

    // Máy Android 11+ mặc định dùng Gỡ lỗi WiFi (không cần Trợ năng); máy cũ hơn chỉ có Trợ năng.
    fun tapMode(ctx: Context): Int = p(ctx).getInt("tapMode", if (Build.VERSION.SDK_INT >= 30) TAP_ADB else TAP_ACC)

    fun setTapMode(ctx: Context, m: Int) {
        p(ctx).edit().putInt("tapMode", m).apply()
    }

    /** Tiếp quản ngón thật SỚM (mượt hơn, không khựng giữa chừng). Mặc định bật. */
    fun earlyTake(ctx: Context): Boolean = p(ctx).getBoolean("earlyTake", true)

    fun setEarlyTake(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("earlyTake", v).apply()
    }

    /**
     * Mở khóa chạm xuyên (Android 12+, chỉ chế độ Gỡ lỗi WiFi): dùng quyền shell của ADB tắt cơ chế chặn cú chạm xuyên
     * qua cửa sổ nổi (block_untrusted_touches) để nút số / tâm / vòng / nút main "xuyên" xuống game 100%, không bị chặn.
     * Mặc định bật. Giá trị gốc của hệ thống được nhớ lại và tự khôi phục khi ngắt Gỡ lỗi WiFi.
     */
    fun unblockTouch(ctx: Context): Boolean = p(ctx).getBoolean("unblockTouch", true)

    fun setUnblockTouch(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("unblockTouch", v).apply()
    }

    /** Giá trị gốc của block_untrusted_touches trước khi app đổi (null = chưa đổi / đã khôi phục; "null" = hệ thống chưa đặt giá trị). */
    fun butOrig(ctx: Context): String? = p(ctx).getString("butOrig", null)

    fun setButOrig(ctx: Context, v: String?) {
        val e = p(ctx).edit()
        if (v == null) e.remove("butOrig") else e.putString("butOrig", v)
        e.apply()
    }

    // ---- Tâm ảo (crosshair) + vòng tròn quanh tâm: cài đặt chung, không gắn với từng macro
    fun crossOn(ctx: Context) = p(ctx).getBoolean("crossOn", false)
    fun sliderStep(ctx: Context, key: String) = p(ctx).getInt("sliderStep_$key", 10) // bước nút -/+ của từng thanh trượt (1 hoặc 10)
    fun setSliderStep(ctx: Context, key: String, v: Int) = p(ctx).edit().putInt("sliderStep_$key", v).apply()
    fun crossSize(ctx: Context) = p(ctx).getInt("crossSize", 28)       // dp, đường kính hình tâm
    fun crossAlpha(ctx: Context) = p(ctx).getInt("crossAlpha", 90)     // %
    fun ringOn(ctx: Context) = p(ctx).getBoolean("ringOn", false)
    fun ringSize(ctx: Context) = p(ctx).getInt("ringSize", 120)        // dp, đường kính vòng tròn
    fun ringStroke(ctx: Context) = p(ctx).getInt("ringStroke", 2)      // dp, độ dày nét
    fun ringAlpha(ctx: Context) = p(ctx).getInt("ringAlpha", 70)       // %

    fun setCrossOn(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("crossOn", v).apply()
    fun setCrossSize(ctx: Context, v: Int) = p(ctx).edit().putInt("crossSize", v).apply()
    fun setCrossAlpha(ctx: Context, v: Int) = p(ctx).edit().putInt("crossAlpha", v).apply()
    fun setRingOn(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("ringOn", v).apply()
    fun setRingSize(ctx: Context, v: Int) = p(ctx).edit().putInt("ringSize", v).apply()
    fun setRingStroke(ctx: Context, v: Int) = p(ctx).edit().putInt("ringStroke", v).apply()
    fun setRingAlpha(ctx: Context, v: Int) = p(ctx).edit().putInt("ringAlpha", v).apply()

    /** Vị trí bong bóng, nhớ riêng cho hướng dọc / ngang. */
    fun bubblePos(ctx: Context, land: Boolean): Pair<Int, Int>? {
        val s = p(ctx)
        val kx = if (land) "blx" else "bx"
        val ky = if (land) "bly" else "by"
        return if (s.contains(kx)) Pair(s.getInt(kx, 0), s.getInt(ky, 0)) else null
    }

    fun setBubblePos(ctx: Context, x: Int, y: Int, land: Boolean) {
        val kx = if (land) "blx" else "bx"
        val ky = if (land) "bly" else "by"
        p(ctx).edit().putInt(kx, x).putInt(ky, y).apply()
    }

    fun encode(list: List<MacroButton>): JSONArray {
        val arr = JSONArray()
        for (b in list) {
            val o = JSONObject()
            o.put("id", b.id)
            o.put("kind", b.kind.name)
            o.put("number", b.number)
            o.put("x", b.x)
            o.put("y", b.y)
            o.put("size", b.sizeDp)
            o.put("alpha", b.alphaPct)
            o.put("delay", b.delayMs)
            o.put("main", b.mainNo)
            o.put("trigger", b.trigger)
            o.put("name", b.name)
            o.put("pass", b.passThru)
            o.put("pX", b.pX)
            o.put("pY", b.pY)
            o.put("lX", b.lX)
            o.put("lY", b.lY)
            if (b.kind == Kind.AIM) {
                o.put("aimSmooth", b.aimSmooth)
                o.put("aimDead", b.aimDead)
                o.put("aimGain", b.aimGain)
            }
            arr.put(o)
        }
        return arr
    }

    fun decode(arr: JSONArray): MutableList<MacroButton> {
        val out = mutableListOf<MacroButton>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kind = when (o.optString("kind")) {
                "MAIN" -> Kind.MAIN
                "AIM" -> Kind.AIM
                else -> Kind.NUM
            }
            out.add(
                MacroButton(
                    id = o.optInt("id", i + 1),
                    kind = kind,
                    number = o.optInt("number", i + 1),
                    x = o.optInt("x", 300),
                    y = o.optInt("y", 300),
                    sizeDp = o.optInt("size", if (kind == Kind.MAIN) 72 else if (kind == Kind.AIM) 96 else 56),
                    alphaPct = o.optInt("alpha", 85).coerceIn(0, 100),
                    delayMs = o.optInt("delay", 120),
                    mainNo = o.optInt("main", 0),
                    // tương thích dữ liệu cũ (chỉ có onRelease true/false)
                    trigger = o.optInt("trigger", if (o.optBoolean("onRelease", false)) TRIG_RELEASE else TRIG_PRESS),
                    name = o.optString("name", ""),
                    passThru = o.optBoolean("pass", false),
                    pX = o.optInt("pX", -1),
                    pY = o.optInt("pY", -1),
                    lX = o.optInt("lX", -1),
                    lY = o.optInt("lY", -1),
                    aimSmooth = o.optInt("aimSmooth", AIM_SMOOTH_DEF).coerceIn(0, 100),
                    aimDead = o.optInt("aimDead", AIM_DEAD_DEF).coerceIn(0, 100),
                    aimGain = o.optInt("aimGain", AIM_GAIN_DEF).coerceIn(100, 400)
                )
            )
        }
        return out
    }

    fun saveCurrent(ctx: Context, list: List<MacroButton>) {
        p(ctx).edit().putString("current", encode(list).toString()).apply()
    }

    fun loadCurrent(ctx: Context): MutableList<MacroButton> {
        val s = p(ctx).getString("current", null) ?: return mutableListOf()
        return try {
            decode(JSONArray(s))
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun macrosObj(ctx: Context): JSONObject {
        val s = p(ctx).getString("macros", null) ?: return JSONObject()
        return try {
            JSONObject(s)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    fun macroNames(ctx: Context): List<String> {
        return macrosObj(ctx).keys().asSequence().toList().sorted()
    }

    fun saveMacro(ctx: Context, name: String, list: List<MacroButton>) {
        val o = macrosObj(ctx)
        o.put(name, encode(list))
        p(ctx).edit().putString("macros", o.toString()).apply()
    }

    fun loadMacro(ctx: Context, name: String): MutableList<MacroButton>? {
        val arr = macrosObj(ctx).optJSONArray(name) ?: return null
        return decode(arr)
    }

    fun deleteMacro(ctx: Context, name: String) {
        val o = macrosObj(ctx)
        o.remove(name)
        p(ctx).edit().putString("macros", o.toString()).apply()
    }
}

package com.macrosniper.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class Kind { NUM, MAIN }

/**
 * Một nút trên màn hình.
 * - NUM : nút macro số 1, 2, 3...  (là vị trí sẽ được "bấm")
 * - MAIN: nút trung tâm main1, main2... (nút bạn bấm để kích hoạt chuỗi)
 */
data class MacroButton(
    val id: Int,
    var kind: Kind,
    var number: Int,
    var x: Int,            // tâm nút (px, toạ độ màn hình)
    var y: Int,
    var sizeDp: Int,
    var alphaPct: Int,     // 20..100
    var delayMs: Int,      // chỉ NUM: độ trễ trước khi bấm (tốc độ ấn)
    var mainNo: Int,       // chỉ NUM: thuộc main số mấy (0 = không liên kết)
    var onRelease: Boolean // chỉ MAIN: false = kích hoạt khi ấn, true = khi thả tay
)

object Store {
    private fun p(ctx: Context) =
        ctx.getSharedPreferences("macro_sniper", Context.MODE_PRIVATE)

    fun isRunning(ctx: Context): Boolean = p(ctx).getBoolean("running", false)

    fun setRunning(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("running", v).apply()
    }

    fun bubblePos(ctx: Context): Pair<Int, Int>? {
        val s = p(ctx)
        return if (s.contains("bx")) Pair(s.getInt("bx", 0), s.getInt("by", 0)) else null
    }

    fun setBubblePos(ctx: Context, x: Int, y: Int) {
        p(ctx).edit().putInt("bx", x).putInt("by", y).apply()
    }

    private fun encode(list: List<MacroButton>): JSONArray {
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
            o.put("onRelease", b.onRelease)
            arr.put(o)
        }
        return arr
    }

    private fun decode(arr: JSONArray): MutableList<MacroButton> {
        val out = mutableListOf<MacroButton>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kind = if (o.optString("kind") == "MAIN") Kind.MAIN else Kind.NUM
            out.add(
                MacroButton(
                    id = o.optInt("id", i + 1),
                    kind = kind,
                    number = o.optInt("number", i + 1),
                    x = o.optInt("x", 300),
                    y = o.optInt("y", 300),
                    sizeDp = o.optInt("size", if (kind == Kind.MAIN) 72 else 56),
                    alphaPct = o.optInt("alpha", 85),
                    delayMs = o.optInt("delay", 120),
                    mainNo = o.optInt("main", 0),
                    onRelease = o.optBoolean("onRelease", false)
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

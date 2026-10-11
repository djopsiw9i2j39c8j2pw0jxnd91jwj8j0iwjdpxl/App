package com.macrosniper.app

import org.json.JSONObject

/**
 * Xuất / nhập macro ra tệp .json để chia sẻ cho nhau.
 * Tệp ghi kèm cỡ màn hình của máy xuất; khi nhập, vị trí nút được co giãn theo TỈ LỆ sang cỡ màn hình máy nhận.
 */
object MacroShare {
    const val FORMAT = "macrotouch-macro"
    const val VERSION = 1
    const val MAX_BYTES = 1_000_000
    private const val MAX_BUTTONS = 200

    // dữ liệu chờ xuất (tránh nhét tệp lớn vào Intent)
    @Volatile
    var pendingExport: String? = null

    @Volatile
    var pendingName: String = "macro"

    fun toJson(name: String, list: List<MacroButton>, scrW: Int, scrH: Int): String {
        val o = JSONObject()
        o.put("format", FORMAT)
        o.put("version", VERSION)
        val screen = JSONObject()
        screen.put("short", minOf(scrW, scrH))
        screen.put("long", maxOf(scrW, scrH))
        screen.put("land", scrW > scrH)
        o.put("screen", screen)
        val macros = JSONObject()
        macros.put(name, Store.encode(list))
        o.put("macros", macros)
        return o.toString(2)
    }

    /** Trả về danh sách (tên, các nút) đã co giãn theo màn hình máy này; null nếu tệp không phải macro hợp lệ. */
    fun fromJson(text: String, myShort: Int, myLong: Int): List<Pair<String, MutableList<MacroButton>>>? {
        val o = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        if (o.optString("format") != FORMAT) return null
        val macros = o.optJSONObject("macros") ?: return null

        val sc = o.optJSONObject("screen")
        val refShort = sc?.optInt("short", 0) ?: 0
        val refLong = sc?.optInt("long", 0) ?: 0
        val srcLand = sc?.optBoolean("land", false) ?: false
        val sS = if (refShort > 0 && myShort > 0) myShort.toFloat() / refShort else 1f
        val sL = if (refLong > 0 && myLong > 0) myLong.toFloat() / refLong else 1f

        val out = ArrayList<Pair<String, MutableList<MacroButton>>>()
        val keys = macros.keys()
        while (keys.hasNext()) {
            val raw = keys.next()
            val arr = macros.optJSONArray(raw) ?: continue
            val list = Store.decode(arr)
            if (list.isEmpty() || list.size > MAX_BUTTONS) continue
            for (b in list) {
                // cạnh ngắn / cạnh dài của màn hình: hướng dọc x theo cạnh ngắn, y theo cạnh dài; hướng ngang ngược lại
                if (srcLand) {
                    b.x = Math.round(b.x * sL); b.y = Math.round(b.y * sS)
                } else {
                    b.x = Math.round(b.x * sS); b.y = Math.round(b.y * sL)
                }
                if (b.pX >= 0 && b.pY >= 0) {
                    b.pX = Math.round(b.pX * sS); b.pY = Math.round(b.pY * sL)
                }
                if (b.lX >= 0 && b.lY >= 0) {
                    b.lX = Math.round(b.lX * sL); b.lY = Math.round(b.lY * sS)
                }
                b.sizeDp = b.sizeDp.coerceIn(16, 400)
                b.delayMs = b.delayMs.coerceIn(1, 10000)
                if (b.name.length > 20) b.name = b.name.take(20)
            }
            val name = raw.trim().take(40).ifEmpty { "macro" }
            out.add(Pair(name, list))
        }
        return if (out.isEmpty()) null else out
    }
}

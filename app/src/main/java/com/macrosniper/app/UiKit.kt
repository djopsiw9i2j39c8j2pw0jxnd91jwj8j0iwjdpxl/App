package com.macrosniper.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import kotlin.math.hypot

object Theme {
    val ACCENT = Color.parseColor("#8DFF3F")
    val ACCENT2 = Color.parseColor("#3FD91C")
    val LIME = Color.parseColor("#E4FF6B")
    val TEXT = Color.parseColor("#F2FFF0")
    val MUTED = Color.parseColor("#9AA79A")
    val DANGER = Color.parseColor("#FF5A5A")
    val PANEL = Color.parseColor("#F2101510")
    val FIELD = Color.parseColor("#1C241B")
    val STROKE = Color.parseColor("#3C7A24")
}

fun roundedBg(fill: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.shape = GradientDrawable.RECTANGLE
    g.setColor(fill)
    g.cornerRadius = radiusPx
    if (strokePx > 0) g.setStroke(strokePx, strokeColor)
    return g
}

/** Nút tròn trên màn hình: số (NUM) hoặc "mainN" (MAIN). */
class BtnView(ctx: Context, val m: MacroButton) : View(ctx) {
    var hilite: Boolean = false
        set(v) {
            field = v
            invalidate()
        }
    var pressedFx: Boolean = false
        set(v) {
            field = v
            invalidate()
        }
    /** Chế độ setup: hiện thêm nhãn nhỏ "mN" dưới số để biết nút số thuộc main nào. */
    var showTag: Boolean = false
        set(v) {
            field = v
            invalidate()
        }
    private var flashing = false

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    fun flashFx() {
        flashing = true
        invalidate()
        postDelayed({
            flashing = false
            invalidate()
        }, 90)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f
        val isMain = m.kind == Kind.MAIN
        val col = if (isMain) Theme.LIME else Theme.ACCENT

        // quầng sáng ngoài
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = col
        p.alpha = if (flashing || pressedFx) 150 else 40
        c.drawCircle(cx, cy, r, p)

        // thân nút
        val br = r * 0.84f
        p.shader = RadialGradient(
            cx, cy - br * 0.35f, br * 1.3f,
            intArrayOf(Color.parseColor("#F0243320"), Color.parseColor("#F00A0E0A")),
            null, Shader.TileMode.CLAMP
        )
        p.alpha = 255
        c.drawCircle(cx, cy, br, p)
        p.shader = null

        // viền
        p.style = Paint.Style.STROKE
        p.strokeWidth = br * 0.09f
        p.color = if (hilite) Color.WHITE else col
        p.alpha = 255
        c.drawCircle(cx, cy, br - p.strokeWidth / 2f, p)

        // chữ: tên tuỳ chỉnh (nếu có) hoặc số / mainN. Chữ luôn tự co / xuống dòng / cắt bớt cho nằm gọn trong nút.
        val custom = m.name.trim()
        val label = when {
            custom.isNotEmpty() -> custom
            isMain -> "main${m.number}"
            else -> m.number.toString()
        }
        val base = when {
            custom.isNotEmpty() -> if (isMain) br * 0.5f else br * 0.62f
            isMain -> br * 0.5f
            label.length >= 3 -> br * 0.7f
            label.length == 2 -> br * 0.9f
            else -> br * 1.05f
        }
        val tag = showTag && !isMain && m.mainNo > 0
        val tagText = if (custom.isNotEmpty()) "${m.number}·m${m.mainNo}" else "m${m.mainNo}"
        tp.color = col
        val maxW = br * 1.52f
        val (lines, size) = fitLabel(label, base, maxW, br)
        tp.textSize = size
        val fm = tp.fontMetrics
        val lineH = (fm.descent - fm.ascent) * 0.95f
        val shiftUp = if (tag) br * 0.12f else 0f
        val total = lineH * lines.size
        var y = cy - total / 2f - fm.ascent - shiftUp
        for (ln in lines) {
            c.drawText(ln, cx, y, tp)
            y += lineH
        }

        if (tag) {
            tp.textSize = br * 0.28f
            tp.color = Theme.LIME
            val f2 = tp.fontMetrics
            c.drawText(tagText, cx, cy + br * 0.62f - (f2.ascent + f2.descent) / 2f, tp)
        }
    }

    /**
     * Xếp chữ cho vừa bề ngang [maxW]: ưu tiên 1 dòng co nhỏ dần; nếu vẫn dài và có dấu cách thì tách 2 dòng;
     * cuối cùng cắt bớt và thêm "…". Trả về (các dòng, cỡ chữ).
     */
    private fun fitLabel(text: String, base: Float, maxW: Float, br: Float): Pair<List<String>, Float> {
        val minOne = maxOf(br * 0.26f, 8f)
        var sz = base
        tp.textSize = sz
        val w1 = tp.measureText(text)
        if (w1 > maxW) sz = maxOf(minOne, base * maxW / w1)
        tp.textSize = sz
        if (tp.measureText(text) <= maxW) return Pair(listOf(text), sz)

        // 2 dòng (tách ở dấu cách gần giữa nhất)
        if (text.contains(' ')) {
            val mid = text.length / 2
            var cut = -1
            for (i in text.indices) {
                if (text[i] == ' ' && (cut < 0 || Math.abs(i - mid) < Math.abs(cut - mid))) cut = i
            }
            val a = text.substring(0, cut).trim()
            val b = text.substring(cut + 1).trim()
            if (a.isNotEmpty() && b.isNotEmpty()) {
                var s2 = minOf(base, br * 0.5f)
                tp.textSize = s2
                val wide = maxOf(tp.measureText(a), tp.measureText(b))
                if (wide > maxW) s2 = maxOf(br * 0.22f, s2 * maxW / wide)
                tp.textSize = s2
                if (maxOf(tp.measureText(a), tp.measureText(b)) <= maxW) return Pair(listOf(a, b), s2)
            }
        }

        // cắt bớt
        tp.textSize = minOne
        var t = text
        while (t.length > 1 && tp.measureText("$t…") > maxW) t = t.dropLast(1)
        return Pair(listOf("$t…"), minOne)
    }
}

/** Công tắc gạt gọn (thay cho cặp nút Bật / Tắt). Bấm để đổi trạng thái. */
class ToggleSwitch(ctx: Context) : View(ctx) {
    var checked: Boolean = false
        set(v) {
            field = v
            pos = if (v) 1f else 0f
            invalidate()
        }
    var onToggle: ((Boolean) -> Unit)? = null
    private var pos = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        setOnClickListener {
            val nv = !checked
            animateTo(nv)
            onToggle?.invoke(nv)
        }
    }

    private fun animateTo(on: Boolean) {
        val start = pos
        val target = if (on) 1f else 0f
        checked = on // đặt trạng thái thật ngay; thumb trượt mượt từ vị trí cũ
        pos = start
        val t0 = android.os.SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                val f = ((android.os.SystemClock.uptimeMillis() - t0) / 120f).coerceIn(0f, 1f)
                pos = start + (target - start) * f
                invalidate()
                if (f < 1f) postOnAnimation(this)
            }
        }
        postOnAnimation(step)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        p.style = Paint.Style.FILL
        p.color = if (checked) Theme.ACCENT2 else Theme.FIELD
        p.alpha = 255
        c.drawRoundRect(0f, 0f, w, h, r, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * resources.displayMetrics.density
        p.color = if (checked) Theme.ACCENT else Theme.STROKE
        c.drawRoundRect(p.strokeWidth / 2, p.strokeWidth / 2, w - p.strokeWidth / 2, h - p.strokeWidth / 2, r, r, p)
        val tr = r * 0.68f
        val cx = r + (w - 2 * r) * pos
        p.style = Paint.Style.FILL
        p.color = if (checked) Color.parseColor("#0B120A") else Theme.MUTED
        c.drawCircle(cx, h / 2f, tr, p)
    }
}

/** Bong bóng logo có thể kéo thả. */
class BubbleView(ctx: Context, private val logo: Bitmap?) : View(ctx) {
    var editing: Boolean = false
        set(v) {
            field = v
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: BitmapShader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val bmp = logo ?: return
        val br = minOf(w, h) / 2f * 0.86f
        val s = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val mx = Matrix()
        val scale = (2f * br) / bmp.width.toFloat()
        mx.setScale(scale, scale)
        mx.postTranslate(w / 2f - br, h / 2f - br)
        s.setLocalMatrix(mx)
        shader = s
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        val br = r * 0.86f
        val col = if (editing) Theme.LIME else Theme.ACCENT

        p.shader = null
        p.style = Paint.Style.FILL
        p.color = col
        p.alpha = if (editing) 110 else 55
        c.drawCircle(cx, cy, r, p)

        p.color = Color.parseColor("#161616")
        p.alpha = 255
        c.drawCircle(cx, cy, br, p)

        val s = shader
        if (s != null) {
            p.shader = s
            c.drawCircle(cx, cy, br, p)
            p.shader = null
        }

        p.style = Paint.Style.STROKE
        p.strokeWidth = br * 0.07f
        p.color = col
        p.alpha = 255
        c.drawCircle(cx, cy, br - p.strokeWidth / 2f, p)
    }
}

/** Tâm ảo ở giữa màn hình + (tuỳ chọn) vòng tròn quanh tâm. Mọi kích thước tính bằng px. */
class CrosshairView(ctx: Context) : View(ctx) {
    var showCross = true
    var crossPx = 0f
    var crossAlpha = 0.9f
    var showRing = false
    var ringPx = 0f      // đường kính vòng
    var strokePx = 2f
    var ringAlpha = 0.7f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Vẽ 2 lớp (viền tối mờ phía dưới) để nhìn rõ trên mọi nền game. */
    private fun stroke(c: Canvas, w: Float, a: Float, draw: (Paint) -> Unit) {
        p.strokeWidth = w + 2f * resources.displayMetrics.density
        p.color = Color.BLACK
        p.alpha = (a * 110).toInt()
        draw(p)
        p.strokeWidth = w
        p.color = Theme.ACCENT
        p.alpha = (a * 255).toInt()
        draw(p)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val d = resources.displayMetrics.density
        if (showRing && ringPx > 0f) {
            stroke(c, strokePx, ringAlpha) { c.drawCircle(cx, cy, ringPx / 2f - strokePx / 2f, it) }
        }
        if (showCross && crossPx > 0f) {
            val r = crossPx / 2f
            val gap = r * 0.28f
            val w = maxOf(1.5f * d, r * 0.09f)
            stroke(c, w, crossAlpha) {
                c.drawLine(cx - r, cy, cx - gap, cy, it)
                c.drawLine(cx + gap, cy, cx + r, cy, it)
                c.drawLine(cx, cy - r, cx, cy - gap, it)
                c.drawLine(cx, cy + gap, cx, cy + r, it)
            }
            val dot = maxOf(1.6f * d, r * 0.1f)
            fill.color = Color.BLACK
            fill.alpha = (crossAlpha * 110).toInt()
            c.drawCircle(cx, cy, dot + d, fill)
            fill.color = Theme.ACCENT
            fill.alpha = (crossAlpha * 255).toInt()
            c.drawCircle(cx, cy, dot, fill)
        }
    }
}

/**
 * Kéo thả một cửa sổ nổi. Chạm nhẹ (không kéo) -> onTap.
 * target: view thật sự được thêm vào WindowManager (mặc định chính view đang chạm).
 */
class DragListener(
    private val lp: WindowManager.LayoutParams,
    private val wm: WindowManager,
    private val slop: Int,
    private val target: View? = null,
    private val limit: () -> Pair<Int, Int>,
    private val onMove: () -> Unit,
    private val onTap: () -> Unit,
    private val onDragEnd: () -> Unit
) : View.OnTouchListener {

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                startX = lp.x
                startY = lp.y
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!moved && hypot(dx, dy) > slop) moved = true
                if (moved) {
                    val lim = limit()
                    lp.x = (startX + dx.toInt()).coerceIn(0, maxOf(0, lim.first))
                    lp.y = (startY + dy.toInt()).coerceIn(0, maxOf(0, lim.second))
                    try {
                        wm.updateViewLayout(target ?: v, lp)
                    } catch (_: Exception) {
                    }
                    onMove()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (moved) onDragEnd() else onTap()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (moved) onDragEnd()
            }
        }
        return true
    }
}

/** ScrollView giới hạn chiều cao tối đa. */
class MaxHeightScroll(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
        )
    }
}

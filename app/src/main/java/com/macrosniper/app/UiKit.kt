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

        // chữ
        val label = if (isMain) "main${m.number}" else m.number.toString()
        tp.color = col
        tp.textSize = when {
            isMain -> br * 0.5f
            label.length >= 3 -> br * 0.7f
            label.length == 2 -> br * 0.9f
            else -> br * 1.05f
        }
        val tag = showTag && !isMain && m.mainNo > 0
        val fm = tp.fontMetrics
        val shiftUp = if (tag) br * 0.12f else 0f
        c.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f - shiftUp, tp)

        if (tag) {
            tp.textSize = br * 0.28f
            tp.color = Theme.LIME
            val f2 = tp.fontMetrics
            c.drawText("m${m.mainNo}", cx, cy + br * 0.62f - (f2.ascent + f2.descent) / 2f, tp)
        }
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

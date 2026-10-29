package ua.controldex

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Проста іконка батареї: рівень 0–100, -1 = невідомо. Кольори як в оригіналі: <=5 червоний, <=20 жовтий. */
class BatteryView(ctx: Context) : View(ctx) {
    var level = -1
        set(v) { field = v; invalidate() }
    var charging = false
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) {
        val d = resources.displayMetrics.density
        setMeasuredDimension((56 * d).toInt(), (26 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val bw = width - 5 * d
        val body = RectF(1 * d, 1 * d, bw, height - 1 * d)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = 0xFF555555.toInt()
        c.drawRoundRect(body, 3 * d, 3 * d, p)
        p.style = Paint.Style.FILL
        c.drawRect(bw, height * 0.32f, width - 1 * d, height * 0.68f, p)
        if (level >= 0) {
            p.color = when { level <= 5 -> 0xFFD32F2F.toInt(); level <= 20 -> 0xFFF9A825.toInt(); else -> 0xFF43A047.toInt() }
            val inner = RectF(body.left + 3 * d, body.top + 3 * d, body.left + 3 * d + (body.width() - 6 * d) * level / 100f, body.bottom - 3 * d)
            c.drawRect(inner, p)
        }
        if (charging) {
            p.color = 0xFF000000.toInt(); p.textSize = 16 * d; p.textAlign = Paint.Align.CENTER
            c.drawText("⚡", body.centerX(), body.centerY() + 6 * d, p)
        }
    }
}

package autozone.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Small, consistent line icons; the adjacent text carries the accessible label. */
class VehicleIconView(context: Context, private val symbol: String, color: Int) : View(context) {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(width / 40f, height / 40f)
        when (symbol) {
            "fan" -> {
                canvas.drawCircle(20f, 20f, 3f, paint)
                repeat(3) {
                    canvas.save()
                    canvas.rotate(it * 120f, 20f, 20f)
                    canvas.drawPath(path.apply { reset()
                        moveTo(18f, 16f)
                        cubicTo(10f, 4f, 24f, 2f, 28f, 8f)
                        cubicTo(31f, 14f, 26f, 17f, 24f, 18f)
                    }, paint)
                    canvas.restore()
                }
            }
            "light" -> {
                canvas.drawPath(path.apply { reset()
                    moveTo(15f, 27f); lineTo(15f, 24f)
                    cubicTo(3f, 14f, 13f, 6f, 20f, 6f)
                    cubicTo(29f, 6f, 36f, 16f, 25f, 24f)
                    lineTo(25f, 27f); close()
                }, paint)
                canvas.drawLine(15f, 32f, 25f, 32f, paint)
                canvas.drawLine(17f, 36f, 23f, 36f, paint)
            }
            "air" -> {
                canvas.drawPath(path.apply { reset(); moveTo(5f, 12f); lineTo(25f, 12f); cubicTo(36f, 12f, 33f, 3f, 27f, 5f) }, paint)
                canvas.drawPath(path.apply { reset(); moveTo(3f, 20f); lineTo(30f, 20f); cubicTo(40f, 20f, 36f, 30f, 31f, 28f) }, paint)
                canvas.drawPath(path.apply { reset(); moveTo(7f, 28f); lineTo(18f, 28f); cubicTo(27f, 28f, 23f, 37f, 18f, 35f) }, paint)
            }
            "window" -> {
                canvas.drawPath(path.apply { reset(); moveTo(5f, 32f); lineTo(5f, 17f); lineTo(14f, 7f); lineTo(35f, 7f); lineTo(35f, 32f); close() }, paint)
                canvas.drawLine(10f, 25f, 30f, 25f, paint)
                canvas.drawLine(25f, 12f, 18f, 19f, paint)
                canvas.drawLine(30f, 13f, 23f, 20f, paint)
            }
        }
        canvas.restore()
    }
}

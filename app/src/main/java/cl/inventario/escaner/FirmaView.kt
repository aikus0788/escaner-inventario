package cl.inventario.escaner

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Recuadro para que el cliente firme con el dedo. */
class FirmaView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val trazos = mutableListOf<Path>()
    private var actual: Path? = null
    private val limites = RectF()
    private var conLimites = false
    private var hayFirma = false

    /** Se llama cuando el cliente termina un trazo. */
    var alCambiar: (() -> Unit)? = null

    private val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 30, 90)
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val guia = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(214, 217, 226)
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    val tieneFirma: Boolean get() = hayFirma

    fun borrar() {
        trazos.clear()
        actual = null
        hayFirma = false
        limites.setEmpty()
        conLimites = false
        invalidate()
        alCambiar?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val y = height * 0.78f
        canvas.drawLine(width * 0.08f, y, width * 0.92f, y, guia)
        trazos.forEach { canvas.drawPath(it, tinta) }
        actual?.let { canvas.drawPath(it, tinta) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Evita que la pantalla se desplace mientras se firma.
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                actual = Path().apply { moveTo(e.x, e.y); lineTo(e.x + 0.1f, e.y) }
                incluir(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) {
                    actual?.lineTo(e.getHistoricalX(i), e.getHistoricalY(i))
                    incluir(e.getHistoricalX(i), e.getHistoricalY(i))
                }
                actual?.lineTo(e.x, e.y)
                incluir(e.x, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                actual?.let { trazos.add(it) }
                actual = null
                hayFirma = true
                parent?.requestDisallowInterceptTouchEvent(false)
                alCambiar?.invoke()
            }
        }
        invalidate()
        return true
    }

    private fun incluir(x: Float, y: Float) {
        // Ojo: un rectángulo de un solo punto cuenta como «vacío», por eso se usa una marca aparte.
        if (!conLimites) {
            limites.set(x, y, x, y)
            conLimites = true
        } else {
            limites.union(x, y)
        }
    }

    /** La firma recortada, con fondo transparente, o null si no hay firma. */
    fun comoImagen(): Bitmap? {
        if (!hayFirma || trazos.isEmpty()) return null
        val margen = tinta.strokeWidth * 2
        val izq = (limites.left - margen).coerceAtLeast(0f)
        val arr = (limites.top - margen).coerceAtLeast(0f)
        val der = (limites.right + margen).coerceAtMost(width.toFloat())
        val aba = (limites.bottom + margen).coerceAtMost(height.toFloat())
        val w = (der - izq).toInt().coerceAtLeast(1)
        val h = (aba - arr).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(-izq, -arr)
        trazos.forEach { c.drawPath(it, tinta) }
        return bmp
    }
}

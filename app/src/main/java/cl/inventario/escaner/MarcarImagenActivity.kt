package cl.inventario.escaner

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Editor de una imagen de la OT: se raya o marca con el dedo (lápiz, círculo o flecha)
 * y se le deja un comentario. Al guardar, las marcas quedan pegadas en la imagen.
 */
class MarcarImagenActivity : AppCompatActivity() {

    private lateinit var lienzo: LienzoMarcas
    private lateinit var edtComentario: TextInputEditText
    private lateinit var archivo: File

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        archivo = File(intent.getStringExtra(EXTRA_ARCHIVO) ?: run { finish(); return })
        val base = cargar(archivo) ?: run {
            Toast.makeText(this, "No se pudo abrir la imagen.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val dp = resources.displayMetrics.density
        val azul = ContextCompat.getColor(this, R.color.verde)

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(24, 26, 32))
            fitsSystemWindows = true
        }

        // Barra superior
        val barra = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
        }
        val btnCancelar = MaterialButton(this).apply {
            text = "Cancelar"; setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT); elevation = 0f
            setOnClickListener { salirSinGuardar() }
        }
        val titulo = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITULO) ?: "Marcar imagen"
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val btnListo = MaterialButton(this).apply {
            text = "Listo"
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MarcarImagenActivity, R.color.naranja))
            setOnClickListener { guardar() }
        }
        barra.addView(btnCancelar, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        barra.addView(titulo, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        barra.addView(btnListo, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        raiz.addView(barra)

        // Imagen
        lienzo = LienzoMarcas(this).apply { this.base = base }
        raiz.addView(FrameLayout(this).apply { addView(lienzo, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Herramientas
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        }
        val fila1 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val grupo = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        val herramientas = listOf("Lápiz" to LienzoMarcas.Herramienta.LAPIZ, "Círculo" to LienzoMarcas.Herramienta.CIRCULO,
            "Flecha" to LienzoMarcas.Herramienta.FLECHA)
        herramientas.forEach { (n, h) ->
            val bt = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId(); text = n; textSize = 13f
                minWidth = 0; setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
            }
            grupo.addView(bt)
            if (h == LienzoMarcas.Herramienta.LAPIZ) grupo.check(bt.id)
            bt.setOnClickListener { lienzo.herramienta = h }
        }
        val btnDeshacer = MaterialButton(this).apply {
            text = "↶ Deshacer"; setTextColor(azul)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT); elevation = 0f
            setOnClickListener { lienzo.deshacer() }
        }
        fila1.addView(grupo, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        fila1.addView(btnDeshacer)
        panel.addView(fila1)

        // Colores
        val fila2 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt()) }
        fila2.addView(TextView(this).apply { text = "Color:"; setTextColor(Color.DKGRAY); textSize = 13f })
        val pintores = mutableListOf<(Boolean) -> Unit>()
        COLORES.forEach { col ->
            val v = View(this)
            val pintar: (Boolean) -> Unit = { sel ->
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(col)
                    setStroke(((if (sel) 3 else 1) * dp).toInt(), if (sel) Color.BLACK else Color.LTGRAY)
                }
            }
            pintar(col == lienzo.color)
            v.setOnClickListener {
                lienzo.color = col
                pintores.forEachIndexed { i, f -> f(COLORES[i] == col) }
            }
            pintores.add(pintar)
            fila2.addView(v, LinearLayout.LayoutParams((30 * dp).toInt(), (30 * dp).toInt()).apply { marginStart = (10 * dp).toInt() })
        }
        panel.addView(fila2)

        // Comentario
        val caja = TextInputLayout(this).apply {
            hint = "Comentario (opcional, sale bajo la imagen en el PDF)"
        }
        edtComentario = TextInputEditText(caja.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
            setText(intent.getStringExtra(EXTRA_COMENTARIO).orEmpty())
        }
        caja.addView(edtComentario)
        panel.addView(caja, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (4 * dp).toInt() })
        raiz.addView(panel)
        setContentView(raiz)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = salirSinGuardar()
        })
    }

    private fun hayCambios() = lienzo.hayMarcas() ||
        edtComentario.text?.toString().orEmpty().trim() != intent.getStringExtra(EXTRA_COMENTARIO).orEmpty().trim()

    private fun salirSinGuardar() {
        if (!hayCambios()) { setResult(Activity.RESULT_CANCELED); finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Salir sin guardar?")
            .setMessage("Se pierden las marcas y el comentario que hiciste en esta imagen.")
            .setPositiveButton("Salir") { _, _ -> setResult(Activity.RESULT_CANCELED); finish() }
            .setNegativeButton("Seguir editando", null)
            .show()
    }

    private fun guardar() {
        try {
            if (lienzo.hayMarcas()) {
                val final = lienzo.imagenFinal()
                val temp = File(archivo.parentFile, archivo.name + ".tmp")
                temp.outputStream().use { final.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                if (!temp.renameTo(archivo)) { temp.copyTo(archivo, overwrite = true); temp.delete() }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudieron guardar las marcas: ${e.message}", Toast.LENGTH_LONG).show()
        }
        setResult(Activity.RESULT_OK, Intent()
            .putExtra(EXTRA_ARCHIVO, archivo.absolutePath)
            .putExtra(EXTRA_COMENTARIO, edtComentario.text?.toString().orEmpty().trim()))
        finish()
    }

    /** Imagen a máx. 2000 px, ya girada según su orientación. */
    private fun cargar(f: File): Bitmap? {
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var m = 1
        while (maxOf(o.outWidth, o.outHeight) / (m * 2) >= 2000) m *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = m }) ?: return null
        return bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    companion object {
        const val EXTRA_ARCHIVO = "archivo"
        const val EXTRA_COMENTARIO = "comentario"
        const val EXTRA_TITULO = "titulo"
        val COLORES = listOf(Color.rgb(229, 57, 53), Color.rgb(253, 216, 53), Color.rgb(30, 91, 255), Color.rgb(67, 160, 71), Color.WHITE)

        fun intent(ctx: Context, f: File, comentario: String, titulo: String) = Intent(ctx, MarcarImagenActivity::class.java)
            .putExtra(EXTRA_ARCHIVO, f.absolutePath).putExtra(EXTRA_COMENTARIO, comentario).putExtra(EXTRA_TITULO, titulo)
    }
}

/** Muestra la imagen ajustada a la pantalla y guarda los trazos en coordenadas de la imagen. */
class LienzoMarcas(context: Context) : View(context) {

    enum class Herramienta { LAPIZ, CIRCULO, FLECHA }

    private class Trazo(val herramienta: Herramienta, val color: Int, val ancho: Float, val puntos: MutableList<Pair<Float, Float>>)

    var base: Bitmap? = null
        set(v) { field = v; invalidate() }
    var herramienta = Herramienta.LAPIZ
    var color = MarcarImagenActivity.COLORES[0]

    private val trazos = mutableListOf<Trazo>()
    private var actual: Trazo? = null
    private val matriz = Matrix()
    private val inversa = Matrix()

    fun hayMarcas() = trazos.isNotEmpty()
    fun deshacer() { if (trazos.isNotEmpty()) { trazos.removeAt(trazos.size - 1); invalidate() } }

    private fun anchoTrazo(): Float = base?.let { maxOf(it.width, it.height) * 0.009f } ?: 8f

    private fun calcularMatriz() {
        val b = base ?: return
        val e = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        matriz.setScale(e, e)
        matriz.postTranslate((width - b.width * e) / 2, (height - b.height * e) / 2)
        matriz.invert(inversa)
    }

    override fun onDraw(c: Canvas) {
        val b = base ?: return
        calcularMatriz()
        c.save()
        c.concat(matriz)
        c.drawBitmap(b, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        (trazos + listOfNotNull(actual)).forEach { dibujarTrazo(c, it) }
        c.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = base ?: return false
        val p = floatArrayOf(e.x, e.y).also { inversa.mapPoints(it) }
        val x = p[0].coerceIn(0f, b.width.toFloat())
        val y = p[1].coerceIn(0f, b.height.toFloat())
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                actual = Trazo(herramienta, color, anchoTrazo(), mutableListOf(x to y))
            }
            MotionEvent.ACTION_MOVE -> actual?.let {
                if (it.herramienta == Herramienta.LAPIZ) {
                    for (i in 0 until e.historySize) {
                        val h = floatArrayOf(e.getHistoricalX(i), e.getHistoricalY(i)).also { a -> inversa.mapPoints(a) }
                        it.puntos.add(h[0].coerceIn(0f, b.width.toFloat()) to h[1].coerceIn(0f, b.height.toFloat()))
                    }
                    it.puntos.add(x to y)
                } else {
                    if (it.puntos.size > 1) it.puntos[1] = x to y else it.puntos.add(x to y)
                }
            }
            MotionEvent.ACTION_UP -> actual?.let {
                if (it.herramienta != Herramienta.LAPIZ && it.puntos.size > 1) it.puntos[1] = x to y
                val (a, z) = it.puntos.first() to it.puntos.last()
                if (it.herramienta == Herramienta.LAPIZ || hypot(z.first - a.first, z.second - a.second) > it.ancho * 2) trazos.add(it)
                actual = null
            }
            MotionEvent.ACTION_CANCEL -> actual = null
        }
        invalidate()
        return true
    }

    private fun dibujarTrazo(c: Canvas, t: Trazo) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = t.color; style = Paint.Style.STROKE; strokeWidth = t.ancho
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        // Borde oscuro suave para que el trazo se vea sobre fondos claros u oscuros.
        val sombra = Paint(p).apply { color = Color.argb(90, 0, 0, 0); strokeWidth = t.ancho * 1.6f }
        val ruta = Path()
        val (a, z) = t.puntos.first() to t.puntos.last()
        when (t.herramienta) {
            Herramienta.LAPIZ -> {
                ruta.moveTo(a.first, a.second)
                if (t.puntos.size == 1) ruta.lineTo(a.first + 0.1f, a.second)
                t.puntos.drop(1).forEach { ruta.lineTo(it.first, it.second) }
            }
            Herramienta.CIRCULO -> ruta.addOval(RectF(minOf(a.first, z.first), minOf(a.second, z.second),
                maxOf(a.first, z.first), maxOf(a.second, z.second)), Path.Direction.CW)
            Herramienta.FLECHA -> {
                ruta.moveTo(a.first, a.second); ruta.lineTo(z.first, z.second)
                val ang = atan2(z.second - a.second, z.first - a.first)
                val largo = t.ancho * 4.5f
                for (d in listOf(-0.5f, 0.5f)) {
                    ruta.moveTo(z.first, z.second)
                    ruta.lineTo(z.first - largo * cos(ang + d), z.second - largo * sin(ang + d))
                }
            }
        }
        c.drawPath(ruta, sombra)
        c.drawPath(ruta, p)
    }

    /** Imagen con las marcas, en su tamaño real. */
    fun imagenFinal(): Bitmap {
        val b = base!!
        val out = b.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        trazos.forEach { dibujarTrazo(c, it) }
        return out
    }

    /** Para pruebas: agrega un trazo en coordenadas de la imagen. */
    fun agregarTrazo(h: Herramienta, col: Int, vararg pts: Pair<Float, Float>) {
        trazos.add(Trazo(h, col, anchoTrazo(), pts.toMutableList()))
    }
}

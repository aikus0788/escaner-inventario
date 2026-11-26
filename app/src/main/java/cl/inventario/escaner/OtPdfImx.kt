package cl.inventario.escaner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import java.io.File

/**
 * «Orden de trabajo IMX» (diseño minimalista con secciones numeradas).
 * Hoja A4 medida en puntos; la versión web (otpdf.js → crearPdfOtImx) usa las mismas medidas.
 */
object OtPdfImx {

    private const val ANCHO = 595
    private const val ALTO = 842

    private val TINTA = Color.rgb(20, 28, 60)
    private val GRIS = Color.rgb(107, 112, 128)
    private val LINEA = Color.rgb(209, 214, 224)
    private val NARANJA = Color.rgb(240, 147, 0)
    private val NARANJA2 = Color.rgb(240, 98, 20)

    private const val X0 = 50f
    private const val X1 = 545f
    private const val ALTO_FILA = 22f
    private const val TOP_TABLA = 336f
    private val ANCHOS = floatArrayOf(.3f, .12f, .18f, .19f, .21f)
    private val TITULOS = listOf("Tipo actividad", "# Equipos", "Modelo", "Tipo equipo", "Serie")

    /** Estados que se muestran como casillas. */
    val ESTADOS = OrdenTrabajo.ESTADOS

    fun generar(ctx: Context, ot: OrdenTrabajo, firma: Bitmap?, destino: File, imagenes: List<File> = emptyList()) {
        val logo = BitmapFactory.decodeResource(ctx.resources, R.drawable.logo_imx)
        val validas = imagenes.filter { it.exists() && it.length() > 0 }
        val paginas = 1 + (validas.size + 1) / 2
        val doc = PdfDocument()
        try {
            val p = doc.startPage(PdfDocument.PageInfo.Builder(ANCHO, ALTO, 1).create())
            dibujar(p.canvas, ot, firma, logo, paginas)
            doc.finishPage(p)
            validas.chunked(2).forEachIndexed { i, grupo ->
                val pg = doc.startPage(PdfDocument.PageInfo.Builder(ANCHO, ALTO, i + 2).create())
                val bitmaps = grupo.map { cargarReducida(it) }
                dibujarImagenes(pg.canvas, ot, bitmaps, i * 2, validas.size, logo, i + 2, paginas)
                doc.finishPage(pg)
                bitmaps.forEach { it?.recycle() }
            }
            destino.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    private fun cargarReducida(f: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var m = 1
        while (maxOf(o.outWidth, o.outHeight) / (m * 2) >= 1600) m *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = m })
    }

    // ---------------- Página 1 ----------------

    fun dibujar(c: Canvas, ot: OrdenTrabajo, firma: Bitmap?, logo: Bitmap?, paginas: Int = 1) {
        c.drawColor(Color.WHITE)
        encabezado(c, ot, logo, "Orden de trabajo", 52f)

        seccion(c, 1, "Datos de la visita", 132f)
        campo(c, "Cliente", ot.cliente, X0, 285f, 158f)
        campo(c, "Fecha", ot.fecha, 310f, X1, 158f)
        campo(c, "Local", ot.local, X0, 285f, 196f)
        campo(c, "Técnico", ot.tecnico, 310f, X1, 196f)
        campo(c, "Dirección", ot.direccion, X0, X1, 234f)

        // Estado con casillas; si el estado escrito no está en la lista se muestra aparte.
        texto(c, "Estado actividad", X0, 272f, 8.5f, GRIS)
        val marcado = ESTADOS.firstOrNull { it.equals(ot.estado.trim(), true) }
        ESTADOS.forEachIndexed { i, e ->
            val x = X0 + i * 99f
            c.drawRect(RectF(x, 279f, x + 11f, 290f), trazo(TINTA, 0.9f))
            if (e == marcado) c.drawRect(RectF(x + 2.5f, 281.5f, x + 8.5f, 287.5f), relleno(NARANJA2))
            texto(c, e, x + 17f, 288.5f, 10.5f, TINTA)
        }
        if (marcado == null && ot.estado.isNotBlank()) {
            texto(c, "Otro: ${ot.estado.trim()}", X1, 272f, 8.5f, NARANJA2, derecha = true, max = 200f)
        }

        seccion(c, 2, "Detalle de actividad", 322f)
        val xs = FloatArray(6).also { it[0] = X0; for (i in 0 until 5) it[i + 1] = it[i] + ANCHOS[i] * (X1 - X0) }
        TITULOS.forEachIndexed { i, t ->
            texto(c, t, (xs[i] + xs[i + 1]) / 2, TOP_TABLA + ALTO_FILA - 7f, 9f, TINTA, negrita = true, centro = true, max = xs[i + 1] - xs[i] - 4)
        }
        val filas = OrdenTrabajo.MAX_LINEAS
        val fin = TOP_TABLA + ALTO_FILA * (filas + 1)
        for (k in 1..filas) c.drawLine(X0, TOP_TABLA + ALTO_FILA * (k + 1), X1, TOP_TABLA + ALTO_FILA * (k + 1), trazo(LINEA, 0.7f))
        for (i in 1 until 5) c.drawLine(xs[i], TOP_TABLA + ALTO_FILA, xs[i], fin, trazo(LINEA, 0.7f))
        c.drawRect(RectF(X0, TOP_TABLA, X1, fin), trazo(LINEA, 0.7f))
        c.drawLine(X0, TOP_TABLA + ALTO_FILA, X1, TOP_TABLA + ALTO_FILA, trazo(TINTA, 1.2f))
        ot.lineas.filterNot { it.vacia() }.take(filas).forEachIndexed { k, l ->
            val base = TOP_TABLA + ALTO_FILA * (k + 2) - 7f
            listOf(l.actividadParaPdf, l.cantidad, l.modelo, l.tipoEquipo, l.serie).forEachIndexed { i, v ->
                val ancho = xs[i + 1] - xs[i] - 8
                if (i == 1) texto(c, v, (xs[i] + xs[i + 1]) / 2, base, 9.5f, TINTA, centro = true, max = ancho, cortar = true)
                else texto(c, v, xs[i] + 5f, base, 9.5f, TINTA, max = ancho, cortar = true)
            }
        }

        val tObs = fin + 30f
        seccion(c, 3, "Observaciones", tObs)
        for (k in 0 until 3) c.drawLine(X0, tObs + 30f + k * 20f, X1, tObs + 30f + k * 20f, trazo(LINEA, 0.7f))
        observaciones(c, ot.observaciones, tObs)

        val tCli = fin + 132f
        seccion(c, 4, "Conformidad del cliente", tCli)
        campo(c, "Nombre", ot.recibeNombre, X0, 230f, tCli + 26f)
        campo(c, "Cargo", ot.recibeCargo, X0, 230f, tCli + 66f)
        c.drawRoundRect(RectF(260f, tCli + 16f, X1, tCli + 100f), 4f, 4f, trazo(LINEA, 0.7f))
        texto(c, "Firma cliente", 402.5f, tCli + 94f, 8.5f, GRIS, centro = true)
        firma?.let { original ->
            val f = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888).also {
                val cf = Canvas(it); cf.drawColor(Color.WHITE); cf.drawBitmap(original, 0f, 0f, null)
            }
            val caja = RectF(270f, tCli + 20f, 535f, tCli + 84f)
            val e = minOf(caja.width() / f.width, caja.height() / f.height)
            val w = f.width * e
            val h = f.height * e
            c.drawBitmap(f, null, RectF(caja.centerX() - w / 2, caja.bottom - h, caja.centerX() + w / 2, caja.bottom),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        pie(c, 1, paginas)
    }

    // ---------------- Páginas de imágenes ----------------

    fun dibujarImagenes(c: Canvas, ot: OrdenTrabajo, imagenes: List<Bitmap?>, desde: Int, total: Int, logo: Bitmap?, pagina: Int, paginas: Int) {
        c.drawColor(Color.WHITE)
        encabezado(c, ot, logo, "Imágenes", 52f)
        val cajas = listOf(RectF(X0, 120f, X1, 455f), RectF(X0, 470f, X1, 795f))
        imagenes.forEachIndexed { i, bmp ->
            val caja = cajas[i]
            texto(c, "Imagen ${desde + i + 1} de $total", caja.left, caja.top + 2f, 10.5f, NARANJA2, negrita = true)
            val area = RectF(caja.left, caja.top + 10f, caja.right, caja.bottom)
            c.drawRoundRect(area, 4f, 4f, trazo(LINEA, 0.8f))
            if (bmp != null) {
                val e = minOf((area.width() - 8) / bmp.width, (area.height() - 8) / bmp.height)
                val w = bmp.width * e
                val h = bmp.height * e
                c.drawBitmap(bmp, null, RectF(area.centerX() - w / 2, area.centerY() - h / 2, area.centerX() + w / 2, area.centerY() + h / 2),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
        }
        pie(c, pagina, paginas)
    }

    // ---------------- Piezas ----------------

    private fun encabezado(c: Canvas, ot: OrdenTrabajo, logo: Bitmap?, titulo: String, @Suppress("UNUSED_PARAMETER") alto: Float) {
        logo?.let {
            val h = 52f
            val w = h * it.width / it.height
            c.drawBitmap(it, null, RectF(X0, 34f, X0 + w, 34f + h), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        c.drawLine(126f, 36f, 126f, 84f, trazo(LINEA, 0.7f))
        texto(c, titulo, 140f, 58f, 19f, TINTA, negrita = true, max = 260f)
        texto(c, "IMX · Servicio técnico", 140f, 76f, 10f, GRIS)
        if (ot.numero.isNotBlank()) {
            texto(c, "N°", X1, 50f, 9f, GRIS, derecha = true)
            texto(c, ot.numero, X1, 72f, 17f, NARANJA2, negrita = true, derecha = true, max = 140f)
        }
        c.drawRect(RectF(X0, 99f, X1, 101f), relleno(NARANJA))
    }

    private fun pie(c: Canvas, pagina: Int, paginas: Int) {
        c.drawLine(X0, 805f, X1, 805f, trazo(LINEA, 0.7f))
        texto(c, "Preparado por Infomaxis", X0, 820f, 8.5f, GRIS)
        texto(c, "Página $pagina de $paginas", X1, 820f, 8.5f, GRIS, derecha = true)
    }

    private fun seccion(c: Canvas, n: Int, titulo: String, top: Float) {
        c.drawCircle(60f, top - 4f, 9f, relleno(NARANJA))
        texto(c, n.toString(), 60f, top, 10f, Color.WHITE, negrita = true, centro = true)
        texto(c, titulo, 76f, top, 12f, TINTA, negrita = true)
    }

    private fun campo(c: Canvas, etiqueta: String, valor: String, x0: Float, x1: Float, top: Float) {
        texto(c, etiqueta, x0, top, 8.5f, GRIS)
        texto(c, valor.replace('\n', ' ').trim(), x0, top + 15f, 11f, TINTA, max = x1 - x0, cortar = true)
        c.drawLine(x0, top + 21f, x1, top + 21f, trazo(LINEA, 0.7f))
    }

    /** Observaciones sobre las 3 líneas guía; si no caben, se achica la letra. */
    private fun observaciones(c: Canvas, v: String, top: Float) {
        val t = v.trim()
        if (t.isEmpty()) return
        var s = 10.5f
        var lineas = partir(t, s)
        if (lineas.size <= 3) {
            lineas.forEachIndexed { i, l -> texto(c, l, X0, top + 26f + i * 20f, s, TINTA) }
            return
        }
        while (s > 6.5f) {
            s -= 0.5f
            lineas = partir(t, s)
            if (lineas.size * s * 1.2f <= 64f) break
        }
        lineas.forEachIndexed { i, l ->
            val base = top + 10f + s + i * s * 1.2f
            if (base <= top + 76f) texto(c, l, X0, base, s, TINTA)
        }
    }

    private fun partir(t: String, s: Float): List<String> {
        val p = pincel(s, false, TINTA)
        val out = mutableListOf<String>()
        for (parrafo in t.split('\n')) {
            var actual = ""
            for (palabra in parrafo.split(Regex("\\s+"))) {
                val prueba = if (actual.isEmpty()) palabra else "$actual $palabra"
                if (p.measureText(prueba) <= X1 - X0 || actual.isEmpty()) actual = prueba
                else { out.add(actual); actual = palabra }
            }
            out.add(actual)
        }
        return out
    }

    private fun pincel(tam: Float, negrita: Boolean, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = tam
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (negrita) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun trazo(color: Int, ancho: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = ancho
    }

    private fun relleno(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    private fun texto(
        c: Canvas, v: String, x: Float, base: Float, tam: Float, color: Int, negrita: Boolean = false,
        centro: Boolean = false, derecha: Boolean = false, max: Float = 0f, cortar: Boolean = false,
    ) {
        var t = v
        if (t.isEmpty()) return
        val p = pincel(tam, negrita, color)
        if (max > 0) {
            while (p.measureText(t) > max && p.textSize > 6.5f) p.textSize -= 0.25f
            if (cortar && p.measureText(t) > max) {
                while (t.length > 1 && p.measureText("$t…") > max) t = t.dropLast(1)
                t = "$t…"
            }
        }
        val w = p.measureText(t)
        c.drawText(t, if (centro) x - w / 2 else if (derecha) x - w else x, base, p)
    }
}

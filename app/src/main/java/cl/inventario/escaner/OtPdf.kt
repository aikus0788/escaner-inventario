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
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/**
 * Dibuja la «Orden de trabajo» NCR Voyix con las mismas medidas del formulario original
 * (hoja A4, medidas en puntos) y escribe los datos en cada casilla.
 */
object OtPdf {

    const val ANCHO = 595
    const val ALTO = 842

    private val VERDE = Color.rgb(36, 148, 95)

    /** Líneas del formulario: x0, y0, x1, y1 (rectángulos negros rellenos, igual que el original). */
    private val LINEAS = arrayOf(
        floatArrayOf(214.32f, 70.68f, 216.24f, 94.8f), floatArrayOf(214.8f, 94.8f, 215.76f, 261.72f),
        floatArrayOf(214.8f, 287.76f, 215.76f, 309.96f), floatArrayOf(279.24f, 287.76f, 280.2f, 309.96f),
        floatArrayOf(355.8f, 287.76f, 356.76f, 309.96f), floatArrayOf(432.36f, 287.76f, 433.32f, 309.96f),
        floatArrayOf(214.8f, 311.88f, 215.76f, 527.04f), floatArrayOf(49.92f, 68.76f, 51.84f, 743.28f),
        floatArrayOf(214.8f, 528.96f, 215.76f, 644.88f), floatArrayOf(537.48f, 70.68f, 539.4f, 743.28f),
        floatArrayOf(279.24f, 311.88f, 280.2f, 527.04f), floatArrayOf(355.8f, 311.88f, 356.76f, 527.04f),
        floatArrayOf(432.36f, 311.88f, 433.32f, 527.04f), floatArrayOf(51.84f, 68.76f, 539.4f, 70.68f),
        floatArrayOf(51.84f, 92.88f, 539.4f, 94.8f), floatArrayOf(51.84f, 117.48f, 537.48f, 118.44f),
        floatArrayOf(51.84f, 141.6f, 537.48f, 142.56f), floatArrayOf(51.84f, 165.72f, 537.48f, 166.68f),
        floatArrayOf(51.84f, 189.84f, 537.48f, 190.8f), floatArrayOf(51.84f, 213.96f, 537.48f, 214.92f),
        floatArrayOf(51.84f, 238.08f, 537.48f, 239.04f), floatArrayOf(51.84f, 261.72f, 539.4f, 263.64f),
        floatArrayOf(51.84f, 285.84f, 539.4f, 287.76f), floatArrayOf(51.84f, 309.96f, 539.4f, 311.88f),
        floatArrayOf(51.84f, 334.56f, 537.48f, 335.52f), floatArrayOf(51.84f, 358.68f, 537.48f, 359.64f),
        floatArrayOf(51.84f, 382.8f, 537.48f, 383.76f), floatArrayOf(51.84f, 406.92f, 537.48f, 407.88f),
        floatArrayOf(51.84f, 431.04f, 537.48f, 432.0f), floatArrayOf(51.84f, 455.16f, 537.48f, 456.12f),
        floatArrayOf(51.84f, 479.28f, 537.48f, 480.24f), floatArrayOf(51.84f, 503.4f, 537.48f, 504.36f),
        floatArrayOf(51.84f, 527.04f, 539.4f, 528.96f), floatArrayOf(51.84f, 597.12f, 537.48f, 598.08f),
        floatArrayOf(51.84f, 621.24f, 537.48f, 622.2f), floatArrayOf(51.84f, 644.88f, 539.4f, 646.8f),
        floatArrayOf(51.84f, 741.36f, 539.4f, 743.28f),
    )

    /** Filas de «Seguimiento»: borde inferior de cada casilla (los datos van alineados con la etiqueta). */
    private val FILAS_DATOS = floatArrayOf(117.48f, 141.6f, 165.72f, 189.84f, 213.96f, 238.08f, 261.72f)
    private val ETIQUETAS = listOf("Numero de orden", "Fecha", "Cliente", "Local", "Direccion", "Nombre tecnico", "Estado actividad")

    /** Detalle de actividad: 9 filas (arriba, abajo) y 5 columnas (izquierda, derecha). */
    private val FILAS_DETALLE = arrayOf(
        311.88f to 334.56f, 335.52f to 358.68f, 359.64f to 382.8f, 383.76f to 406.92f, 407.88f to 431.04f,
        432.0f to 455.16f, 456.12f to 479.28f, 480.24f to 503.4f, 504.36f to 527.04f,
    )
    private val COLUMNAS = arrayOf(51.84f to 214.8f, 215.76f to 279.24f, 280.2f to 355.8f, 356.76f to 432.36f, 433.32f to 537.48f)
    private val TITULOS_COLUMNAS = listOf("Tipo Actividad", "# Equipos", "Modelo", "Tipo equipo", "Serie")

    private const val X_DATOS = 219.5f
    private const val X_FIN = 534.5f

    /** Crea el PDF de la OT en [destino]; las [imagenes] van en páginas adicionales, dos por hoja. */
    fun generar(
        ctx: Context, ot: OrdenTrabajo, firma: Bitmap?, destino: File, imagenes: List<File> = emptyList(),
        comentarios: List<String> = emptyList(),
    ) {
        val logo = BitmapFactory.decodeResource(ctx.resources, R.drawable.logo_ncr_voyix)
        val doc = PdfDocument()
        try {
            val pagina = doc.startPage(PdfDocument.PageInfo.Builder(ANCHO, ALTO, 1).create())
            dibujar(pagina.canvas, ot, firma, logo)
            doc.finishPage(pagina)

            val validas = imagenes.mapIndexed { i, f -> f to comentarios.getOrElse(i) { "" } }.filter { it.first.exists() && it.first.length() > 0 }
            validas.chunked(POR_PAGINA).forEachIndexed { i, grupo ->
                val p = doc.startPage(PdfDocument.PageInfo.Builder(ANCHO, ALTO, i + 2).create())
                val bitmaps = grupo.map { cargarReducida(it.first) }
                dibujarImagenes(p.canvas, ot, bitmaps, i * POR_PAGINA, validas.size, logo, grupo.map { it.second })
                doc.finishPage(p)
                bitmaps.forEach { it?.recycle() }
            }
            destino.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    private const val POR_PAGINA = 2

    /** Alto reservado bajo la imagen para su comentario. */
    const val ALTO_COMENTARIO = 38f

    /** Imagen reducida (máx. 1600 px) para no llenar la memoria. */
    private fun cargarReducida(f: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var m = 1
        while (maxOf(o.outWidth, o.outHeight) / (m * 2) >= 1600) m *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = m })
    }

    /** Página de imágenes: encabezado de la OT y hasta 2 imágenes con su número. */
    fun dibujarImagenes(
        c: Canvas, ot: OrdenTrabajo, imagenes: List<Bitmap?>, desde: Int, total: Int, logo: Bitmap?,
        comentarios: List<String> = emptyList(),
    ) {
        c.drawColor(Color.WHITE)
        logo?.let { c.drawBitmap(it, null, RectF(50.4f, 21.6f, 211.6f, 47.0f), Paint(Paint.FILTER_BITMAP_FLAG)) }
        val titulo = "ORDEN DE TRABAJO" + (ot.numero.takeIf { it.isNotBlank() }?.let { " N° $it" } ?: "") + " · IMÁGENES"
        texto(c, titulo, 297.6f, 61.5f, Alinear.CENTRO, 490f)
        val cajas = listOf(RectF(51.84f, 78f, 539.4f, 438f), RectF(51.84f, 452f, 539.4f, 800f))
        val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(214, 217, 226); style = Paint.Style.STROKE; strokeWidth = 0.8f }
        imagenes.forEachIndexed { i, bmp ->
            val caja = cajas[i]
            texto(c, "Imagen ${desde + i + 1} de $total", caja.left, caja.top + 11f, Alinear.IZQ, 300f, negrita = true, color = VERDE)
            val marco = RectF(caja.left, caja.top + 18f, caja.right, caja.bottom)
            c.drawRect(marco, borde)
            val comentario = comentarios.getOrElse(i) { "" }.trim()
            val area = if (comentario.isEmpty()) marco else RectF(marco.left, marco.top, marco.right, marco.bottom - ALTO_COMENTARIO)
            if (comentario.isNotEmpty()) {
                c.drawLine(marco.left, area.bottom, marco.right, area.bottom, borde)
                parrafo(c, "Comentario: $comentario", RectF(marco.left + 6f, area.bottom + 4f, marco.right - 6f, marco.bottom - 3f))
            }
            if (bmp != null) {
                val e = minOf((area.width() - 8) / bmp.width, (area.height() - 8) / bmp.height)
                val w = bmp.width * e
                val h = bmp.height * e
                val x = area.centerX() - w / 2
                val y = area.centerY() - h / 2
                c.drawBitmap(bmp, null, RectF(x, y, x + w, y + h), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
        }
        texto(c, "Preparado por Infomaxis", 51.4f, 815.7f, Alinear.IZQ, 300f)
    }

    /** Dibuja la OT en un lienzo medido en puntos (595 × 842). */
    fun dibujar(c: Canvas, ot: OrdenTrabajo, firma: Bitmap?, logo: Bitmap?) {
        c.drawColor(Color.WHITE)
        val negro = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }

        // Logo y título
        logo?.let { c.drawBitmap(it, null, RectF(50.4f, 21.6f, 211.6f, 47.0f), Paint(Paint.FILTER_BITMAP_FLAG)) }
        texto(c, "ORDEN DE TRABAJO", 297.6f, 61.5f, Alinear.CENTRO, ANCHO.toFloat())

        // Líneas de la tabla
        for (r in LINEAS) c.drawRect(r[0], r[1], r[2], r[3], negro)

        // Encabezados verdes y etiquetas
        texto(c, "SEGUIMIENTO NCR EXTERNAL", 52.8f, 89.6f, Alinear.IZQ, 160f, negrita = true, color = VERDE)
        texto(c, "DATOS A COMPLETAR", 376.6f, 89.6f, Alinear.CENTRO, 300f, negrita = true, color = VERDE)
        ETIQUETAS.forEachIndexed { i, e -> texto(c, e, 52.8f, FILAS_DATOS[i] - 2.9f, Alinear.IZQ, 160f) }
        texto(c, "Detalle de actividad", 294.7f, 282.6f, Alinear.CENTRO, 480f, negrita = true, color = VERDE)
        COLUMNAS.forEachIndexed { i, (x0, x1) ->
            texto(c, TITULOS_COLUMNAS[i], (x0 + x1) / 2, 306.6f, Alinear.CENTRO, x1 - x0 - 4, negrita = true, color = VERDE)
        }
        texto(c, "Observaciones", 52.8f, 566.8f, Alinear.IZQ, 160f)
        texto(c, "Nombre quien recibe", 52.8f, 618.3f, Alinear.IZQ, 160f)
        texto(c, "Cargo quien recibe", 52.8f, 641.5f, Alinear.IZQ, 160f)
        texto(c, "Firma cliente", 294.7f, 739.0f, Alinear.CENTRO, 300f)
        texto(c, "Preparado por Infomaxis", 51.4f, 815.7f, Alinear.IZQ, 300f)

        // Datos
        val datos = listOf(ot.numero, ot.fecha, ot.cliente, ot.local, ot.direccion, ot.tecnico, ot.estado)
        datos.forEachIndexed { i, v -> dato(c, v, X_DATOS, FILAS_DATOS[i] - 2.9f, X_FIN - X_DATOS) }

        ot.lineas.filterNot { it.vacia() }.take(OrdenTrabajo.MAX_LINEAS).forEachIndexed { i, l ->
            val abajo = FILAS_DETALLE[i].second
            val base = abajo - 7.2f
            val valores = listOf(l.actividadParaPdf, l.cantidad, l.modelo, l.tipoEquipo, l.serie)
            valores.forEachIndexed { j, v ->
                val (x0, x1) = COLUMNAS[j]
                if (j == 1) dato(c, v, (x0 + x1) / 2, base, x1 - x0 - 6, Alinear.CENTRO)
                else dato(c, v, x0 + 3.5f, base, x1 - x0 - 7)
            }
        }

        parrafo(c, ot.observaciones, RectF(X_DATOS, 531.5f, X_FIN, 595.5f))
        dato(c, ot.recibeNombre, X_DATOS, 618.3f, X_FIN - X_DATOS)
        dato(c, ot.recibeCargo, X_DATOS, 641.5f, X_FIN - X_DATOS)

        // Firma del cliente, centrada sobre «Firma cliente»
        firma?.let { original ->
            // Se pasa a fondo blanco opaco: algunos lectores de PDF no muestran imágenes con transparencia.
            val f = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888).also {
                val cf = Canvas(it)
                cf.drawColor(Color.WHITE)
                cf.drawBitmap(original, 0f, 0f, null)
            }
            val caja = RectF(130f, 650f, 460f, 727f)
            val escala = minOf(caja.width() / f.width, caja.height() / f.height)
            val w = f.width * escala
            val h = f.height * escala
            val x = caja.centerX() - w / 2
            val y = caja.bottom - h
            c.drawBitmap(f, null, RectF(x, y, x + w, y + h), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
    }

    // ---------------- Texto ----------------

    private enum class Alinear { IZQ, CENTRO }

    private fun pincel(tam: Float, negrita: Boolean, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = tam
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (negrita) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Texto fijo del formulario (11 pt); se achica un poco si no cabe. */
    private fun texto(
        c: Canvas, t: String, x: Float, base: Float, alinear: Alinear, maxAncho: Float,
        negrita: Boolean = false, color: Int = Color.BLACK,
    ) {
        val p = pincel(11f, negrita, color)
        while (p.measureText(t) > maxAncho && p.textSize > 7f) p.textSize -= 0.25f
        val x0 = if (alinear == Alinear.CENTRO) x - p.measureText(t) / 2 else x
        c.drawText(t, x0, base, p)
    }

    /** Dato escrito por el técnico: 10 pt, se achica hasta 6.5 pt y, si aún no cabe, se corta con «…». */
    private fun dato(c: Canvas, v: String, x: Float, base: Float, maxAncho: Float, alinear: Alinear = Alinear.IZQ) {
        var t = v.replace('\n', ' ').trim()
        if (t.isEmpty()) return
        val p = pincel(10f, false, Color.rgb(15, 30, 90))
        while (p.measureText(t) > maxAncho && p.textSize > 6.5f) p.textSize -= 0.25f
        if (p.measureText(t) > maxAncho) {
            while (t.length > 1 && p.measureText("$t…") > maxAncho) t = t.dropLast(1)
            t = "$t…"
        }
        val x0 = if (alinear == Alinear.CENTRO) x - p.measureText(t) / 2 else x
        c.drawText(t, x0, base, p)
    }

    /** Texto de varias líneas (Observaciones) dentro de [caja]; se achica hasta que quepa. */
    private fun parrafo(c: Canvas, v: String, caja: RectF) {
        val t = v.trim()
        if (t.isEmpty()) return
        val p = pincel(10f, false, Color.rgb(15, 30, 90))
        var capa: StaticLayout
        while (true) {
            capa = StaticLayout.Builder.obtain(t, 0, t.length, p, caja.width().toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build()
            if (capa.height <= caja.height() || p.textSize <= 6.5f) break
            p.textSize -= 0.5f
        }
        c.save()
        c.clipRect(caja)
        c.translate(caja.left, caja.top)
        capa.draw(c)
        c.restore()
    }
}

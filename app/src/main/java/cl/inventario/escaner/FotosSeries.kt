package cl.inventario.escaner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File

/**
 * Fotos que se sacaron para leer modelo y serie de cada componente.
 * Para enviarlas (por ejemplo, por WhatsApp) se les agrega arriba una franja con
 * la caja, el componente y el dato leído, así quien las recibe sabe qué es cada una.
 */
object FotosSeries {

    const val MODELO = "modelo"
    const val SERIE = "serie"

    /** Solo las fotos de la serie de cada componente, en el orden de la caja. */
    fun ordenadas(c: Caja): List<Pair<String, String>> =
        c.fotosSeries.entries.filter { it.key.endsWith("|$SERIE") }
            .sortedBy { e -> Caja.COMPONENTES.indexOf(e.key.substringBefore('|')).let { if (it < 0) 99 else it } }
            .map { it.key to it.value }

    fun cuantas(c: Caja) = ordenadas(c).size

    /** Texto de la franja: línea principal y detalle de la caja. */
    fun titulos(c: Caja, clave: String): Pair<String, String> {
        val comp = Caja.etiqueta(clave.substringBefore('|'), c.tipo)
        val campo = clave.substringAfter('|')
        val pieza = c.pieza(clave.substringBefore('|'))
        val valor = if (campo == MODELO) pieza.modelo else pieza.serie
        val principal = "$comp · ${if (campo == MODELO) "Modelo" else "Serie"}: ${valor.ifBlank { "—" }}"
        val detalle = listOf(
            c.trabajo,
            listOf(c.cliente, c.tienda.takeIf { it.isNotBlank() }?.let { "Tienda $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" "),
            c.tipo + (c.numeroPos.takeIf { it.isNotBlank() }?.let { " N° $it" } ?: ""),
            c.ubicacion,
            c.fecha,
        ).filter { it.isNotBlank() }.joinToString(" · ")
        return principal to detalle
    }

    /** Copia de la foto con la franja de datos arriba. */
    fun conFranja(original: Bitmap, principal: String, detalle: String): Bitmap {
        val w = original.width
        val alto = (w * 0.13f).coerceAtLeast(90f)
        val out = Bitmap.createBitmap(w, original.height + alto.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(8, 22, 90))
        c.drawRect(0f, alto - alto * 0.06f, w.toFloat(), alto, Paint().apply { color = Color.rgb(240, 147, 0) })
        c.drawBitmap(original, 0f, alto, Paint(Paint.FILTER_BITMAP_FLAG))
        val margen = w * 0.03f
        fun pincel(tam: Float, negrita: Boolean, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = tam; this.color = color
            typeface = Typeface.create(Typeface.SANS_SERIF, if (negrita) Typeface.BOLD else Typeface.NORMAL)
        }
        fun ajustar(p: Paint, t: String) { while (p.measureText(t) > w - 2 * margen && p.textSize > 8f) p.textSize *= 0.95f }
        val p1 = pincel(alto * 0.36f, true, Color.WHITE).also { ajustar(it, principal) }
        val p2 = pincel(alto * 0.22f, false, Color.rgb(200, 210, 255)).also { ajustar(it, detalle) }
        c.drawText(principal, margen, alto * 0.46f, p1)
        c.drawText(detalle, margen, alto * 0.80f, p2)
        return out
    }

    /** Arma las fotos listas para enviar de [cajas] en [carpeta]. */
    fun preparar(ctx: Context, cajas: List<Caja>, carpeta: File): List<File> {
        carpeta.mkdirs()
        carpeta.listFiles()?.forEach { it.delete() }
        val salida = mutableListOf<File>()
        for (c in cajas) {
            for ((clave, nombre) in ordenadas(c)) {
                val f = Fotos.archivo(ctx, nombre)
                if (!f.exists()) continue
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, o)
                var m = 1
                while (maxOf(o.outWidth, o.outHeight) / (m * 2) >= 1600) m *= 2
                val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = m }) ?: continue
                val (principal, detalle) = titulos(c, clave)
                val marcada = conFranja(bmp, principal, detalle)
                val n = salida.size + 1
                val base = listOf(c.tipo + c.numeroPos, Caja.etiqueta(clave.substringBefore('|'), c.tipo), clave.substringAfter('|'))
                    .joinToString("_").replace(Regex("[^A-Za-z0-9ÁÉÍÓÚáéíóúÑñ_-]+"), "-")
                val archivo = File(carpeta, "%02d_%s.jpg".format(n, base))
                archivo.outputStream().use { marcada.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                bmp.recycle(); marcada.recycle()
                salida.add(archivo)
            }
        }
        return salida
    }
}

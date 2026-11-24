package cl.inventario.escaner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID

/** Guarda en el teléfono las fotos de cada caja, achicadas para que el Excel no quede muy pesado. */
object Fotos {

    private const val LADO_MAXIMO = 1280
    private const val CALIDAD = 72

    fun carpeta(ctx: Context): File = File(ctx.filesDir, "fotos").apply { mkdirs() }

    fun archivo(ctx: Context, nombre: String): File = File(carpeta(ctx), nombre)

    /** Copia achicada y derecha de la foto recién sacada. Devuelve el nombre del archivo guardado. */
    fun guardarDesde(ctx: Context, original: File): String {
        val opciones = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.absolutePath, opciones)
        var muestra = 1
        while (maxOf(opciones.outWidth, opciones.outHeight) / (muestra * 2) >= LADO_MAXIMO) muestra *= 2
        var bmp = BitmapFactory.decodeFile(original.absolutePath, BitmapFactory.Options().apply { inSampleSize = muestra })
            ?: throw IllegalStateException("no se pudo leer la foto")

        // Achicar al tamaño final.
        val mayor = maxOf(bmp.width, bmp.height)
        if (mayor > LADO_MAXIMO) {
            val f = LADO_MAXIMO.toFloat() / mayor
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
        }

        // Enderezar según cómo se sostuvo el teléfono.
        val giro = when (ExifInterface(original.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (giro != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(giro) }, true)

        val nombre = UUID.randomUUID().toString() + ".jpg"
        archivo(ctx, nombre).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, CALIDAD, it) }
        return nombre
    }

    /** Guarda una foto recuperada de un Excel cargado. */
    fun guardarBytes(ctx: Context, bytes: ByteArray): String {
        val nombre = UUID.randomUUID().toString() + ".jpg"
        archivo(ctx, nombre).writeBytes(bytes)
        return nombre
    }

    /** Miniatura para mostrar en pantalla. */
    fun miniatura(ctx: Context, nombre: String, lado: Int): Bitmap? {
        val f = archivo(ctx, nombre)
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var muestra = 1
        while (maxOf(o.outWidth, o.outHeight) / (muestra * 2) >= lado) muestra *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = muestra })
    }

    /** Borra las fotos que ya no pertenecen a ninguna caja ni al formulario abierto. */
    fun limpiarHuerfanas(ctx: Context, enUso: Collection<String>) {
        val usadas = enUso.toHashSet()
        carpeta(ctx).listFiles()?.forEach { if (it.name !in usadas) it.delete() }
    }
}

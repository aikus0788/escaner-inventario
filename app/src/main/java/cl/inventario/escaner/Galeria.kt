package cl.inventario.escaner

import android.content.Context
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Opción de usar una imagen guardada en el teléfono en vez de sacar una foto. */
object Galeria {

    /** Muestra «Tomar foto» / «Elegir de la galería». */
    fun preguntar(ctx: Context, camara: () -> Unit, galeria: () -> Unit) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Agregar imagen")
            .setItems(arrayOf("📷  Tomar foto", "🖼  Elegir imagen del teléfono")) { _, i ->
                if (i == 0) camara() else galeria()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Copia la imagen elegida a [destino] (la misma foto temporal que usa la cámara). */
    fun copiar(ctx: Context, origen: Uri, destino: File): Boolean = try {
        ctx.contentResolver.openInputStream(origen)?.use { entrada ->
            destino.outputStream().use { entrada.copyTo(it) }
        } != null && destino.length() > 0
    } catch (_: Exception) {
        false
    }
}

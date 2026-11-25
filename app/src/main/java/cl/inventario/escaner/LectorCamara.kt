package cl.inventario.escaner

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Lee códigos de barras y texto de la foto de una etiqueta (en el teléfono, sin internet). */
object LectorCamara {

    /**
     * [listo] recibe el valor más probable (o null) y todas las opciones encontradas,
     * igual que en el módulo de inventario.
     */
    fun leer(ctx: Context, uri: Uri, esSerie: Boolean, listo: (String?, List<String>) -> Unit) {
        val imagen = try {
            InputImage.fromFilePath(ctx, uri)
        } catch (e: Exception) {
            listo(null, emptyList())
            return
        }
        BarcodeScanning.getClient().process(imagen).addOnCompleteListener { tc ->
            val codigos = if (tc.isSuccessful) {
                tc.result.mapNotNull { it.rawValue?.trim() }.filter { it.isNotEmpty() }.distinct()
            } else emptyList()
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(imagen).addOnCompleteListener { tt ->
                val lineas = if (tt.isSuccessful) {
                    tt.result.textBlocks.flatMap { b -> b.lines.map { it.text.trim() } }.filter { it.length >= 2 }
                } else emptyList()
                val sugeridos = Lector.buscarConEtiqueta(lineas, esSerie)
                val todos = LinkedHashSet<String>().apply {
                    addAll(sugeridos)
                    addAll(codigos)
                    addAll(lineas.filter { Lector.pareceCodigo(it) })
                    addAll(lineas)
                }
                listo(sugeridos.firstOrNull() ?: codigos.singleOrNull(), todos.toList().take(30))
            }
        }
    }
}

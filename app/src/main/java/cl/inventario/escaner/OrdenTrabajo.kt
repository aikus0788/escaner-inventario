package cl.inventario.escaner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Una línea del «Detalle de actividad» de la OT. */
data class LineaOt(
    val tipoActividad: String = "",
    val cantidad: String = "",
    val modelo: String = "",
    val tipoEquipo: String = "",
    val serie: String = "",
    /** Actividad escrita a mano cuando el tipo es «Otros». */
    val actividadOtra: String = "",
) {
    fun vacia() = listOf(tipoActividad, cantidad, modelo, tipoEquipo, serie, actividadOtra).all { it.isBlank() }

    /** Lo que se escribe en la columna «Tipo Actividad» de la OT. */
    val actividadParaPdf: String
        get() = if (tipoActividad.equals(OrdenTrabajo.OTROS, true) && actividadOtra.isNotBlank()) actividadOtra else tipoActividad
}

/** Orden de trabajo NCR Voyix, con los mismos campos del formulario en papel. */
data class OrdenTrabajo(
    val numero: String = "",
    val fecha: String = "",
    val cliente: String = "",
    val local: String = "",
    val direccion: String = "",
    val tecnico: String = "",
    val estado: String = "",
    val lineas: List<LineaOt> = emptyList(),
    val observaciones: String = "",
    val recibeNombre: String = "",
    val recibeCargo: String = "",
    /** Archivo PNG con la firma del cliente (dentro de la carpeta de la OT), o vacío. */
    val firma: String = "",
    /** Imágenes que se agregan al final del PDF (archivos dentro de la carpeta de imágenes de la OT). */
    val imagenes: List<String> = emptyList(),
) {
    companion object {
        /** El formulario tiene 9 líneas de detalle. */
        const val MAX_LINEAS = 9

        val ESTADOS = listOf("Finalizada", "Pendiente", "Parcial", "Reprogramada", "Cancelada")
        const val OTROS = "Otros"
        val TIPOS_ACTIVIDAD = listOf(
            "Instalación", "Retiro", "Reposicionamiento", "Mantención", "Reparación", "Revisión", "Cambio de equipo", OTROS,
        )
        val TIPOS_EQUIPO = listOf(
            "POS", "SSCO", "Pantalla", "Segunda pantalla", "Impresora", "Gaveta", "Teclado",
            "Scanner de mano", "Scanner de mesón",
        )
    }
}

/** Guarda en el teléfono la OT que se está llenando, para no perderla si se cierra la app. */
class AlmacenOt(context: Context) {
    private val prefs = context.getSharedPreferences("ot", Context.MODE_PRIVATE)

    fun cargar(): OrdenTrabajo {
        val t = prefs.getString("borrador", null) ?: return OrdenTrabajo()
        return try {
            val o = JSONObject(t)
            val arr = o.optJSONArray("lineas") ?: JSONArray()
            OrdenTrabajo(
                numero = o.optString("numero"),
                fecha = o.optString("fecha"),
                cliente = o.optString("cliente"),
                local = o.optString("local"),
                direccion = o.optString("direccion"),
                tecnico = o.optString("tecnico"),
                estado = o.optString("estado"),
                lineas = (0 until arr.length()).map { i ->
                    val l = arr.getJSONObject(i)
                    LineaOt(
                        l.optString("tipoActividad"), l.optString("cantidad"), l.optString("modelo"), l.optString("tipoEquipo"),
                        l.optString("serie"), l.optString("actividadOtra"),
                    )
                },
                observaciones = o.optString("observaciones"),
                recibeNombre = o.optString("recibeNombre"),
                recibeCargo = o.optString("recibeCargo"),
                firma = o.optString("firma"),
                imagenes = o.optJSONArray("imagenes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            )
        } catch (_: Exception) {
            OrdenTrabajo()
        }
    }

    fun guardar(ot: OrdenTrabajo) {
        val arr = JSONArray()
        ot.lineas.forEach { l ->
            arr.put(
                JSONObject().put("tipoActividad", l.tipoActividad).put("cantidad", l.cantidad).put("modelo", l.modelo)
                    .put("tipoEquipo", l.tipoEquipo).put("serie", l.serie).put("actividadOtra", l.actividadOtra),
            )
        }
        val o = JSONObject()
            .put("numero", ot.numero).put("fecha", ot.fecha).put("cliente", ot.cliente).put("local", ot.local)
            .put("direccion", ot.direccion).put("tecnico", ot.tecnico).put("estado", ot.estado).put("lineas", arr)
            .put("observaciones", ot.observaciones).put("recibeNombre", ot.recibeNombre).put("recibeCargo", ot.recibeCargo)
            .put("firma", ot.firma)
            .put("imagenes", JSONArray(ot.imagenes))
        prefs.edit().putString("borrador", o.toString()).putString("tecnico", ot.tecnico).apply()
    }

    /** Nombre del técnico de la última OT, para no escribirlo cada vez. */
    fun ultimoTecnico(): String = prefs.getString("tecnico", "") ?: ""
}

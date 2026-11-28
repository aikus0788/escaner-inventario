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
    /** Comentario de cada imagen (nombre de archivo → texto). */
    val comentarios: Map<String, String> = emptyMap(),
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

/**
 * Detalle de la OT en modo conteo desde el Inventario: una línea por trabajo, tipo de equipo y modelo,
 * con la cantidad de equipos. La serie solo se escribe cuando es 1 equipo.
 * Ej.: 5 POS TCx700 retirados → «Retiro · 5 · TCx700 · POS · (sin serie)».
 */
fun lineasDesdeInventario(cajas: List<Caja>): List<LineaOt> {
    data class Grupo(val trabajo: String, val equipo: String, val modelo: String, val orden: Int, val series: MutableList<String>)
    val grupos = linkedMapOf<String, Grupo>()
    for (c in cajas) {
        for (comp in Caja.POR_TIPO[c.tipo] ?: Caja.COMPONENTES) {
            val p = c.piezas[comp] ?: continue
            if (p.vacia()) continue
            val equipo = Caja.etiqueta(comp, c.tipo)
            val k = listOf(c.trabajo, equipo, p.modelo.trim()).joinToString("|") { it.lowercase() }
            val g = grupos.getOrPut(k) {
                Grupo(c.trabajo, equipo, p.modelo.trim(), Caja.COMPONENTES.indexOf(comp).let { if (it < 0) 99 else it }, mutableListOf())
            }
            g.series.add(p.serie.trim())
        }
    }
    val ordenTrabajo = { t: String -> when (t) { Caja.RETIRO -> 0; Caja.INSTALACION -> 1; else -> 2 } }
    return grupos.values.sortedWith(compareBy({ ordenTrabajo(it.trabajo) }, { it.orden }))
        .map { g -> LineaOt(g.trabajo, g.series.size.toString(), g.modelo, g.equipo, if (g.series.size == 1) g.series[0] else "") }
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
                comentarios = o.optJSONObject("comentarios")?.let { c -> c.keys().asSequence().associateWith { c.optString(it) } }
                    ?: emptyMap(),
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
            .put("comentarios", JSONObject(ot.comentarios.filterValues { it.isNotBlank() }))
        prefs.edit().putString("borrador", o.toString()).putString("tecnico", ot.tecnico).apply()
    }

    /** Clientes agregados con «Otros», para que aparezcan en la lista las próximas veces. */
    fun clientesExtra(): List<String> = prefs.getString("clientesExtra", null)?.let { t ->
        try { JSONArray(t).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (_: Exception) { emptyList() }
    } ?: emptyList()

    fun agregarCliente(nombre: String) {
        val lista = clientesExtra().filterNot { it.equals(nombre, true) } + nombre
        prefs.edit().putString("clientesExtra", JSONArray(lista).toString()).apply()
    }

    /** Nombre del técnico de la última OT, para no escribirlo cada vez. */
    fun ultimoTecnico(): String = prefs.getString("tecnico", "") ?: ""
}

package cl.inventario.escaner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Equipo(
    val id: Long,
    val fecha: String,
    val tipo: String,
    val modelo: String,
    val serie: String,
    val ubicacion: String,
    val notas: String,
)

/** Guarda la planilla en el propio teléfono. */
class Almacen(context: Context) {
    private val prefs = context.getSharedPreferences("datos", Context.MODE_PRIVATE)

    fun cargar(): MutableList<Equipo> {
        val lista = mutableListOf<Equipo>()
        val texto = prefs.getString("filas", null) ?: return lista
        try {
            val arr = JSONArray(texto)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                lista.add(
                    Equipo(
                        id = o.optLong("id"),
                        fecha = o.optString("fecha"),
                        tipo = o.optString("tipo"),
                        modelo = o.optString("modelo"),
                        serie = o.optString("serie"),
                        ubicacion = o.optString("ubicacion"),
                        notas = o.optString("notas"),
                    )
                )
            }
        } catch (_: Exception) {
        }
        return lista
    }

    fun guardar(lista: List<Equipo>) {
        val arr = JSONArray()
        for (e in lista) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("fecha", e.fecha)
                    .put("tipo", e.tipo)
                    .put("modelo", e.modelo)
                    .put("serie", e.serie)
                    .put("ubicacion", e.ubicacion)
                    .put("notas", e.notas)
            )
        }
        prefs.edit().putString("filas", arr.toString()).apply()
    }
}

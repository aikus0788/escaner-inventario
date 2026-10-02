package cl.inventario.escaner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Equipo(
    val id: Long,
    val fecha: String,
    val cliente: String,
    val tienda: String,
    val direccion: String,
    val region: String,
    val tipo: String,
    val modelo: String,
    val serie: String,
    val ubicacion: String,
    val notas: String,
) {
    /** Columnas en el mismo orden que la planilla Excel. */
    fun comoFila(numero: Int): List<String> =
        listOf(numero.toString(), fecha, cliente, tienda, direccion, region, tipo, modelo, serie, ubicacion, notas)

    companion object {
        val ENCABEZADO = listOf(
            "N°", "Fecha", "Cliente", "N° Tienda", "Dirección", "Región",
            "Tipo de equipo", "Modelo", "Serie", "Ubicación POS", "Notas",
        )
        val ANCHOS = listOf(6, 12, 16, 11, 34, 26, 18, 24, 24, 16, 30)
    }
}

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
                        cliente = o.optString("cliente"),
                        tienda = o.optString("tienda"),
                        direccion = o.optString("direccion"),
                        region = o.optString("region"),
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
                    .put("cliente", e.cliente)
                    .put("tienda", e.tienda)
                    .put("direccion", e.direccion)
                    .put("region", e.region)
                    .put("tipo", e.tipo)
                    .put("modelo", e.modelo)
                    .put("serie", e.serie)
                    .put("ubicacion", e.ubicacion)
                    .put("notas", e.notas)
            )
        }
        prefs.edit().putString("filas", arr.toString()).apply()
    }

    /** Datos de la tienda que se mantienen entre un equipo y otro. */
    fun leerTienda(clave: String): String = prefs.getString("tienda_$clave", "") ?: ""
    fun guardarTienda(valores: Map<String, String>) {
        val ed = prefs.edit()
        valores.forEach { (k, v) -> ed.putString("tienda_$k", v) }
        ed.apply()
    }
}

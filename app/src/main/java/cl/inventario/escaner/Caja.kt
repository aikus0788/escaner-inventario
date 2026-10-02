package cl.inventario.escaner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Modelo y número de serie de un componente de la caja (pantalla, impresora…). */
data class Pieza(val modelo: String = "", val serie: String = "") {
    fun vacia() = modelo.isBlank() && serie.isBlank()
}

/** Una caja (POS o SSCO) con todos sus componentes: es una fila del Excel. */
data class Caja(
    val id: Long,
    val fecha: String,
    val cliente: String,
    val tienda: String,
    val direccion: String,
    val region: String,
    val tipo: String,
    val ubicacion: String,
    val piezas: Map<String, Pieza>,
    val notas: String,
) {
    fun pieza(nombre: String) = piezas[nombre] ?: Pieza()

    /** Columnas en el mismo orden que la planilla Excel. */
    fun comoFila(numero: Int): List<String> {
        val f = mutableListOf(numero.toString(), fecha, cliente, tienda, direccion, region, tipo, ubicacion)
        for (c in COMPONENTES) {
            f.add(pieza(c).modelo)
            f.add(pieza(c).serie)
        }
        f.add(notas)
        return f
    }

    companion object {
        const val POS = "POS"
        const val SSCO = "SSCO"

        /** Orden de las columnas en el Excel (todas las cajas usan las mismas columnas). */
        /** El propio equipo POS o SSCO; en pantalla se muestra con el nombre del tipo de caja. */
        const val PRINCIPAL = "POS/SSCO"

        val COMPONENTES = listOf(
            PRINCIPAL, "Pantalla", "Segunda pantalla", "Impresora", "Gaveta", "Teclado", "Scanner de mano", "Scanner de mesón",
        )

        /** Componentes que se piden según el tipo de caja. */
        val POR_TIPO = mapOf(
            POS to COMPONENTES,
            SSCO to listOf(PRINCIPAL, "Pantalla", "Impresora", "Scanner de mesón", "Scanner de mano"),
        )

        /** Nombre que se ve en pantalla: el componente principal se llama como el tipo de caja. */
        fun etiqueta(nombre: String, tipo: String) = if (nombre == PRINCIPAL) tipo.ifEmpty { PRINCIPAL } else nombre

        val ENCABEZADO: List<String> by lazy {
            val e = mutableListOf("N°", "Fecha", "Cliente", "N° Tienda", "Dirección", "Región", "Tipo", "Ubicación POS")
            for (c in COMPONENTES) {
                e.add("$c - Modelo")
                e.add("$c - Serie")
            }
            e.add("Notas")
            e
        }

        val ANCHOS: List<Int> by lazy {
            val a = mutableListOf(6, 12, 16, 11, 34, 26, 8, 14)
            repeat(COMPONENTES.size) { a.add(20); a.add(20) }
            a.add(30)
            a
        }
    }
}

/** Guarda la planilla en el propio teléfono. */
class Almacen(context: Context) {
    private val prefs = context.getSharedPreferences("datos", Context.MODE_PRIVATE)

    fun cargar(): MutableList<Caja> {
        val texto = prefs.getString("cajas", null)
        if (texto == null) {
            // Datos de la versión anterior (una fila por equipo): se convierten una sola vez.
            val viejas = prefs.getString("filas", null) ?: return mutableListOf()
            val convertidas = convertirViejas(viejas)
            guardar(convertidas)
            return convertidas
        }
        val lista = mutableListOf<Caja>()
        try {
            val arr = JSONArray(texto)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val piezas = mutableMapOf<String, Pieza>()
                o.optJSONObject("piezas")?.let { p ->
                    for (k in p.keys()) {
                        val x = p.getJSONObject(k)
                        piezas[k] = Pieza(x.optString("modelo"), x.optString("serie"))
                    }
                }
                lista.add(
                    Caja(
                        id = o.optLong("id"),
                        fecha = o.optString("fecha"),
                        cliente = o.optString("cliente"),
                        tienda = o.optString("tienda"),
                        direccion = o.optString("direccion"),
                        region = o.optString("region"),
                        tipo = o.optString("tipo"),
                        ubicacion = o.optString("ubicacion"),
                        piezas = piezas,
                        notas = o.optString("notas"),
                    )
                )
            }
        } catch (_: Exception) {
        }
        return lista
    }

    fun guardar(lista: List<Caja>) {
        val arr = JSONArray()
        for (c in lista) {
            val piezas = JSONObject()
            c.piezas.filterValues { !it.vacia() }.forEach { (k, v) ->
                piezas.put(k, JSONObject().put("modelo", v.modelo).put("serie", v.serie))
            }
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("fecha", c.fecha)
                    .put("cliente", c.cliente)
                    .put("tienda", c.tienda)
                    .put("direccion", c.direccion)
                    .put("region", c.region)
                    .put("tipo", c.tipo)
                    .put("ubicacion", c.ubicacion)
                    .put("piezas", piezas)
                    .put("notas", c.notas)
            )
        }
        prefs.edit().putString("cajas", arr.toString()).apply()
    }

    private fun convertirViejas(texto: String): MutableList<Caja> {
        val lista = mutableListOf<Caja>()
        try {
            val arr = JSONArray(texto)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                lista.add(
                    Convertidor.desdeFilaVieja(
                        id = o.optLong("id"), fecha = o.optString("fecha"), cliente = o.optString("cliente"),
                        tienda = o.optString("tienda"), direccion = o.optString("direccion"), region = o.optString("region"),
                        tipoEquipo = o.optString("tipo"), modelo = o.optString("modelo"), serie = o.optString("serie"),
                        ubicacion = o.optString("ubicacion"), notas = o.optString("notas"),
                    )
                )
            }
        } catch (_: Exception) {
        }
        return lista
    }

    /** Datos de la tienda que se mantienen entre una caja y otra. */
    fun leerTienda(clave: String): String = prefs.getString("tienda_$clave", "") ?: ""
    fun guardarTienda(valores: Map<String, String>) {
        val ed = prefs.edit()
        valores.forEach { (k, v) -> ed.putString("tienda_$k", v) }
        ed.apply()
    }
}

/** Convierte una fila del formato anterior (un equipo por fila) en una caja con un solo componente. */
object Convertidor {
    fun desdeFilaVieja(
        id: Long, fecha: String, cliente: String, tienda: String, direccion: String, region: String,
        tipoEquipo: String, modelo: String, serie: String, ubicacion: String, notas: String,
    ): Caja {
        val comp = Caja.COMPONENTES.firstOrNull { it.equals(tipoEquipo.trim(), ignoreCase = true) }
        val esTipoCaja = tipoEquipo.trim().equals(Caja.SSCO, true) || tipoEquipo.trim().equals(Caja.POS, true)
        val piezas = mutableMapOf<String, Pieza>()
        var notasFinal = notas
        when {
            comp != null -> piezas[comp] = Pieza(modelo, serie)
            esTipoCaja -> piezas[Caja.PRINCIPAL] = Pieza(modelo, serie)
            modelo.isNotBlank() || serie.isNotBlank() -> {
                val extra = listOf(tipoEquipo, modelo.takeIf { it.isNotBlank() }?.let { "Mod: $it" }, serie.takeIf { it.isNotBlank() }?.let { "S/N: $it" })
                    .filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
                notasFinal = listOf(notas, extra).filter { it.isNotBlank() }.joinToString(" · ")
            }
        }
        return Caja(
            id = id, fecha = fecha, cliente = cliente, tienda = tienda, direccion = direccion, region = region,
            tipo = if (tipoEquipo.trim().equals(Caja.SSCO, true)) Caja.SSCO else Caja.POS,
            ubicacion = ubicacion, piezas = piezas, notas = notasFinal,
        )
    }
}

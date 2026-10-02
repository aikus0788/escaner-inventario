package cl.inventario.escaner

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.Normalizer
import java.util.Calendar
import java.util.zip.ZipInputStream

/** Lee la primera hoja de un .xlsx y la convierte en filas de equipos. */
object XlsxReader {

    class FormatoNoValido(msg: String) : Exception(msg)

    fun leerEquipos(input: InputStream): List<Equipo> {
        val tabla = leerTabla(input)
        val iEnc = tabla.indexOfFirst { fila ->
            val n = fila.map { normalizar(it) }
            n.any { it == "modelo" || it == "serie" } || n.any { it.startsWith("tipo") }
        }
        if (iEnc < 0) throw FormatoNoValido("No encontré las columnas (Modelo, Serie, Tipo…) en la primera hoja.")
        val enc = tabla[iEnc].map { normalizar(it) }

        fun col(vararg nombres: String): Int = enc.indexOfFirst { h -> nombres.any { n -> h == n } }
            .let { if (it >= 0) it else enc.indexOfFirst { h -> nombres.any { n -> h.startsWith(n) } } }

        val cFecha = col("fecha")
        val cCliente = col("cliente")
        val cTienda = col("ntienda", "notienda", "tienda", "numerotienda", "nrotienda")
        val cDireccion = col("direccion")
        val cRegion = col("region")
        val cTipo = col("tipodeequipo", "tipo", "equipo")
        val cModelo = col("modelo")
        val cSerie = col("serie", "numerodeserie", "nserie", "sn")
        val cUbic = col("ubicacionpos", "ubicacion", "pos")
        val cNotas = col("notas", "observaciones", "comentarios")

        val base = System.currentTimeMillis()
        val equipos = mutableListOf<Equipo>()
        for (i in iEnc + 1 until tabla.size) {
            val f = tabla[i]
            fun v(c: Int): String = if (c in f.indices) f[c].trim() else ""
            val e = Equipo(
                id = base + i,
                fecha = fechaLegible(v(cFecha)),
                cliente = v(cCliente),
                tienda = v(cTienda),
                direccion = v(cDireccion),
                region = v(cRegion),
                tipo = v(cTipo),
                modelo = v(cModelo),
                serie = v(cSerie),
                ubicacion = v(cUbic),
                notas = v(cNotas),
            )
            if (listOf(e.cliente, e.tienda, e.tipo, e.modelo, e.serie, e.ubicacion, e.notas).any { it.isNotEmpty() }) {
                equipos.add(e)
            }
        }
        return equipos
    }

    /** Excel guarda las fechas como número de días desde 1899-12-30. */
    private fun fechaLegible(s: String): String {
        val n = s.toDoubleOrNull() ?: return s
        if (n < 20000 || n > 80000) return s
        val cal = Calendar.getInstance().apply {
            clear()
            set(1899, Calendar.DECEMBER, 30)
            add(Calendar.DAY_OF_MONTH, n.toInt())
        }
        return "%02d-%02d-%04d".format(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR))
    }

    private fun normalizar(s: String): String {
        val sinTildes = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        return sinTildes.replace("°", "").replace("º", "").filter { it.isLetterOrDigit() }
    }

    // ---------------- lectura del archivo ----------------

    private fun leerTabla(input: InputStream): List<List<String>> {
        val archivos = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entrada = zip.nextEntry
            while (entrada != null) {
                val nombre = entrada.name.trimStart('/')
                if (nombre.endsWith(".xml") || nombre.endsWith(".rels")) archivos[nombre] = zip.readBytes()
                entrada = zip.nextEntry
            }
        }
        if (archivos.isEmpty()) throw FormatoNoValido("El archivo no es un Excel .xlsx. Si es .xls antiguo, ábrelo en Excel y guárdalo como .xlsx.")

        val compartidos = archivos["xl/sharedStrings.xml"]?.let { leerCompartidos(it) } ?: emptyList()
        val hoja = primeraHoja(archivos) ?: throw FormatoNoValido("El Excel no tiene hojas con datos.")
        return leerHoja(archivos.getValue(hoja), compartidos)
    }

    private fun primeraHoja(archivos: Map<String, ByteArray>): String? {
        try {
            val wb = archivos["xl/workbook.xml"]
            val rels = archivos["xl/_rels/workbook.xml.rels"]
            if (wb != null && rels != null) {
                var rid: String? = null
                parsear(wb) { p ->
                    if (rid == null && p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                        for (i in 0 until p.attributeCount) {
                            if (p.getAttributeName(i).endsWith("id") && p.getAttributeName(i) != "sheetId") rid = p.getAttributeValue(i)
                        }
                    }
                }
                var destino: String? = null
                parsear(rels) { p ->
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship" && p.getAttributeValue(null, "Id") == rid) {
                        destino = p.getAttributeValue(null, "Target")
                    }
                }
                destino?.let { d ->
                    val ruta = if (d.startsWith("/")) d.trimStart('/') else "xl/" + d.removePrefix("./")
                    if (archivos.containsKey(ruta)) return ruta
                }
            }
        } catch (_: Exception) {
        }
        return archivos.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
            .minByOrNull { it.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }
    }

    private fun leerCompartidos(bytes: ByteArray): List<String> {
        val lista = mutableListOf<String>()
        val actual = StringBuilder()
        var enSi = false
        var enT = false
        var enFonetica = false
        parsear(bytes) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> { enSi = true; actual.setLength(0) }
                    "t" -> enT = enSi
                    "rPh" -> enFonetica = true
                }
                XmlPullParser.TEXT -> if (enT && !enFonetica) actual.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "si" -> { lista.add(actual.toString()); enSi = false }
                    "t" -> enT = false
                    "rPh" -> enFonetica = false
                }
            }
        }
        return lista
    }

    private fun leerHoja(bytes: ByteArray, compartidos: List<String>): List<List<String>> {
        val filas = sortedMapOf<Int, MutableMap<Int, String>>()
        var filaActual = 0
        var colActual = 0
        var tipo: String? = null
        var enValor = false
        var enTexto = false
        val valor = StringBuilder()

        parsear(bytes) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        filaActual = p.getAttributeValue(null, "r")?.toIntOrNull() ?: (filaActual + 1)
                        colActual = 0
                    }
                    "c" -> {
                        val ref = p.getAttributeValue(null, "r")
                        colActual = if (ref != null) indiceColumna(ref) else colActual + 1
                        tipo = p.getAttributeValue(null, "t")
                        valor.setLength(0)
                    }
                    "v" -> enValor = true
                    "t" -> enTexto = true
                }
                XmlPullParser.TEXT -> if (enValor || enTexto) valor.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> enValor = false
                    "t" -> enTexto = false
                    "c" -> {
                        val crudo = valor.toString()
                        val texto = when (tipo) {
                            "s" -> crudo.trim().toIntOrNull()?.let { compartidos.getOrNull(it) } ?: ""
                            "b" -> if (crudo.trim() == "1") "VERDADERO" else "FALSO"
                            else -> limpiarNumero(crudo)
                        }
                        if (texto.isNotEmpty()) filas.getOrPut(filaActual) { mutableMapOf() }[colActual] = texto
                    }
                }
            }
        }

        return filas.values.map { celdas ->
            val max = celdas.keys.maxOrNull() ?: -1
            (0..max).map { celdas[it] ?: "" }
        }
    }

    /** "12345.0" → "12345", para que los números de tienda o serie no queden con decimales. */
    private fun limpiarNumero(s: String): String {
        val d = s.trim().toDoubleOrNull() ?: return s
        return if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else s.trim()
    }

    private fun indiceColumna(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (!ch.isLetter()) break
            n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return n - 1
    }

    private fun parsear(bytes: ByteArray, alEvento: (XmlPullParser) -> Unit) {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(ByteArrayInputStream(bytes), "UTF-8")
        while (p.next() != XmlPullParser.END_DOCUMENT) alEvento(p)
    }
}

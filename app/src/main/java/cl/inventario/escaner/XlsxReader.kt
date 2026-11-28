package cl.inventario.escaner

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.Normalizer
import java.util.Calendar
import java.util.zip.ZipInputStream

/**
 * Lee la primera hoja de un .xlsx y la convierte en cajas (una por fila).
 * Si el Excel lo exportó la app, también recupera las fotos de la hoja «Fotos».
 */
object XlsxReader {

    class FormatoNoValido(msg: String) : Exception(msg)

    /** Una caja leída del Excel y sus fotos (clave del campo → bytes JPEG). */
    data class CajaLeida(val caja: Caja, val fotos: Map<String, ByteArray>, val fotosSeries: Map<String, ByteArray> = emptyMap())

    /** Lee todas las pestañas con cajas (por ejemplo «Retiro» e «Instalación») y las junta. */
    fun leerCajas(input: InputStream): List<CajaLeida> {
        val archivos = leerZip(input)
        val compartidos = archivos["xl/sharedStrings.xml"]?.let { leerCompartidos(it) } ?: emptyList()
        val hojas = hojasPorNombre(archivos).ifEmpty {
            val unica = primeraHojaPorArchivo(archivos) ?: throw FormatoNoValido("El Excel no tiene hojas con datos.")
            linkedMapOf("" to unica)
        }
        val resultado = mutableListOf<CajaLeida>()
        var algunaConColumnas = false
        for ((nombre, ruta) in hojas) {
            val cajas = leerPestana(archivos, ruta, compartidos, nombre, resultado.size) ?: continue
            algunaConColumnas = true
            resultado.addAll(cajas)
        }
        if (!algunaConColumnas) throw FormatoNoValido("No encontré las columnas (Tipo, Modelo, Serie…) en el Excel.")
        return resultado
    }

    /** Trabajo según el nombre de la pestaña, para Excels donde la columna «Trabajo» venga vacía. */
    private fun trabajoDePestana(nombre: String): String {
        val n = normalizar(nombre)
        return when {
            n.startsWith("ret") -> Caja.RETIRO
            n.startsWith("ins") -> Caja.INSTALACION
            else -> ""
        }
    }

    /** Cajas de una pestaña, o null si la pestaña no tiene las columnas del inventario. */
    private fun leerPestana(
        archivos: Map<String, ByteArray>, principal: String, compartidos: List<String>, nombreHoja: String, desde: Int,
    ): List<CajaLeida>? {
        val hoja = leerHoja(archivos.getValue(principal), compartidos)
        val numerosDeFila = hoja.keys.toList()
        val tabla = hoja.values.toList()
        // Imágenes ancladas en la hoja: (fila, columna) contadas desde 0 → bytes.
        val imagenes = try { imagenesDeHoja(archivos, principal) } catch (_: Exception) { emptyMap() }
        val iEnc = tabla.indexOfFirst { fila ->
            val n = fila.map { normalizar(it) }
            n.any { it == "modelo" || it == "serie" || it.endsWith("serie") } || n.any { it.startsWith("tipo") }
        }
        if (iEnc < 0) return null
        val enc = tabla[iEnc].map { normalizar(it) }

        fun col(vararg nombres: String): Int = enc.indexOfFirst { h -> nombres.any { n -> h == n } }
            .let { if (it >= 0) it else enc.indexOfFirst { h -> nombres.any { n -> h.startsWith(n) } } }
        fun exacta(nombre: String): Int = enc.indexOf(nombre)

        val cFecha = col("fecha")
        val cCliente = col("cliente")
        val cTienda = col("ntienda", "notienda", "tienda", "numerotienda", "nrotienda")
        val cDireccion = col("direccion")
        val cRegion = col("region")
        val cUbic = col("ubicacionpos", "ubicacion", "caja")
        val cNumPos = col("npos", "numeropos", "numerodepos", "nropos", "nodepos")
        val cNotas = col("notas", "observaciones", "comentarios")
        val cEstado = col("estadopos", "estadodelpos", "estado")
        val cTrabajo = col("trabajo", "tipodetrabajo")
        // Columnas de las 5 fotos: "Foto: Serie del POS", …
        val colsFoto = Caja.FOTOS_POS.associate { (clave, nombre) -> clave to exacta(normalizar("Foto: $nombre")) }
            .filterValues { it >= 0 }
        // Columnas «Foto serie: Impresora», …
        val colsSerie = Caja.COMPONENTES.associate { c -> "$c|serie" to exacta(normalizar(ExcelInventario.PREFIJO_SERIE + c)) }
            .filterValues { it >= 0 }

        // Formato nuevo: "Pantalla - Modelo", "Pantalla - Serie", …
        val colsPieza = Caja.COMPONENTES.associateWith { c ->
            val n = normalizar(c)
            exacta(n + "modelo") to exacta(n + "serie")
        }
        val formatoNuevo = colsPieza.values.any { it.first >= 0 || it.second >= 0 }

        val base = System.currentTimeMillis() + desde * 1000L
        val cajas = mutableListOf<CajaLeida>()
        for (i in iEnc + 1 until tabla.size) {
            val f = tabla[i]
            fun v(c: Int): String = if (c in f.indices) f[c].trim() else ""

            val caja = if (formatoNuevo) {
                val cTipo = col("tipo")
                val tipo = v(cTipo).let { if (it.equals(Caja.SSCO, true)) Caja.SSCO else if (it.isBlank()) "" else Caja.POS }
                val piezas = colsPieza.mapValues { (_, cs) -> Pieza(v(cs.first), v(cs.second)) }.filterValues { !it.vacia() }
                Caja(
                    id = base + i, fecha = fechaLegible(v(cFecha)), cliente = v(cCliente), tienda = v(cTienda),
                    direccion = v(cDireccion), region = v(cRegion), tipo = tipo.ifEmpty { Caja.POS },
                    ubicacion = v(cUbic), numeroPos = v(cNumPos), piezas = piezas, notas = v(cNotas),
                    estado = v(cEstado),
                    trabajo = v(cTrabajo).let { t ->
                        when {
                            t.startsWith("ret", true) -> Caja.RETIRO
                            t.startsWith("ins", true) -> Caja.INSTALACION
                            t.isBlank() -> trabajoDePestana(nombreHoja)
                            else -> t
                        }
                    },
                )
            } else {
                // Formato antiguo: una fila por equipo.
                Convertidor.desdeFilaVieja(
                    id = base + i, fecha = fechaLegible(v(cFecha)), cliente = v(cCliente), tienda = v(cTienda),
                    direccion = v(cDireccion), region = v(cRegion), tipoEquipo = v(col("tipodeequipo", "tipo", "equipo")),
                    modelo = v(col("modelo")), serie = v(col("serie", "numerodeserie", "nserie", "sn")),
                    ubicacion = v(cUbic), notas = v(cNotas), numeroPos = v(cNumPos),
                )
            }
            val tieneDatos = caja.piezas.isNotEmpty() ||
                listOf(caja.cliente, caja.tienda, caja.ubicacion, caja.numeroPos, caja.notas, caja.estado).any { it.isNotEmpty() }
            if (tieneDatos) {
                val filaCero = numerosDeFila[i] - 1
                val fotos = colsFoto.mapNotNull { (clave, c) -> imagenes[filaCero to c]?.let { clave to it } }.toMap()
                val series = colsSerie.mapNotNull { (clave, c) -> imagenes[filaCero to c]?.let { clave to it } }.toMap()
                cajas.add(CajaLeida(caja, fotos, series))
            }
        }
        return cajas
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

    private fun leerZip(input: InputStream): Map<String, ByteArray> {
        val archivos = mutableMapOf<String, ByteArray>()
        try {
            ZipInputStream(input).use { zip ->
                var entrada = zip.nextEntry
                while (entrada != null) {
                    val nombre = entrada.name.trimStart('/')
                    val sirve = nombre.endsWith(".xml") || nombre.endsWith(".rels") || nombre.startsWith("xl/media/")
                    if (sirve && !entrada.isDirectory) archivos[nombre] = zip.readBytes()
                    entrada = zip.nextEntry
                }
            }
        } catch (_: Exception) {
        }
        if (archivos.none { it.key.endsWith(".xml") }) {
            throw FormatoNoValido("El archivo no es un Excel .xlsx. Si es .xls antiguo, ábrelo en Excel y guárdalo como .xlsx.")
        }
        return archivos
    }

    /** Hojas del libro en orden: nombre → ruta del archivo dentro del .xlsx. */
    private fun hojasPorNombre(archivos: Map<String, ByteArray>): LinkedHashMap<String, String> {
        val resultado = LinkedHashMap<String, String>()
        try {
            val wb = archivos["xl/workbook.xml"] ?: return resultado
            val rels = archivos["xl/_rels/workbook.xml.rels"] ?: return resultado
            val destinos = relaciones(rels)
            parsear(wb) { p ->
                if (p.eventType == XmlPullParser.START_TAG && local(p.name) == "sheet") {
                    var nombre = ""
                    var rid = ""
                    for (i in 0 until p.attributeCount) {
                        val an = p.getAttributeName(i)
                        if (an == "name") nombre = p.getAttributeValue(i)
                        if (an.endsWith(":id") || an == "id") rid = p.getAttributeValue(i)
                    }
                    destinos[rid]?.let { t ->
                        val ruta = resolver("xl", t.second)
                        if (archivos.containsKey(ruta)) resultado[nombre] = ruta
                    }
                }
            }
        } catch (_: Exception) {
        }
        return resultado
    }

    private fun primeraHojaPorArchivo(archivos: Map<String, ByteArray>): String? =
        archivos.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") && !it.contains("_rels") }
            .minByOrNull { it.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }

    /** Id de relación → (tipo, destino). */
    private fun relaciones(bytes: ByteArray): Map<String, Pair<String, String>> {
        val m = mutableMapOf<String, Pair<String, String>>()
        parsear(bytes) { p ->
            if (p.eventType == XmlPullParser.START_TAG && local(p.name) == "Relationship") {
                val id = p.getAttributeValue(null, "Id") ?: return@parsear
                m[id] = (p.getAttributeValue(null, "Type") ?: "") to (p.getAttributeValue(null, "Target") ?: "")
            }
        }
        return m
    }

    /** Ruta de un destino relativo ("../media/image1.jpg") desde la carpeta [base] ("xl/drawings"). */
    private fun resolver(base: String, destino: String): String {
        if (destino.startsWith("/")) return destino.trimStart('/')
        val partes = base.split('/').filter { it.isNotEmpty() }.toMutableList()
        for (p in destino.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (partes.isNotEmpty()) partes.removeAt(partes.size - 1)
                else -> partes.add(p)
            }
        }
        return partes.joinToString("/")
    }

    private fun carpetaDe(ruta: String) = ruta.substringBeforeLast('/', "")

    private fun local(nombre: String) = nombre.substringAfter(':')

    /** Imágenes ancladas en una hoja: (fila, columna) desde 0 → bytes. */
    private fun imagenesDeHoja(archivos: Map<String, ByteArray>, hoja: String): Map<Pair<Int, Int>, ByteArray> {
        val relsHoja = archivos[carpetaDe(hoja) + "/_rels/" + hoja.substringAfterLast('/') + ".rels"] ?: return emptyMap()
        val dibujo = relaciones(relsHoja).values.firstOrNull { it.first.endsWith("/drawing") }?.second ?: return emptyMap()
        val rutaDibujo = resolver(carpetaDe(hoja), dibujo)
        val bytesDibujo = archivos[rutaDibujo] ?: return emptyMap()
        val relsDibujo = archivos[carpetaDe(rutaDibujo) + "/_rels/" + rutaDibujo.substringAfterLast('/') + ".rels"] ?: return emptyMap()
        val destinos = relaciones(relsDibujo)

        val resultado = mutableMapOf<Pair<Int, Int>, ByteArray>()
        var enFrom = false
        var campo = ""
        var fila = -1
        var col = -1
        val texto = StringBuilder()
        parsear(bytesDibujo) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (local(p.name)) {
                    "oneCellAnchor", "twoCellAnchor", "absoluteAnchor" -> { fila = -1; col = -1 }
                    "from" -> enFrom = true
                    "row", "col" -> if (enFrom) { campo = local(p.name); texto.setLength(0) }
                    "blip" -> {
                        var rid: String? = null
                        for (i in 0 until p.attributeCount) if (p.getAttributeName(i).endsWith("embed")) rid = p.getAttributeValue(i)
                        val destino = rid?.let { destinos[it]?.second }
                        val bytes = destino?.let { archivos[resolver(carpetaDe(rutaDibujo), it)] }
                        if (bytes != null && fila >= 0 && col >= 0) resultado[fila to col] = bytes
                    }
                }
                XmlPullParser.TEXT -> if (campo.isNotEmpty()) texto.append(p.text)
                XmlPullParser.END_TAG -> when (local(p.name)) {
                    "row", "col" -> if (campo.isNotEmpty()) {
                        val n = texto.toString().trim().toIntOrNull() ?: -1
                        if (campo == "row") fila = n else col = n
                        campo = ""
                    }
                    "from" -> enFrom = false
                }
            }
        }
        return resultado
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

    /** Filas de una hoja: número de fila (1, 2, 3…) → celdas. */
    private fun leerHoja(bytes: ByteArray, compartidos: List<String>): java.util.SortedMap<Int, List<String>> {
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
                            // Texto escrito tal cual (conserva ceros a la izquierda: "05", "0512").
                            "inlineStr", "str" -> crudo
                            else -> limpiarNumero(crudo)
                        }
                        if (texto.isNotEmpty()) filas.getOrPut(filaActual) { mutableMapOf() }[colActual] = texto
                    }
                }
            }
        }

        val resultado = sortedMapOf<Int, List<String>>()
        for ((n, celdas) in filas) {
            val max = celdas.keys.maxOrNull() ?: -1
            resultado[n] = (0..max).map { celdas[it] ?: "" }
        }
        return resultado
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

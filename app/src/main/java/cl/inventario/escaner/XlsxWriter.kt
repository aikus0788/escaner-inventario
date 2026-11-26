package cl.inventario.escaner

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Escribe un archivo .xlsx sin librerías externas: hoja «Inventario», una fila por caja.
 * Las fotos van como imágenes dentro de sus celdas; donde no hay foto, la celda queda en blanco.
 */
object XlsxWriter {

    /** Una foto dentro de la hoja: [fila] es la caja (0 = primera caja) y [columna] la columna (0 = A). */
    data class FotoCelda(val fila: Int, val columna: Int, val archivo: File, val descripcion: String)

    const val HOJA_PRINCIPAL = "Inventario"

    private const val EMU_POR_PX = 9525L
    private const val FOTO_ANCHO_MAX = 200
    private const val FOTO_ALTO_MAX = 150
    private const val MARGEN_PX = 5

    // Estilos (índices de cellXfs)
    private const val S_ENCABEZADO = 1
    private const val S_TEXTO = 2
    private const val S_TEXTO_ARRIBA = 3

    /** Una pestaña del Excel. */
    data class Hoja(
        val nombre: String,
        val encabezado: List<String>,
        val filas: List<List<String>>,
        val anchos: List<Int>,
        val fotos: List<FotoCelda> = emptyList(),
    )

    /** Un Excel de una sola pestaña. */
    fun escribir(
        destino: File,
        encabezado: List<String>,
        filas: List<List<String>>,
        anchos: List<Int>,
        fotos: List<FotoCelda> = emptyList(),
    ) = escribir(destino, listOf(Hoja(HOJA_PRINCIPAL, encabezado, filas, anchos, fotos)))

    /** Un Excel con varias pestañas (por ejemplo «Retiro» e «Instalación»). */
    fun escribir(destino: File, hojas: List<Hoja>) {
        require(hojas.isNotEmpty())
        // Por cada hoja, solo las fotos cuyo archivo existe.
        val fotosPorHoja = hojas.map { h -> h.fotos.filter { it.archivo.exists() && it.archivo.length() > 0 } }

        ZipOutputStream(FileOutputStream(destino)).use { zip ->
            fun agregar(nombre: String, contenido: String) {
                zip.putNextEntry(ZipEntry(nombre))
                zip.write(contenido.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            val tipos = StringBuilder()
            tipos.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Default Extension="jpg" ContentType="image/jpeg"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
""")
            hojas.forEachIndexed { i, _ ->
                tipos.append("""<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""").append('\n')
                if (fotosPorHoja[i].isNotEmpty()) {
                    tipos.append("""<Override PartName="/xl/drawings/drawing${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>""").append('\n')
                }
            }
            tipos.append("</Types>")
            agregar("[Content_Types].xml", tipos.toString())
            agregar(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""",
            )

            // Libro: rId1 = estilos, rId2… = hojas
            val listaHojas = StringBuilder()
            val relsLibro = StringBuilder(
                """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""",
            )
            hojas.forEachIndexed { i, h ->
                listaHojas.append("""<sheet name="${escapar(nombreHoja(h.nombre))}" sheetId="${i + 1}" r:id="rId${i + 2}"/>""")
                relsLibro.append("""<Relationship Id="rId${i + 2}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>""")
            }
            agregar(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets>$listaHojas</sheets>
</workbook>""",
            )
            agregar(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$relsLibro</Relationships>""",
            )
            agregar("xl/styles.xml", estilos())

            var numeroImagen = 0
            hojas.forEachIndexed { i, h ->
                val n = i + 1
                val fotosOk = fotosPorHoja[i]
                agregar("xl/worksheets/sheet$n.xml", hoja(h, fotosOk, seleccionada = i == 0))
                if (fotosOk.isEmpty()) return@forEachIndexed

                agregar(
                    "xl/worksheets/_rels/sheet$n.xml.rels",
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing$n.xml"/>
</Relationships>""",
                )
                agregar("xl/drawings/drawing$n.xml", dibujo(fotosOk))
                val rels = StringBuilder(
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""",
                )
                fotosOk.forEachIndexed { j, f ->
                    numeroImagen++
                    rels.append("""<Relationship Id="rId${j + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image$numeroImagen.jpg"/>""")
                    zip.putNextEntry(ZipEntry("xl/media/image$numeroImagen.jpg"))
                    f.archivo.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                rels.append("</Relationships>")
                agregar("xl/drawings/_rels/drawing$n.xml.rels", rels.toString())
            }
        }
    }

    /** Excel no admite algunos caracteres en el nombre de una pestaña y lo limita a 31 letras. */
    private fun nombreHoja(n: String) = n.replace(Regex("[\\\\/?*\\[\\]:]"), " ").take(31).ifBlank { "Hoja" }

    private fun hoja(h: Hoja, fotosOk: List<FotoCelda>, seleccionada: Boolean): String {
        val filasConFoto = fotosOk.map { it.fila }.toSet()
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        val sel = if (seleccionada) """ tabSelected="1"""" else ""
        sb.append("""<sheetViews><sheetView$sel workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        sb.append("<cols>")
        h.anchos.forEachIndexed { i, w -> sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
        sb.append("</cols><sheetData>")
        fila(sb, 1, h.encabezado, S_ENCABEZADO)
        val altoFila = (FOTO_ALTO_MAX + 2 * MARGEN_PX) * 0.75
        h.filas.forEachIndexed { i, f ->
            if (i in filasConFoto) fila(sb, i + 2, f, S_TEXTO_ARRIBA, altoFila) else fila(sb, i + 2, f, S_TEXTO)
        }
        sb.append("</sheetData>")
        val ultima = columna(h.encabezado.size - 1) + (h.filas.size + 1)
        sb.append("""<autoFilter ref="A1:$ultima"/>""")
        if (fotosOk.isNotEmpty()) sb.append("""<drawing r:id="rId1"/>""")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun estilos() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFF0960A"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="4">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
<xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""

    private fun dibujo(fotos: List<FotoCelda>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        fotos.forEachIndexed { i, f ->
            val (ancho, alto) = tamanoEnPantalla(f.archivo)
            val cx = ancho * EMU_POR_PX
            val cy = alto * EMU_POR_PX
            val margen = MARGEN_PX * EMU_POR_PX
            sb.append("<xdr:oneCellAnchor>")
            sb.append("<xdr:from><xdr:col>${f.columna}</xdr:col><xdr:colOff>$margen</xdr:colOff><xdr:row>${f.fila + 1}</xdr:row><xdr:rowOff>$margen</xdr:rowOff></xdr:from>")
            sb.append("""<xdr:ext cx="$cx" cy="$cy"/>""")
            sb.append("<xdr:pic>")
            sb.append("""<xdr:nvPicPr><xdr:cNvPr id="${i + 2}" name="Foto ${i + 1}" descr="${escapar(f.descripcion)}"/><xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr>""")
            sb.append("""<xdr:blipFill><a:blip r:embed="rId${i + 1}"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>""")
            sb.append("""<xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr>""")
            sb.append("</xdr:pic><xdr:clientData/></xdr:oneCellAnchor>")
        }
        sb.append("</xdr:wsDr>")
        return sb.toString()
    }

    /** Tamaño en píxeles con que se muestra la foto en su celda, manteniendo la proporción. */
    private fun tamanoEnPantalla(archivo: File): Pair<Int, Int> {
        val (w, h) = medidasJpeg(archivo) ?: (4 to 3)
        val f = minOf(FOTO_ANCHO_MAX.toDouble() / w, FOTO_ALTO_MAX.toDouble() / h)
        return maxOf(1, (w * f).toInt()) to maxOf(1, (h * f).toInt())
    }

    /** Lee ancho y alto de un JPEG sin decodificarlo. */
    fun medidasJpeg(archivo: File): Pair<Int, Int>? = try {
        archivo.inputStream().buffered().use { s ->
            if (s.read() != 0xFF || s.read() != 0xD8) return null
            while (true) {
                var b = s.read()
                while (b != 0xFF) { if (b < 0) return null; b = s.read() }
                var marca = s.read()
                while (marca == 0xFF) marca = s.read()
                if (marca < 0) return null
                if (marca == 0xD8 || marca in 0xD0..0xD7 || marca == 0x01) continue
                val largo = (s.read() shl 8) or s.read()
                if (largo < 2) return null
                val esSOF = marca in 0xC0..0xCF && marca != 0xC4 && marca != 0xC8 && marca != 0xCC
                if (esSOF) {
                    s.read()
                    val h = (s.read() shl 8) or s.read()
                    val w = (s.read() shl 8) or s.read()
                    return if (w > 0 && h > 0) w to h else null
                }
                var faltan = (largo - 2).toLong()
                while (faltan > 0) {
                    val saltados = s.skip(faltan)
                    if (saltados <= 0) return null
                    faltan -= saltados
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    } catch (_: Exception) {
        null
    }

    private fun fila(
        sb: StringBuilder,
        n: Int,
        valores: List<String>,
        estilo: Int,
        alto: Double? = null,
    ) {
        if (alto != null) sb.append("""<row r="$n" ht="${"%.1f".format(java.util.Locale.US, alto)}" customHeight="1">""")
        else sb.append("""<row r="$n">""")
        valores.forEachIndexed { i, v ->
            if (v.isEmpty() && estilo != S_ENCABEZADO) return@forEachIndexed
            sb.append("""<c r="${columna(i)}$n" t="inlineStr" s="$estilo"><is><t xml:space="preserve">""")
            sb.append(escapar(v))
            sb.append("</t></is></c>")
        }
        sb.append("</row>")
    }

    private fun columna(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.insert(0, 'A' + r)
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun escapar(s: String): String {
        val out = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch == '&' -> out.append("&amp;")
                ch == '<' -> out.append("&lt;")
                ch == '>' -> out.append("&gt;")
                ch == '"' -> out.append("&quot;")
                ch == '\t' || ch == '\n' || ch == '\r' -> out.append(ch)
                ch.code < 0x20 -> {}
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}

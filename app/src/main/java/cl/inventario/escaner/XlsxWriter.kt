package cl.inventario.escaner

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Escribe un archivo .xlsx sin librerías externas.
 *  - Hoja «Inventario»: una fila por caja.
 *  - Hoja «Fotos» (si hay fotos): una fila por foto, con la imagen incrustada.
 * Desde la hoja principal, la columna de fotos enlaza a las fotos de cada caja, y vuelta.
 */
object XlsxWriter {

    /** Una foto para la hoja «Fotos». [caja] es el N° de la caja en la hoja principal (1, 2, 3…). */
    data class FotoExcel(
        val caja: Int,
        val cajaTexto: String,
        val campo: String,
        val valor: String,
        val clave: String,
        val archivo: File,
    )

    const val HOJA_PRINCIPAL = "Inventario"
    const val HOJA_FOTOS = "Fotos"
    val ENCABEZADO_FOTOS = listOf("N° caja", "Caja", "Foto de", "Valor", "Foto", "clave")
    private val ANCHOS_FOTOS = listOf(9, 26, 24, 26, 46, 22)

    private const val EMU_POR_PX = 9525L
    private const val FOTO_ANCHO_MAX = 320
    private const val FOTO_ALTO_MAX = 230

    // Estilos (índices de cellXfs)
    private const val S_ENCABEZADO = 1
    private const val S_TEXTO = 2
    private const val S_ENLACE = 3
    private const val S_TEXTO_ARRIBA = 4
    private const val S_ENLACE_ARRIBA = 5

    fun escribir(
        destino: File,
        encabezado: List<String>,
        filas: List<List<String>>,
        anchos: List<Int>,
        fotos: List<FotoExcel> = emptyList(),
        columnaEnlace: Int = -1,
    ) {
        // Solo las fotos cuyo archivo existe.
        val fotosOk = fotos.filter { it.archivo.exists() && it.archivo.length() > 0 }
        val hayFotos = fotosOk.isNotEmpty()

        ZipOutputStream(FileOutputStream(destino)).use { zip ->
            fun agregar(nombre: String, contenido: String) {
                zip.putNextEntry(ZipEntry(nombre))
                zip.write(contenido.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            agregar("[Content_Types].xml", tiposDeContenido(hayFotos))
            agregar(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""",
            )
            val hojaFotos = if (hayFotos) """<sheet name="$HOJA_FOTOS" sheetId="2" r:id="rId3"/>""" else ""
            agregar(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="$HOJA_PRINCIPAL" sheetId="1" r:id="rId1"/>$hojaFotos</sheets>
</workbook>""",
            )
            val relFotos = if (hayFotos) {
                """<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>"""
            } else ""
            agregar(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
$relFotos
</Relationships>""",
            )
            agregar("xl/styles.xml", estilos())

            // Fila de la hoja «Fotos» donde empieza cada caja (para los enlaces).
            val primeraFilaDeCaja = mutableMapOf<Int, Int>()
            fotosOk.forEachIndexed { i, f -> primeraFilaDeCaja.putIfAbsent(f.caja, i + 2) }

            agregar("xl/worksheets/sheet1.xml", hojaPrincipal(encabezado, filas, anchos, columnaEnlace, primeraFilaDeCaja))

            if (hayFotos) {
                agregar("xl/worksheets/sheet2.xml", hojaDeFotos(fotosOk))
                agregar(
                    "xl/worksheets/_rels/sheet2.xml.rels",
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/>
</Relationships>""",
                )
                agregar("xl/drawings/drawing1.xml", dibujo(fotosOk))
                val rels = StringBuilder(
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""",
                )
                fotosOk.forEachIndexed { i, _ ->
                    rels.append("""<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image${i + 1}.jpg"/>""")
                }
                rels.append("</Relationships>")
                agregar("xl/drawings/_rels/drawing1.xml.rels", rels.toString())

                // Las imágenes ya vienen comprimidas: se guardan tal cual.
                fotosOk.forEachIndexed { i, f ->
                    zip.putNextEntry(ZipEntry("xl/media/image${i + 1}.jpg").apply { method = ZipEntry.DEFLATED })
                    f.archivo.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun tiposDeContenido(hayFotos: Boolean): String {
        val extra = if (hayFotos) {
            """<Default Extension="jpg" ContentType="image/jpeg"/>
<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>"""
        } else ""
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
$extra
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""
    }

    private fun estilos() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="3"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font><font><u/><sz val="11"/><color rgb="FF0832D6"/><name val="Calibri"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFF0960A"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="6">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
<xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="49" fontId="2" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/>
<xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
<xf numFmtId="49" fontId="2" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1" applyAlignment="1"><alignment vertical="top"/></xf>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""

    private fun hojaPrincipal(
        encabezado: List<String>,
        filas: List<List<String>>,
        anchos: List<Int>,
        columnaEnlace: Int,
        primeraFilaDeCaja: Map<Int, Int>,
    ): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        columnas(sb, anchos)
        sb.append("<sheetData>")
        fila(sb, 1, encabezado, S_ENCABEZADO)
        val enlaces = StringBuilder()
        filas.forEachIndexed { i, f ->
            val n = i + 2
            val caja = i + 1
            val destino = primeraFilaDeCaja[caja]
            val estilos = if (destino != null && columnaEnlace >= 0) mapOf(columnaEnlace to S_ENLACE) else emptyMap()
            fila(sb, n, f, S_TEXTO, estilos)
            if (destino != null && columnaEnlace >= 0) {
                enlaces.append("""<hyperlink ref="${columna(columnaEnlace)}$n" location="'$HOJA_FOTOS'!A$destino" display="${escapar(f.getOrElse(columnaEnlace) { "" })}"/>""")
            }
        }
        sb.append("</sheetData>")
        val ultima = columna(encabezado.size - 1) + (filas.size + 1)
        sb.append("""<autoFilter ref="A1:$ultima"/>""")
        if (enlaces.isNotEmpty()) sb.append("<hyperlinks>").append(enlaces).append("</hyperlinks>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun hojaDeFotos(fotos: List<FotoExcel>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        sb.append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        sb.append("<cols>")
        ANCHOS_FOTOS.forEachIndexed { i, w ->
            val oculta = if (i == ANCHOS_FOTOS.size - 1) """ hidden="1"""" else ""
            sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"$oculta/>""")
        }
        sb.append("</cols><sheetData>")
        fila(sb, 1, ENCABEZADO_FOTOS, S_ENCABEZADO)
        val enlaces = StringBuilder()
        fotos.forEachIndexed { i, f ->
            val n = i + 2
            val (_, alto) = tamanoEnPantalla(f.archivo)
            val altoPuntos = (alto + 10) * 0.75
            fila(
                sb, n,
                listOf(f.caja.toString(), f.cajaTexto, f.campo, f.valor, "", f.clave),
                S_TEXTO_ARRIBA, mapOf(0 to S_ENLACE_ARRIBA), altoPuntos,
            )
            // El N° de caja vuelve a su fila en la hoja principal.
            enlaces.append("""<hyperlink ref="A$n" location="'$HOJA_PRINCIPAL'!A${f.caja + 1}" display="${f.caja}"/>""")
        }
        sb.append("</sheetData>")
        sb.append("<hyperlinks>").append(enlaces).append("</hyperlinks>")
        sb.append("""<drawing r:id="rId1"/>""")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun dibujo(fotos: List<FotoExcel>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        fotos.forEachIndexed { i, f ->
            val (ancho, alto) = tamanoEnPantalla(f.archivo)
            val cx = ancho * EMU_POR_PX
            val cy = alto * EMU_POR_PX
            val margen = 5 * EMU_POR_PX
            sb.append("<xdr:oneCellAnchor>")
            sb.append("<xdr:from><xdr:col>4</xdr:col><xdr:colOff>$margen</xdr:colOff><xdr:row>${i + 1}</xdr:row><xdr:rowOff>$margen</xdr:rowOff></xdr:from>")
            sb.append("""<xdr:ext cx="$cx" cy="$cy"/>""")
            sb.append("<xdr:pic>")
            sb.append("""<xdr:nvPicPr><xdr:cNvPr id="${i + 2}" name="Foto ${i + 1}" descr="${escapar(f.campo)}"/><xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr>""")
            sb.append("""<xdr:blipFill><a:blip r:embed="rId${i + 1}"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>""")
            sb.append("""<xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr>""")
            sb.append("</xdr:pic><xdr:clientData/></xdr:oneCellAnchor>")
        }
        sb.append("</xdr:wsDr>")
        return sb.toString()
    }

    /** Tamaño en píxeles con que se muestra la foto en la hoja, manteniendo la proporción. */
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

    private fun columnas(sb: StringBuilder, anchos: List<Int>) {
        sb.append("<cols>")
        anchos.forEachIndexed { i, w -> sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
        sb.append("</cols>")
    }

    private fun fila(
        sb: StringBuilder,
        n: Int,
        valores: List<String>,
        estilo: Int,
        estilos: Map<Int, Int> = emptyMap(),
        alto: Double? = null,
    ) {
        if (alto != null) sb.append("""<row r="$n" ht="${"%.1f".format(java.util.Locale.US, alto)}" customHeight="1">""")
        else sb.append("""<row r="$n">""")
        valores.forEachIndexed { i, v ->
            val s = estilos[i] ?: estilo
            if (v.isEmpty() && s == estilo && estilo != S_ENCABEZADO) return@forEachIndexed
            sb.append("""<c r="${columna(i)}$n" t="inlineStr" s="$s"><is><t xml:space="preserve">""")
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

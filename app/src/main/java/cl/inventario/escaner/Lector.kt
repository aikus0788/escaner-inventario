package cl.inventario.escaner

/** Busca el modelo o la serie entre las líneas de texto leídas de una etiqueta. */
object Lector {

    private val etiquetaSerie = Regex(
        """(?i)(?:^|[^A-Z0-9])(S\s?/\s?N|SN|SERIAL(?:\s*(?:NO\.?|NUMBER|N[°º]))?|SER\.?\s*NO\.?|N[°º]?\s*DE\s*SERIE|NO\.?\s*DE\s*SERIE|SERIE)(?![A-Z0-9])\s*[:#.\-]?\s*(.*)$"""
    )

    private val etiquetaModelo = Regex(
        """(?i)(?:^|[^A-Z0-9])(MODEL(?:O)?(?:\s*(?:NO\.?|NUMBER|N[°º]|NAME))?|M\s?/\s?N|MTM|MT|MOD\.?|P\s?/\s?N|PRODUCT(?:\s*(?:NO\.?|NUMBER|NAME))?|PRODUCTO|TYPE|TIPO)(?![A-Z0-9])\s*[:#.\-]?\s*(.*)$"""
    )

    private val token = Regex("""[A-Za-z0-9][A-Za-z0-9\-/.]{3,}""")

    fun buscarConEtiqueta(lineas: List<String>, esSerie: Boolean): List<String> {
        val regex = if (esSerie) etiquetaSerie else etiquetaModelo
        val resultado = LinkedHashSet<String>()
        lineas.forEachIndexed { i, linea ->
            val m = regex.find(linea) ?: return@forEachIndexed
            var valor = m.groupValues[2].trim().trimStart(':', '#', '.', '-', ' ')
            if (valor.isEmpty() && i + 1 < lineas.size) valor = lineas[i + 1].trim()
            if (valor.isEmpty()) return@forEachIndexed
            val limpio = if (esSerie) {
                token.find(valor)?.value ?: ""
            } else {
                valor.take(60)
            }
            if (limpio.length >= 2) resultado.add(limpio)
        }
        return resultado.toList()
    }

    fun pareceCodigo(s: String): Boolean =
        s.length in 5..30 && !s.contains(' ') && s.any { it.isDigit() } &&
            s.all { it.isLetterOrDigit() || it == '-' || it == '/' || it == '.' }
}

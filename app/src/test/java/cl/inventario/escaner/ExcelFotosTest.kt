package cl.inventario.escaner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Exporta un Excel con fotos y lo vuelve a cargar: los datos y las fotos deben volver iguales. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExcelFotosTest {

    /** Copia una foto de muestra (src/test/resources/fotos) a [dir]. */
    private fun jpeg(dir: File, nombre: String): File {
        val bytes = javaClass.classLoader!!.getResourceAsStream("fotos/$nombre.jpg")!!.use { it.readBytes() }
        return File(dir, "$nombre.jpg").also { it.writeBytes(bytes) }
    }

    @Test
    fun exportarYCargarConFotos() {
        val dir = File("build/prueba-fotos-media").apply { deleteRecursively(); mkdirs() }
        val f1 = jpeg(dir, "pos-serie")
        val f2 = jpeg(dir, "notas")
        val f3 = jpeg(dir, "estado")
        val f4 = jpeg(dir, "impresora-modelo")

        assertEquals(1280 to 960, XlsxWriter.medidasJpeg(f1))
        assertEquals(960 to 1280, XlsxWriter.medidasJpeg(f2))

        val base = Caja(
            id = 1, fecha = "24-11-2026", cliente = "Jumbo", tienda = "512", direccion = "Av. Siempre Viva 123",
            region = "Metropolitana de Santiago", tipo = Caja.POS, ubicacion = "Caja 3", numeroPos = "05",
            piezas = mapOf(Caja.PRINCIPAL to Pieza("TCx700", "41A2B3C"), "Impresora" to Pieza("TM-T88V", "X5Y6")),
            notas = "Pantalla con rayas", estado = "Operativo, limpio",
        )
        val cajas = listOf(
            base.copy(fotos = mapOf("${Caja.PRINCIPAL}|serie" to f1.name, Caja.FOTO_NOTAS to f2.name, Caja.FOTO_ESTADO to f3.name)),
            base.copy(id = 2, numeroPos = "06", ubicacion = "Caja 4", notas = "", estado = "Sin fotos"),
            base.copy(id = 3, tipo = Caja.SSCO, numeroPos = "07", fotos = mapOf("Impresora|modelo" to f4.name)),
        )

        val fotos = mutableListOf<XlsxWriter.FotoExcel>()
        cajas.forEachIndexed { i, c ->
            for ((k, campo, valor) in c.fotosOrdenadas()) {
                fotos.add(XlsxWriter.FotoExcel(i + 1, "${c.tipo} N° ${c.numeroPos}", campo, valor, k, File(dir, c.fotos.getValue(k))))
            }
        }
        assertEquals(4, fotos.size)

        val salida = File("build/prueba-fotos.xlsx")
        XlsxWriter.escribir(salida, Caja.ENCABEZADO, cajas.mapIndexed { i, c -> c.comoFila(i + 1) }, Caja.ANCHOS, fotos, Caja.ENCABEZADO.size - 1)

        val leidas = salida.inputStream().use { XlsxReader.leerCajas(it) }
        leidas.forEach { println("LEIDA: ${it.caja} fotos=${it.fotos.keys}") }
        assertEquals(3, leidas.size)
        assertEquals("Operativo, limpio", leidas[0].caja.estado)
        assertEquals("Sin fotos", leidas[1].caja.estado)
        assertEquals("05", leidas[0].caja.numeroPos)
        assertEquals("41A2B3C", leidas[0].caja.pieza(Caja.PRINCIPAL).serie)
        assertEquals(setOf("${Caja.PRINCIPAL}|serie", Caja.FOTO_NOTAS, Caja.FOTO_ESTADO), leidas[0].fotos.keys)
        assertEquals(0, leidas[1].fotos.size)
        assertEquals(setOf("Impresora|modelo"), leidas[2].fotos.keys)
        assertArrayEquals(f1.readBytes(), leidas[0].fotos.getValue("${Caja.PRINCIPAL}|serie"))
        assertArrayEquals(f4.readBytes(), leidas[2].fotos.getValue("Impresora|modelo"))

        // Sin fotos el Excel sigue siendo de una sola hoja y se puede leer.
        val sinFotos = File("build/prueba-sin-fotos.xlsx")
        XlsxWriter.escribir(sinFotos, Caja.ENCABEZADO, cajas.mapIndexed { i, c -> c.copy(fotos = emptyMap()).comoFila(i + 1) }, Caja.ANCHOS)
        assertEquals(3, sinFotos.inputStream().use { XlsxReader.leerCajas(it) }.size)
    }
}

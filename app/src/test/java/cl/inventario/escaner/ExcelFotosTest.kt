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
            base.copy(trabajo = Caja.RETIRO, fotos = mapOf("foto_equipo" to f1.name, "foto_lugar" to f2.name, "foto_adicional" to f3.name)),
            base.copy(id = 2, trabajo = Caja.RETIRO, numeroPos = "06", ubicacion = "Caja 4", notas = "", estado = "Sin fotos"),
            // Caja de una versión anterior con «Serie del POS» en Retiro: la columna se agrega para no perder la foto.
            base.copy(id = 4, trabajo = Caja.RETIRO, numeroPos = "08", ubicacion = "Caja 5", fotos = mapOf("foto_serie" to f4.name),
                fotosSeries = mapOf("${Caja.PRINCIPAL}|serie" to f1.name)),
            base.copy(id = 3, trabajo = Caja.INSTALACION, tipo = Caja.SSCO, numeroPos = "07", fotos = mapOf("foto_adicional" to f4.name)),
        )

        val hojas = ExcelInventario.hojas(cajas) { File(dir, it) }
        assertEquals(listOf(Caja.RETIRO, Caja.INSTALACION), hojas.map { it.nombre })
        assertEquals(5, hojas[0].fotos.size)
        assertEquals(1, hojas[1].fotos.size)
        assertEquals(listOf("Foto: Cómo quedó el equipo", "Foto: Dónde quedó el equipo", "Foto: Adicional", "Foto: Serie del POS", "Foto serie: POS/SSCO"),
            hojas[0].encabezado.takeLast(5))
        assertEquals(Caja.PRIMERA_COLUMNA_FOTO + 5, hojas[0].encabezado.size)
        assertEquals(Caja.PRIMERA_COLUMNA_FOTO + 5, hojas[1].encabezado.size)

        val salida = File("build/prueba-fotos.xlsx")
        XlsxWriter.escribir(salida, hojas)

        val leidas = salida.inputStream().use { XlsxReader.leerCajas(it) }
        assertEquals(4, leidas.size)
        assertEquals("Operativo, limpio", leidas[0].caja.estado)
        assertEquals("Sin fotos", leidas[1].caja.estado)
        assertEquals("05", leidas[0].caja.numeroPos)
        assertEquals("512", leidas[0].caja.tienda)
        assertEquals("41A2B3C", leidas[0].caja.pieza(Caja.PRINCIPAL).serie)
        assertEquals(Caja.RETIRO, leidas[0].caja.trabajo)
        assertEquals(Caja.INSTALACION, leidas[3].caja.trabajo)
        assertEquals(setOf("foto_equipo", "foto_lugar", "foto_adicional"), leidas[0].fotos.keys)
        assertEquals(0, leidas[1].fotos.size)
        assertEquals(setOf("foto_serie"), leidas[2].fotos.keys)
        assertEquals(setOf("foto_adicional"), leidas[3].fotos.keys)
        assertArrayEquals(f1.readBytes(), leidas[0].fotos.getValue("foto_equipo"))
        assertArrayEquals(f2.readBytes(), leidas[0].fotos.getValue("foto_lugar"))
        assertArrayEquals(f4.readBytes(), leidas[2].fotos.getValue("foto_serie"))
        // La foto de la serie del componente vuelve a su lugar al cargar el Excel.
        assertEquals(setOf("${Caja.PRINCIPAL}|serie"), leidas[2].fotosSeries.keys)
        assertArrayEquals(f1.readBytes(), leidas[2].fotosSeries.getValue("${Caja.PRINCIPAL}|serie"))
        assertEquals(0, leidas[0].fotosSeries.size)
        assertArrayEquals(f4.readBytes(), leidas[3].fotos.getValue("foto_adicional"))

        // Sin fotos el Excel sigue siendo de una sola hoja y se puede leer.
        val sinFotos = File("build/prueba-sin-fotos.xlsx")
        XlsxWriter.escribir(sinFotos, Caja.ENCABEZADO, cajas.mapIndexed { i, c -> c.copy(fotos = emptyMap()).comoFila(i + 1) }, Caja.ANCHOS)
        assertEquals(4, sinFotos.inputStream().use { XlsxReader.leerCajas(it) }.size)
        assertEquals(listOf("foto_equipo", "foto_lugar", "foto_adicional"), Caja.fotosDe(Caja.RETIRO).map { it.first })
    }
}

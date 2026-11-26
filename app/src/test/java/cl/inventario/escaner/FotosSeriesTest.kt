package cl.inventario.escaner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Franja con los datos que se agrega a las fotos de series antes de enviarlas. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FotosSeriesTest {
    @Test
    fun franjaYOrden() {
        val c = Caja(
            id = 1, fecha = "26-11-2026", cliente = "Jumbo", tienda = "512", direccion = "", region = "",
            tipo = Caja.POS, ubicacion = "Caja 3", numeroPos = "05",
            piezas = mapOf(Caja.PRINCIPAL to Pieza("TCx700", "41A2B3C"), "Scanner de mano" to Pieza("DS2208", "S21345B")),
            notas = "", trabajo = Caja.RETIRO,
            fotosSeries = mapOf("Scanner de mano|serie" to "c.jpg", "${Caja.PRINCIPAL}|serie" to "b.jpg", "${Caja.PRINCIPAL}|modelo" to "a.jpg"),
        )
        // Solo se envían las fotos de la serie (la del modelo no).
        assertEquals(listOf("b.jpg", "c.jpg"), FotosSeries.ordenadas(c).map { it.second })
        assertEquals(2, FotosSeries.cuantas(c))
        val (p, d) = FotosSeries.titulos(c, "${Caja.PRINCIPAL}|serie")
        assertEquals("POS · Serie: 41A2B3C", p)
        assertEquals("Retiro · Jumbo Tienda 512 · POS N° 05 · Caja 3 · 26-11-2026", d)

        val foto = javaClass.classLoader!!.getResourceAsStream("fotos/pos-serie.jpg")!!.use { BitmapFactory.decodeStream(it) }
        val out = FotosSeries.conFranja(foto, p, d)
        assertEquals(foto.width, out.width)
        File("build").mkdirs()
        File("build/ot-preview-foto-serie.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

package cl.inventario.escaner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Dibuja OTs de ejemplo y las guarda como imagen para revisarlas (build/ot-preview-*.png). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OtPdfTest {

    private fun firmaDePrueba(): Bitmap {
        val bmp = Bitmap.createBitmap(600, 200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 30, 90); style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND
        }
        val path = Path().apply {
            moveTo(30f, 150f); cubicTo(80f, 20f, 130f, 190f, 180f, 90f); cubicTo(220f, 20f, 260f, 170f, 320f, 110f)
            cubicTo(370f, 60f, 420f, 160f, 470f, 100f); lineTo(570f, 80f)
        }
        c.drawPath(path, p)
        return bmp
    }

    private fun dibujar(ot: OrdenTrabajo, firma: Bitmap?, salida: String) {
        val ctx = RuntimeEnvironment.getApplication()
        val logo = BitmapFactory.decodeResource(ctx.resources, R.drawable.logo_ncr_voyix)
        assertNotNull(logo)
        val escala = 2f
        val bmp = Bitmap.createBitmap((OtPdf.ANCHO * escala).toInt(), (OtPdf.ALTO * escala).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(escala, escala)
        OtPdf.dibujar(c, ot, firma, logo)
        File("build").mkdirs()
        File("build/$salida").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun otDeEjemplo() {
        val ot = OrdenTrabajo(
            numero = "WO-2026-001845", fecha = "25-11-2026", cliente = "Jumbo", local = "Jumbo Costanera Center N° 512",
            direccion = "Av. Andrés Bello 2425, Providencia", tecnico = "Antonio Muñoz", estado = "Finalizada",
            lineas = listOf(
                LineaOt("Retiro", "1", "TCx700", "POS", "41A2B3C"),
                LineaOt("Retiro", "1", "TM-T88V", "Impresora", "X5Y6Z7W8"),
                LineaOt("Instalación", "2", "Zebra DS2208", "Scanner de mano", "S/N 21345B0123456789"),
                LineaOt("Instalación", "1", "NCR 7879-6000", "Scanner de mesón", "18-34567890"),
                LineaOt("Mantención", "10", "Elo 1509L", "Pantalla", "J21A012345"),
            ),
            observaciones = "Se retira POS antiguo y se instala equipo nuevo. Cableado ordenado y rotulado. " +
                "Cliente conforme con la instalación. Se deja boleta de prueba certificada.",
            recibeNombre = "María José González", recibeCargo = "Jefa de cajas",
        )
        dibujar(ot, firmaDePrueba(), "ot-preview-llena.png")
        dibujar(OrdenTrabajo(), null, "ot-preview-vacia.png")
    }
}

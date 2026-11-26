package cl.inventario.escaner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    /** Firma hecha como la haría el cliente: trazos con el dedo sobre FirmaView. */
    private fun firmaDePrueba(): Bitmap {
        val v = FirmaView(RuntimeEnvironment.getApplication())
        v.layout(0, 0, 1000, 380)
        fun trazo(puntos: List<Pair<Float, Float>>) {
            val t = SystemClock.uptimeMillis()
            v.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, puntos[0].first, puntos[0].second, 0))
            puntos.drop(1).forEachIndexed { i, (x, y) ->
                v.onTouchEvent(MotionEvent.obtain(t, t + i * 10L, MotionEvent.ACTION_MOVE, x, y, 0))
            }
            val (x, y) = puntos.last()
            v.onTouchEvent(MotionEvent.obtain(t, t + 999, MotionEvent.ACTION_UP, x, y, 0))
        }
        // Una «firma» con varias curvas y una línea final.
        trazo((0..60).map { i -> val x = 120f + i * 9f; x to (220f + 70f * kotlin.math.sin(i / 5.0).toFloat()) })
        trazo(listOf(300f to 300f, 420f to 120f, 520f to 310f))
        trazo(listOf(640f to 260f, 880f to 200f))
        val f = v.comoImagen()
        assertNotNull(f)
        // La firma completa ocupa casi todo el ancho; antes del arreglo quedaba un recorte de unos pocos píxeles.
        assertTrue("ancho de la firma: ${f!!.width}", f.width > 700)
        assertTrue("alto de la firma: ${f.height}", f.height > 150)
        return f
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
                LineaOt("Reposicionamiento", "1", "", "SSCO", "SSCO-91"),
                LineaOt("Otros", "1", "", "Gaveta", "G-0001", actividadOtra = "Cambio de cerradura"),
            ),
            observaciones = "Se retira POS antiguo y se instala equipo nuevo. Cableado ordenado y rotulado. " +
                "Cliente conforme con la instalación. Se deja boleta de prueba certificada.",
            recibeNombre = "María José González", recibeCargo = "Jefa de cajas",
        )
        dibujar(ot, firmaDePrueba(), "ot-preview-llena.png")
        dibujar(OrdenTrabajo(), null, "ot-preview-vacia.png")

        // Página de imágenes (2 por hoja)
        val ctx = RuntimeEnvironment.getApplication()
        val logo = BitmapFactory.decodeResource(ctx.resources, R.drawable.logo_ncr_voyix)
        val fotos = listOf("pos-serie", "notas").map { n ->
            javaClass.classLoader!!.getResourceAsStream("fotos/$n.jpg")!!.use { BitmapFactory.decodeStream(it) }
        }
        val bmp = Bitmap.createBitmap(OtPdf.ANCHO * 2, OtPdf.ALTO * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(2f, 2f)
        OtPdf.dibujarImagenes(c, ot, fotos, 0, 3, logo)
        File("build/ot-preview-imagenes.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // «Otros» usa lo escrito; el resto, el tipo elegido.
        org.junit.Assert.assertEquals("Cambio de cerradura", ot.lineas[6].actividadParaPdf)
        org.junit.Assert.assertEquals("Reposicionamiento", ot.lineas[5].actividadParaPdf)
    }
}

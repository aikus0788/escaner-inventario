package cl.inventario.escaner

import org.junit.Assert.assertEquals
import org.junit.Test

/** El detalle de la OT desde el Inventario cuenta equipos por trabajo, tipo y modelo. */
class ConteoOtTest {
    private fun caja(id: Long, trabajo: String, pos: String, serie: String, impresora: Pair<String, String>? = null) = Caja(
        id = id, fecha = "28-11-2026", cliente = "Jumbo", tienda = "512", direccion = "", region = "", tipo = Caja.POS,
        ubicacion = "", numeroPos = pos, notas = "", trabajo = trabajo,
        piezas = buildMap {
            put(Caja.PRINCIPAL, Pieza("TCx700", serie))
            impresora?.let { put("Impresora", Pieza(it.first, it.second)) }
        },
    )

    @Test
    fun cuentaPorModelo() {
        val cajas = listOf(
            caja(1, Caja.RETIRO, "1", "S1", "TM-T88V" to "I1"),
            caja(2, Caja.RETIRO, "37", "S37", "TM-T88V" to "I37"),
            caja(3, Caja.RETIRO, "15", "S15"),
            caja(4, Caja.RETIRO, "39", "S39"),
            caja(5, Caja.RETIRO, "20", "S20", "TM-T20" to "I20"),
            caja(6, Caja.INSTALACION, "40", "N40"),
        )
        val l = lineasDesdeInventario(cajas)
        assertEquals(
            listOf(
                LineaOt(Caja.RETIRO, "5", "TCx700", "POS", ""),
                LineaOt(Caja.RETIRO, "2", "TM-T88V", "Impresora", ""),
                LineaOt(Caja.RETIRO, "1", "TM-T20", "Impresora", "I20"),
                LineaOt(Caja.INSTALACION, "1", "TCx700", "POS", "N40"),
            ),
            l,
        )
        // Solo retiro: solo líneas de retiro.
        assertEquals(setOf(Caja.RETIRO), lineasDesdeInventario(cajas.take(5)).map { it.tipoActividad }.toSet())
    }
}

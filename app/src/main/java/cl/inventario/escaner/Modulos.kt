package cl.inventario.escaner

import android.app.Activity
import android.content.Context
import androidx.annotation.DrawableRes

/**
 * Un módulo de Infomaxis: cada uno es una herramienta independiente que se abre desde la pantalla de inicio.
 *
 * Para agregar un módulo nuevo:
 *  1. Crea su Activity (y regístrala en AndroidManifest.xml).
 *  2. Agrégalo a la lista [Modulos.todos].
 */
data class Modulo(
    val titulo: String,
    val descripcion: String,
    @DrawableRes val icono: Int,
    val pantalla: Class<out Activity>,
    /** Texto breve con el estado del módulo, p. ej. "12 cajas en la planilla". */
    val resumen: (Context) -> String = { "" },
)

object Modulos {
    val todos: List<Modulo> = listOf(
        Modulo(
            titulo = "Inventario equipos",
            descripcion = "Registra cajas POS y SSCO con modelo y serie de cada componente, y expórtalas a Excel.",
            icono = R.drawable.ic_mod_inventario,
            pantalla = MainActivity::class.java,
            resumen = { ctx ->
                val n = Almacen(ctx).cargar().size
                when (n) {
                    0 -> "Planilla vacía"
                    1 -> "1 caja en la planilla"
                    else -> "$n cajas en la planilla"
                }
            },
        ),
        Modulo(
            titulo = "Drive",
            descripcion = "Abre el Drive de la empresa en el navegador para buscar y descargar archivos.",
            icono = R.drawable.ic_mod_drive,
            pantalla = DriveActivity::class.java,
        ),
    )
}

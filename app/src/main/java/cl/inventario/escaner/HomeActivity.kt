package cl.inventario.escaner

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import cl.inventario.escaner.databinding.ActivityHomeBinding

/** Pantalla de inicio de Infomaxis: muestra el logo y los módulos disponibles. */
class HomeActivity : AppCompatActivity() {

    private lateinit var b: ActivityHomeBinding
    private lateinit var actualizador: Actualizador
    private val resumenes = mutableListOf<Pair<Modulo, TextView>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)

        actualizador = Actualizador(this)
        b.txtVersion.text = "Versión ${actualizador.nombreInstalado()}"
        b.btnActualizar.setOnClickListener { actualizador.revisarYPreguntar() }

        for (m in Modulos.todos) {
            val v = layoutInflater.inflate(R.layout.item_modulo, b.contenedorModulos, false)
            v.findViewById<ImageView>(R.id.imgIcono).setImageResource(m.icono)
            v.findViewById<TextView>(R.id.txtTitulo).text = m.titulo
            v.findViewById<TextView>(R.id.txtDescripcion).text = m.descripcion
            resumenes.add(m to v.findViewById(R.id.txtResumen))
            v.setOnClickListener { startActivity(Intent(this, m.pantalla)) }
            b.contenedorModulos.addView(v)
        }

        // Revisa en silencio si hay versión nueva.
        actualizador.buscar { ver ->
            if (ver != null && ver.codigo > actualizador.codigoInstalado()) {
                b.btnActualizar.text = "Instalar versión nueva"
                b.btnActualizar.backgroundTintList = ContextCompat.getColorStateList(this, R.color.amarillo)
                b.btnActualizar.setTextColor(ContextCompat.getColor(this, R.color.tinta))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Al volver de un módulo, se actualiza su resumen (por ejemplo, la cantidad de cajas).
        for ((m, txt) in resumenes) {
            val r = try { m.resumen(this) } catch (_: Exception) { "" }
            txt.text = r
            txt.visibility = if (r.isEmpty()) View.GONE else View.VISIBLE
        }
    }
}

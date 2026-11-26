package cl.inventario.escaner

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import cl.inventario.escaner.databinding.ActivityHomeBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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
        b.btnDescargaApp.setOnClickListener { compartirApp() }

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

    // ---------------- Compartir la app ----------------

    private val mensajeCompartir get() =
        "Descarga la app Infomaxis (Android o iPhone):\n$ENLACE_DESCARGA\n\n" +
            "Abre el enlace en el teléfono y sigue los pasos que aparecen."

    private fun compartirApp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Descarga app")
            .setMessage("Enlace para instalar Infomaxis en otro teléfono. Detecta si es Android (descarga la app) o iPhone (abre la versión web).\n\n$ENLACE_DESCARGA")
            .setPositiveButton("Compartir") { _, _ -> compartir(null) }
            .setNegativeButton("WhatsApp") { _, _ -> compartir("com.whatsapp") }
            .setNeutralButton("Copiar enlace") { _, _ -> copiarEnlace() }
            .show()
    }

    /** Abre el menú para compartir de Android (WhatsApp, Telegram, Facebook, correo…) o directo una app. */
    private fun compartir(paquete: String?) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "App Infomaxis")
            putExtra(Intent.EXTRA_TEXT, mensajeCompartir)
            if (paquete != null) setPackage(paquete)
        }
        try {
            if (paquete != null) {
                startActivity(intent)
            } else {
                startActivity(Intent.createChooser(intent, "Compartir enlace de Infomaxis"))
            }
        } catch (_: ActivityNotFoundException) {
            if (paquete == "com.whatsapp") {
                // WhatsApp Business u otra versión: se usa el enlace web de WhatsApp.
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=" + Uri.encode(mensajeCompartir))))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this, "WhatsApp no está instalado. Usa «Compartir».", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "No hay apps para compartir.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun copiarEnlace() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Enlace Infomaxis", ENLACE_DESCARGA))
        Toast.makeText(this, "Enlace copiado", Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** Página de descarga: en Android ofrece la última APK; en iPhone, la versión web instalable. */
        const val ENLACE_DESCARGA = "https://aikus0788.github.io/escaner-inventario/descargar.html"
    }
}

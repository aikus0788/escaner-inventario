package cl.inventario.escaner

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cl.inventario.escaner.databinding.ActivityDriveBinding

/** Módulo Drive: abre el navegador en el inicio de sesión de Google y, al entrar, en el Drive. */
class DriveActivity : AppCompatActivity() {

    private lateinit var b: ActivityDriveBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDriveBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnVolver.setOnClickListener { finish() }
        b.btnIniciar.setOnClickListener { abrirNavegador() }
    }

    private fun abrirNavegador() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(URL_INICIO)).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No hay un navegador instalado en el teléfono.", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        /** Inicio de sesión de Google que, al terminar, deja a la persona en «Mi unidad» del Drive. */
        const val URL_INICIO =
            "https://accounts.google.com/ServiceLogin?service=writely&continue=https%3A%2F%2Fdrive.google.com%2Fdrive%2Fmy-drive"
    }
}

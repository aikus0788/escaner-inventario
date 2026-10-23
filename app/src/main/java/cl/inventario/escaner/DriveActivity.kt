package cl.inventario.escaner

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cl.inventario.escaner.DriveApi.ErrorDrive
import cl.inventario.escaner.DriveApi.Item
import cl.inventario.escaner.databinding.ActivityDriveBinding
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Módulo Drive: inicia sesión con Google, busca en el Drive y abre o descarga los archivos. */
class DriveActivity : AppCompatActivity() {

    private lateinit var b: ActivityDriveBinding
    private val prefs by lazy { getSharedPreferences("drive", Context.MODE_PRIVATE) }

    private val solicitud = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.readonly")))
        .build()

    @Volatile private var token: String? = null

    /** Lo que se ve en la lista: los resultados de la búsqueda o el contenido de una carpeta. */
    private class Vista(val titulo: String, val items: List<Item>, val info: String)

    private val pila = ArrayDeque<Vista>()
    private var items: List<Item> = emptyList()
    private var ocupado = false

    private val pantallaGoogle = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        try {
            val r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data)
            val t = r.accessToken
            if (t != null) conectado(t) else mostrarLogin("Google no entregó el acceso. Inténtalo de nuevo.")
        } catch (e: ApiException) {
            mostrarLogin(mensajeGoogle(e))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDriveBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnVolver.setOnClickListener { finish() }
        b.btnLogin.setOnClickListener { iniciarSesion() }
        b.btnSalir.setOnClickListener { confirmarSalir() }

        b.lista.layoutManager = LinearLayoutManager(this)
        b.lista.adapter = adaptador
        b.boxBuscar.setEndIconOnClickListener { buscar() }
        b.edtBuscar.setOnEditorActionListener { _, accion, _ ->
            if (accion == EditorInfo.IME_ACTION_SEARCH) { buscar(); true } else false
        }
        b.btnAtrasCarpeta.setOnClickListener { volverCarpeta() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pila.size > 1) volverCarpeta() else finish()
            }
        })

        if (prefs.getString("correo", "").isNullOrEmpty()) mostrarLogin(null) else reconectarEnSilencio()
    }

    // ---------------- Sesión de Google ----------------

    private fun mostrarLogin(error: String?) {
        b.panelLogin.visibility = View.VISIBLE
        b.panelBuscar.visibility = View.GONE
        if (error != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("No se pudo iniciar sesión")
                .setMessage(error)
                .setPositiveButton("Entendido", null)
                .show()
        }
    }

    private fun mostrarBuscador() {
        b.panelLogin.visibility = View.GONE
        b.panelBuscar.visibility = View.VISIBLE
        b.txtCuenta.text = "Conectado: " + prefs.getString("correo", "").orEmpty()
        if (pila.isEmpty()) {
            b.txtVacio.visibility = View.VISIBLE
            b.txtInfo.text = ""
        }
    }

    /** Abre la ventana de Google para elegir o agregar la cuenta. */
    private fun iniciarSesion() {
        cargando(true)
        Identity.getAuthorizationClient(this).authorize(solicitud)
            .addOnSuccessListener { r ->
                cargando(false)
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    pantallaGoogle.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    r.accessToken?.let { conectado(it) } ?: mostrarLogin("Google no entregó el acceso. Inténtalo de nuevo.")
                }
            }
            .addOnFailureListener { e ->
                cargando(false)
                mostrarLogin(if (e is ApiException) mensajeGoogle(e) else (e.message ?: "Error desconocido"))
            }
    }

    private fun mensajeGoogle(e: ApiException): String = when (e.statusCode) {
        CommonStatusCodes.CANCELED -> "Cancelaste el inicio de sesión."
        CommonStatusCodes.NETWORK_ERROR -> "No hay conexión a internet."
        CommonStatusCodes.DEVELOPER_ERROR ->
            "La app todavía no está registrada en Google Cloud para acceder al Drive. " +
                "Falta el paso de configuración del administrador (código 10)."
        else -> "Google respondió con el código ${e.statusCode}. ${e.message.orEmpty()}"
    }

    private fun conectado(t: String) {
        token = t
        enSegundoPlano(
            { DriveApi.correo(t) },
            { correo ->
                prefs.edit().putString("correo", correo).apply()
                mostrarBuscador()
                aviso("Conectado como $correo")
            },
            { e -> manejarError(e) },
        )
    }

    /** Si ya se había iniciado sesión antes, pide un token nuevo sin mostrar nada. */
    private fun reconectarEnSilencio() {
        mostrarBuscador()
        enSegundoPlano(
            { tokenSilencioso() },
            { t -> token = t },
            { e -> manejarError(e) },
        )
    }

    /** Se llama en segundo plano. */
    private fun tokenSilencioso(): String {
        val r = try {
            Tasks.await(Identity.getAuthorizationClient(this).authorize(solicitud))
        } catch (e: Exception) {
            throw ErrorDrive("LOGIN", "Vuelve a iniciar sesión con Google.")
        }
        if (r.hasResolution()) throw ErrorDrive("LOGIN", "Vuelve a iniciar sesión con Google.")
        return r.accessToken ?: throw ErrorDrive("LOGIN", "Vuelve a iniciar sesión con Google.")
    }

    /** Ejecuta una llamada a Drive con el token; si venció, pide otro y reintenta una vez. */
    private fun <T> conToken(trabajo: (String) -> T): T {
        val t = token ?: tokenSilencioso().also { token = it }
        return try {
            trabajo(t)
        } catch (e: ErrorDrive) {
            if (e.codigo != "TOKEN") throw e
            try { GoogleAuthUtil.clearToken(this, t) } catch (_: Exception) {}
            val nuevo = tokenSilencioso()
            token = nuevo
            trabajo(nuevo)
        }
    }

    private fun confirmarSalir() {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Cerrar sesión de Google?")
            .setMessage("La app dejará de tener acceso al Drive hasta que vuelvas a iniciar sesión.\n\n" +
                "La cuenta de Google puede seguir agregada en el teléfono (Ajustes › Cuentas); quítala ahí si este teléfono no es tuyo.")
            .setPositiveButton("Cerrar sesión") { _, _ -> salir() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun salir() {
        val t = token
        enSegundoPlano(
            {
                if (t != null) {
                    DriveApi.revocar(t)
                    try { GoogleAuthUtil.clearToken(this, t) } catch (_: Exception) {}
                }
            },
            {
                token = null
                prefs.edit().remove("correo").apply()
                pila.clear()
                pintar()
                mostrarLogin(null)
                aviso("Sesión cerrada")
            },
            { _ -> },
        )
    }

    // ---------------- Trabajo en segundo plano ----------------

    private fun cargando(si: Boolean) {
        ocupado = si
        b.progreso.visibility = if (si) View.VISIBLE else View.INVISIBLE
        b.btnLogin.isEnabled = !si
    }

    private fun <T> enSegundoPlano(trabajo: () -> T, ok: (T) -> Unit, error: (ErrorDrive) -> Unit) {
        cargando(true)
        Thread {
            val r: Result<T> = try {
                Result.success(trabajo())
            } catch (e: ErrorDrive) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(ErrorDrive("ERROR", e.message ?: "Error desconocido"))
            }
            runOnUiThread {
                cargando(false)
                r.fold(ok) { e -> error(e as ErrorDrive) }
            }
        }.start()
    }

    private fun manejarError(e: ErrorDrive) {
        when (e.codigo) {
            "LOGIN" -> {
                token = null
                mostrarLogin(null)
                aviso("Vuelve a iniciar sesión con Google.")
            }
            "SIN_API" -> MaterialAlertDialogBuilder(this)
                .setTitle("Falta un paso de configuración")
                .setMessage(e.message)
                .setPositiveButton("Entendido", null)
                .show()
            else -> aviso(e.message ?: "")
        }
    }

    // ---------------- Buscar y navegar ----------------

    private fun ocultarTeclado() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(b.edtBuscar.windowToken, 0)
    }

    private fun buscar() {
        val q = b.edtBuscar.text?.toString()?.trim().orEmpty()
        if (q.length < 2) {
            aviso("Escribe al menos 2 letras para buscar.")
            return
        }
        if (ocupado) return
        ocultarTeclado()
        b.txtInfo.text = "Buscando «$q» en todo el Drive…"
        enSegundoPlano(
            { conToken { t -> DriveApi.buscar(t, q) } },
            { (lista, hayMas) ->
                val n = lista.size
                val info = when {
                    n == 0 -> "No se encontró nada con «$q». Prueba con otra palabra."
                    hayMas -> "$n resultados para «$q» (hay más: escribe algo más específico)"
                    n == 1 -> "1 resultado para «$q»"
                    else -> "$n resultados para «$q»"
                }
                pila.clear()
                pila.addLast(Vista("Resultados", lista, info))
                pintar()
            },
            { e ->
                b.txtInfo.text = ""
                manejarError(e)
            },
        )
    }

    private fun abrirCarpeta(c: Item) {
        if (ocupado) return
        enSegundoPlano(
            { conToken { t -> DriveApi.carpeta(t, c.id) } },
            { lista ->
                val info = if (lista.isEmpty()) "Carpeta vacía" else "${lista.size} elementos"
                pila.addLast(Vista(c.nombre, lista, info))
                pintar()
            },
            { e -> manejarError(e) },
        )
    }

    private fun volverCarpeta() {
        if (pila.size > 1) {
            pila.removeLast()
            pintar()
        }
    }

    private fun pintar() {
        val v = pila.lastOrNull()
        items = v?.items ?: emptyList()
        adaptador.notifyDataSetChanged()
        b.lista.scrollToPosition(0)
        b.txtInfo.text = v?.info ?: ""
        b.txtVacio.visibility = if (v == null) View.VISIBLE else View.GONE
        val enCarpeta = pila.size > 1
        b.barraCarpeta.visibility = if (enCarpeta) View.VISIBLE else View.GONE
        b.txtCarpeta.text = if (enCarpeta) "📁 " + pila.drop(1).joinToString(" › ") { it.titulo } else ""
        b.btnAtrasCarpeta.text = if (pila.size == 2) "‹ Resultados" else "‹ Atrás"
    }

    // ---------------- Abrir y descargar ----------------

    private fun opciones(it: Item) {
        val acciones = arrayOf("Ver en el teléfono", "Descargar al teléfono", "Abrir en Google Drive")
        MaterialAlertDialogBuilder(this)
            .setTitle(it.nombre)
            .setItems(acciones) { _, i ->
                when (i) {
                    0 -> traerArchivo(it, abrir = true)
                    1 -> traerArchivo(it, abrir = false)
                    else -> abrirEnDrive(it.url)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun abrirEnDrive(url: String) {
        if (url.isBlank()) {
            aviso("Este archivo no tiene link de Drive.")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            aviso("No hay un navegador para abrir el link.")
        }
    }

    private fun traerArchivo(it: Item, abrir: Boolean) {
        if (ocupado) return
        val espera: AlertDialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (abrir) "Abriendo…" else "Descargando…")
            .setMessage(it.nombre)
            .setCancelable(false)
            .show()
        val carpeta = File(cacheDir, "drive").apply { mkdirs() }
        carpeta.listFiles()?.forEach { f -> f.delete() }
        enSegundoPlano(
            {
                conToken { t ->
                    DriveApi.descargar(t, it, carpeta) { pct ->
                        runOnUiThread { espera.setMessage("${it.nombre}\n\n$pct %") }
                    }
                }
            },
            { (archivo, tipo) ->
                espera.dismiss()
                val mime = tipo.takeIf { t -> t.isNotBlank() && !t.startsWith("application/vnd.google-apps") } ?: mimeDe(archivo.name)
                if (abrir) verArchivo(archivo, mime) else guardarEnDescargas(archivo, mime)
            },
            { e ->
                espera.dismiss()
                if (e.codigo == "MUY_GRANDE" || e.codigo == "NO_DESCARGABLE") {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("No se puede traer al teléfono")
                        .setMessage(e.message + "\n\nPuedes abrirlo en Google Drive.")
                        .setPositiveButton("Abrir en Google Drive") { _, _ -> abrirEnDrive(it.url) }
                        .setNegativeButton("Cerrar", null)
                        .show()
                } else {
                    manejarError(e)
                }
            },
        )
    }

    private fun verArchivo(archivo: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", archivo)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("No hay app para abrirlo")
                .setMessage("Ninguna app del teléfono abre este tipo de archivo. Puedes guardarlo en Descargas.")
                .setPositiveButton("Guardar en Descargas") { _, _ -> guardarEnDescargas(archivo, mime) }
                .setNegativeButton("Cerrar", null)
                .show()
            return
        }
        try {
            startActivity(Intent.createChooser(intent, "Abrir con…"))
        } catch (_: ActivityNotFoundException) {
            aviso("Ninguna app del teléfono abre este tipo de archivo.")
        }
    }

    private fun guardarEnDescargas(archivo: File, mime: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val valores = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, archivo.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
                    ?: throw IllegalStateException("sin acceso a Descargas")
                contentResolver.openOutputStream(uri)?.use { out -> archivo.inputStream().use { it.copyTo(out) } }
                aviso("Guardado en Descargas: ${archivo.name}")
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                archivo.copyTo(File(dir, archivo.name), overwrite = true)
                aviso("Guardado en ${dir.absolutePath}")
            }
        } catch (e: Exception) {
            aviso("No se pudo guardar: ${e.message}")
        }
    }

    private fun mimeDe(nombre: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(nombre.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private fun aviso(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------------- Lista ----------------

    private val formatoEntrada = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val formatoSalida = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())

    private fun fecha(iso: String): String = try {
        formatoSalida.format(formatoEntrada.parse(iso.take(19))!!)
    } catch (_: Exception) {
        ""
    }

    private fun tamano(n: Long): String = when {
        n <= 0 -> ""
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "${n / 1024} KB"
        else -> "%.1f MB".format(n / 1048576.0)
    }

    /** Etiqueta y color según el tipo de archivo. */
    private fun insignia(it: Item): Pair<String, Int> {
        if (it.esCarpeta) return "📁" to Color.parseColor("#F0960A")
        val t = it.tipo
        val ext = it.nombre.substringAfterLast('.', "").lowercase()
        return when {
            t == "application/pdf" || ext == "pdf" -> "PDF" to Color.parseColor("#D93025")
            t.contains("google-apps.document") || t.contains("wordprocessing") || ext in listOf("doc", "docx", "odt", "rtf") ->
                "DOC" to Color.parseColor("#1A73E8")
            t.contains("google-apps.spreadsheet") || t.contains("spreadsheet") || ext in listOf("xls", "xlsx", "csv", "ods") ->
                "XLS" to Color.parseColor("#188038")
            t.contains("google-apps.presentation") || t.contains("presentation") || ext in listOf("ppt", "pptx", "odp") ->
                "PPT" to Color.parseColor("#E8710A")
            t.startsWith("image/") -> "IMG" to Color.parseColor("#8E24AA")
            t.startsWith("video/") -> "VID" to Color.parseColor("#C5221F")
            t.startsWith("audio/") -> "AUD" to Color.parseColor("#00897B")
            t.startsWith("text/") -> "TXT" to Color.parseColor("#5C6170")
            ext in listOf("zip", "rar", "7z") -> "ZIP" to Color.parseColor("#5C6170")
            ext.length in 1..4 -> ext.uppercase() to Color.parseColor("#5C6170")
            else -> "ARCH" to Color.parseColor("#5C6170")
        }
    }

    private val adaptador = object : RecyclerView.Adapter<ItemVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ItemVH(LayoutInflater.from(parent.context).inflate(R.layout.item_drive, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: ItemVH, position: Int) {
            val it = items[position]
            val (texto, color) = insignia(it)
            h.badge.text = texto
            h.badge.backgroundTintList = ColorStateList.valueOf(color)
            h.nombre.text = it.nombre
            h.detalle.text = listOf(
                if (it.esCarpeta) "Carpeta" else it.carpeta.takeIf { c -> c.isNotEmpty() }?.let { c -> "en $c" } ?: "",
                fecha(it.modificado),
                if (it.esCarpeta) "" else tamano(it.tamano),
            ).filter { s -> s.isNotEmpty() }.joinToString(" · ")
            h.itemView.setOnClickListener { _ -> if (it.esCarpeta) abrirCarpeta(it) else opciones(it) }
        }
    }

    class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
        val badge: TextView = v.findViewById(R.id.txtBadge)
        val nombre: TextView = v.findViewById(R.id.txtNombre)
        val detalle: TextView = v.findViewById(R.id.txtDetalle)
    }
}

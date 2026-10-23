package cl.inventario.escaner

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cl.inventario.escaner.databinding.ActivityDriveBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Módulo Drive: busca en el Drive de la empresa y abre o descarga los archivos. */
class DriveActivity : AppCompatActivity() {

    private lateinit var b: ActivityDriveBinding
    private lateinit var servidor: ServidorDrive

    data class Item(
        val id: String,
        val nombre: String,
        val tipo: String,
        val tamano: Long,
        val modificado: String,
        val url: String,
        val carpeta: String,
    ) {
        val esCarpeta get() = tipo == "carpeta"
    }

    /** Lo que se ve en la lista: los resultados de la búsqueda o el contenido de una carpeta. */
    private class Vista(val titulo: String, val items: List<Item>, val info: String)

    private val pila = ArrayDeque<Vista>()
    private var items: List<Item> = emptyList()
    private var ocupado = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDriveBinding.inflate(layoutInflater)
        setContentView(b.root)
        servidor = ServidorDrive(this)

        b.btnVolver.setOnClickListener { finish() }
        b.btnGuardarUrl.setOnClickListener { guardarUrl() }
        b.btnSolicitar.setOnClickListener { solicitar() }
        b.btnRevisar.setOnClickListener { revisarEstado(mostrarAviso = true) }
        b.btnCambiarServidor.setOnClickListener { mostrar(Panel.CONFIG) }
        b.edtNombre.setText(servidor.nombre)
        b.edtUrl.setText(servidor.url)

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

        iniciar()
    }

    override fun onResume() {
        super.onResume()
        if (servidor.estado == "pendiente" && servidor.url.isNotBlank()) revisarEstado(mostrarAviso = false)
    }

    // ---------------- Estados de la pantalla ----------------

    private enum class Panel { CONFIG, ACCESO, BUSCAR }

    private fun mostrar(p: Panel) {
        b.panelConfig.visibility = if (p == Panel.CONFIG) View.VISIBLE else View.GONE
        b.panelAcceso.visibility = if (p == Panel.ACCESO) View.VISIBLE else View.GONE
        b.panelBuscar.visibility = if (p == Panel.BUSCAR) View.VISIBLE else View.GONE
        if (p == Panel.BUSCAR && pila.isEmpty()) {
            b.txtVacio.visibility = View.VISIBLE
            b.txtInfo.text = ""
        }
    }

    private fun cargando(si: Boolean) {
        ocupado = si
        b.progreso.visibility = if (si) View.VISIBLE else View.INVISIBLE
        b.btnSolicitar.isEnabled = !si
        b.btnRevisar.isEnabled = !si
        b.btnGuardarUrl.isEnabled = !si
    }

    private fun iniciar() {
        if (servidor.url.isNotBlank()) {
            if (servidor.estado == "aprobado") mostrar(Panel.BUSCAR) else revisarEstado(mostrarAviso = false)
            return
        }
        // Primera vez: busca la dirección publicada por el administrador.
        cargando(true)
        Thread {
            val u = servidor.buscarDireccionPublicada()
            runOnUiThread {
                cargando(false)
                if (u != null) {
                    servidor.url = u
                    b.edtUrl.setText(u)
                    revisarEstado(mostrarAviso = false)
                } else {
                    mostrar(Panel.CONFIG)
                }
            }
        }.start()
    }

    private fun guardarUrl() {
        val u = b.edtUrl.text?.toString()?.trim().orEmpty()
        if (!u.startsWith("https://script.google.com/") || !u.contains("/exec")) {
            aviso("La dirección debe empezar con https://script.google.com/ y terminar en /exec")
            return
        }
        servidor.url = u
        servidor.estado = ""
        revisarEstado(mostrarAviso = false)
    }

    // ---------------- Acceso ----------------

    private fun enSegundoPlano(trabajo: () -> JSONObject, ok: (JSONObject) -> Unit, error: (ServidorDrive.ErrorServidor) -> Unit) {
        cargando(true)
        Thread {
            val r = try {
                Result.success(trabajo())
            } catch (e: ServidorDrive.ErrorServidor) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(ServidorDrive.ErrorServidor("ERROR", e.message ?: "Error desconocido"))
            }
            runOnUiThread {
                cargando(false)
                r.fold(ok) { e -> error(e as ServidorDrive.ErrorServidor) }
            }
        }.start()
    }

    private fun revisarEstado(mostrarAviso: Boolean) {
        if (ocupado) return
        enSegundoPlano(
            { servidor.llamar(mapOf("accion" to "estado")) },
            { o ->
                val estado = o.optString("estado")
                servidor.estado = estado
                if (estado == "aprobado") {
                    if (mostrarAviso) aviso("¡Acceso aprobado!")
                    mostrar(Panel.BUSCAR)
                } else {
                    pantallaAcceso(estado)
                    if (mostrarAviso && estado == "pendiente") aviso("Todavía no te aprueban.")
                }
            },
            { e ->
                if (e.codigo == "RESPUESTA" || e.codigo == "SIN_URL") mostrar(Panel.CONFIG) else pantallaAcceso(servidor.estado)
                aviso(e.message ?: "")
            },
        )
    }

    private fun pantallaAcceso(estado: String) {
        mostrar(Panel.ACCESO)
        when (estado) {
            "pendiente" -> {
                b.txtAccesoTitulo.text = "Esperando aprobación"
                b.txtAccesoMensaje.text = "Se envió la solicitud a nombre de ${servidor.nombre.ifEmpty { "este teléfono" }}. " +
                    "Cuando el administrador la apruebe, toca «Revisar si ya me aprobaron»."
                b.boxNombre.visibility = View.GONE
                b.btnSolicitar.visibility = View.GONE
                b.btnRevisar.visibility = View.VISIBLE
            }
            "rechazado" -> {
                b.txtAccesoTitulo.text = "Sin acceso"
                b.txtAccesoMensaje.text = "El administrador no aprobó este teléfono o le quitó el acceso. Si es un error, habla con él y vuelve a pedirlo."
                b.boxNombre.visibility = View.VISIBLE
                b.btnSolicitar.visibility = View.VISIBLE
                b.btnSolicitar.text = "Pedir acceso otra vez"
                b.btnRevisar.visibility = View.VISIBLE
            }
            else -> {
                b.txtAccesoTitulo.text = "Pide acceso al Drive"
                b.txtAccesoMensaje.text = "Para buscar en el Drive de la empresa, el administrador debe aprobar este teléfono. Le llegará un correo para hacerlo."
                b.boxNombre.visibility = View.VISIBLE
                b.btnSolicitar.visibility = View.VISIBLE
                b.btnSolicitar.text = "Solicitar acceso"
                b.btnRevisar.visibility = View.GONE
            }
        }
    }

    private fun solicitar() {
        val nombre = b.edtNombre.text?.toString()?.trim().orEmpty()
        if (nombre.length < 3) {
            b.boxNombre.error = "Escribe tu nombre para que el administrador sepa quién eres"
            return
        }
        b.boxNombre.error = null
        servidor.nombre = nombre
        enSegundoPlano(
            { servidor.llamar(mapOf("accion" to "solicitar", "nombre" to nombre, "modelo" to servidor.modeloTelefono)) },
            { o ->
                servidor.estado = o.optString("estado")
                if (servidor.estado == "aprobado") mostrar(Panel.BUSCAR) else pantallaAcceso(servidor.estado)
                if (servidor.estado == "pendiente") aviso("Solicitud enviada al administrador")
            },
            { e -> aviso(e.message ?: "") },
        )
    }

    private fun sinAcceso(e: ServidorDrive.ErrorServidor): Boolean {
        if (e.codigo != "NO_AUTORIZADO") return false
        servidor.estado = "rechazado"
        pila.clear()
        pantallaAcceso("rechazado")
        return true
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
            { servidor.llamar(mapOf("accion" to "buscar", "q" to q), 90_000) },
            { o ->
                val lista = leerItems(o.optJSONArray("carpetas")) + leerItems(o.optJSONArray("archivos"))
                val n = lista.size
                val info = when {
                    n == 0 -> "No se encontró nada con «$q». Prueba con otra palabra."
                    o.optBoolean("hayMas") -> "$n resultados para «$q» (hay más: escribe algo más específico)"
                    n == 1 -> "1 resultado para «$q»"
                    else -> "$n resultados para «$q»"
                }
                pila.clear()
                pila.addLast(Vista("Resultados", lista, info))
                pintar()
            },
            { e ->
                b.txtInfo.text = ""
                if (!sinAcceso(e)) aviso(e.message ?: "")
            },
        )
    }

    private fun abrirCarpeta(c: Item) {
        if (ocupado) return
        enSegundoPlano(
            { servidor.llamar(mapOf("accion" to "carpeta", "carpeta" to c.id), 90_000) },
            { o ->
                val lista = leerItems(o.optJSONArray("carpetas")) + leerItems(o.optJSONArray("archivos"))
                val info = if (lista.isEmpty()) "Carpeta vacía" else "${lista.size} elementos"
                pila.addLast(Vista(o.optString("nombre", c.nombre), lista, info))
                pintar()
            },
            { e -> if (!sinAcceso(e)) aviso(e.message ?: "") },
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

    private fun leerItems(arr: JSONArray?): List<Item> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(
                id = o.optString("id"),
                nombre = o.optString("nombre"),
                tipo = o.optString("tipo"),
                tamano = o.optLong("tamano"),
                modificado = o.optString("modificado"),
                url = o.optString("url"),
                carpeta = o.optString("carpeta"),
            )
        }
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
            .setMessage(it.nombre + "\n\nLos archivos grandes pueden tardar un poco.")
            .setCancelable(false)
            .show()
        enSegundoPlano(
            { servidor.llamar(mapOf("accion" to "archivo", "archivo" to it.id), 180_000) },
            { o ->
                espera.dismiss()
                try {
                    val bytes = Base64.decode(o.getString("base64"), Base64.DEFAULT)
                    val nombre = nombreSeguro(o.optString("nombre", it.nombre))
                    val mime = o.optString("tipo").ifBlank { mimeDe(nombre) }
                    if (abrir) verArchivo(bytes, nombre, mime) else guardarEnDescargas(bytes, nombre, mime)
                } catch (e: Exception) {
                    aviso("No se pudo preparar el archivo: ${e.message}")
                }
            },
            { e ->
                espera.dismiss()
                if (sinAcceso(e)) return@enSegundoPlano
                if (e.codigo == "MUY_GRANDE" || e.codigo == "NO_CONVERTIBLE") {
                    val url = e.datos?.optString("url").orEmpty().ifEmpty { it.url }
                    MaterialAlertDialogBuilder(this)
                        .setTitle(if (e.codigo == "MUY_GRANDE") "Archivo muy grande" else "No se puede abrir aquí")
                        .setMessage(
                            (if (e.codigo == "MUY_GRANDE") "Este archivo pesa más de 20 MB y no se puede traer por la app." else e.message) +
                                "\n\nPuedes abrirlo en Google Drive (necesitas haber iniciado sesión con una cuenta que tenga acceso).",
                        )
                        .setPositiveButton("Abrir en Google Drive") { _, _ -> abrirEnDrive(url) }
                        .setNegativeButton("Cerrar", null)
                        .show()
                } else {
                    aviso(e.message ?: "")
                }
            },
        )
    }

    private fun verArchivo(bytes: ByteArray, nombre: String, mime: String) {
        val carpeta = File(cacheDir, "drive").apply { mkdirs() }
        carpeta.listFiles()?.forEach { f -> f.delete() }
        val archivo = File(carpeta, nombre).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", archivo)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Abrir con…"))
        } catch (_: ActivityNotFoundException) {
            MaterialAlertDialogBuilder(this)
                .setTitle("No hay app para abrirlo")
                .setMessage("Ninguna app del teléfono abre este tipo de archivo. Puedes guardarlo en Descargas.")
                .setPositiveButton("Guardar en Descargas") { _, _ -> guardarEnDescargas(bytes, nombre, mime) }
                .setNegativeButton("Cerrar", null)
                .show()
        }
    }

    private fun guardarEnDescargas(bytes: ByteArray, nombre: String, mime: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val valores = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
                    ?: throw IllegalStateException("sin acceso a Descargas")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                aviso("Guardado en Descargas: $nombre")
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                File(dir, nombre).writeBytes(bytes)
                aviso("Guardado en ${dir.absolutePath}")
            }
        } catch (e: Exception) {
            aviso("No se pudo guardar: ${e.message}")
        }
    }

    private fun nombreSeguro(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "archivo" }

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

    private fun tamano(b: Long): String = when {
        b <= 0 -> ""
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "${b / 1024} KB"
        else -> "%.1f MB".format(b / 1048576.0)
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

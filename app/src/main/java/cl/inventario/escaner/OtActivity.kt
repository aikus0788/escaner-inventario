package cl.inventario.escaner

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import cl.inventario.escaner.databinding.ActivityOtBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Módulo OT trabajos: se llena la orden de trabajo NCR Voyix y se genera el PDF. */
class OtActivity : AppCompatActivity() {

    private lateinit var b: ActivityOtBinding
    private lateinit var almacen: AlmacenOt
    private val formatoFecha = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())

    /** Campos en pantalla de cada línea del detalle. */
    private class LineaUI(
        val vista: View,
        val tipoActividad: MaterialAutoCompleteTextView,
        val cantidad: EditText,
        val modelo: EditText,
        val tipoEquipo: MaterialAutoCompleteTextView,
        val serie: EditText,
        val lectura: TextView,
    )
    private val lineas = mutableListOf<LineaUI>()

    /** Firma guardada como archivo (cuando se vuelve a abrir una OT a medio llenar). */
    private var archivoFirma: String = ""

    private var objetivo: Pair<Int, Boolean>? = null   // (línea, esSerie)
    private val fotoTemporal by lazy { File(cacheDir, "foto_ot.jpg") }

    private val tomarFoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val o = objetivo ?: return@registerForActivityResult
        if (ok) leerEtiqueta(o.first, o.second)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOtBinding.inflate(layoutInflater)
        setContentView(b.root)
        almacen = AlmacenOt(this)

        savedInstanceState?.let {
            val l = it.getInt("objLinea", -1)
            if (l >= 0) objetivo = l to it.getBoolean("objSerie")
        }

        b.btnVolver.setOnClickListener { finish() }
        b.btnNueva.setOnClickListener { confirmarNueva() }
        b.btnAgregarLinea.setOnClickListener { agregarLinea(LineaOt(cantidad = "1"), enfocar = true) }
        b.btnDesdeInventario.setOnClickListener { copiarDesdeInventario() }
        b.btnGenerar.setOnClickListener { generar() }
        b.edtFecha.setOnClickListener { elegirFecha() }
        b.boxFecha.setEndIconOnClickListener { elegirFecha() }

        b.edtCliente.setAdapter(BuscadorLista(this, CLIENTES))
        b.edtEstado.setAdapter(BuscadorLista(this, OrdenTrabajo.ESTADOS))
        mostrarListaAlTocar(b.edtCliente)
        mostrarListaAlTocar(b.edtEstado)

        // Firma
        b.firma.alCambiar = { guardarFirma() }
        b.btnBorrarFirma.setOnClickListener { borrarFirma() }
        b.imgFirma.setOnClickListener { aviso("Ya hay una firma guardada. Toca «Borrar firma» para firmar de nuevo.") }

        mostrar(almacen.cargar())
    }

    override fun onPause() {
        super.onPause()
        almacen.guardar(leer())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        objetivo?.let {
            outState.putInt("objLinea", it.first)
            outState.putBoolean("objSerie", it.second)
        }
    }

    private fun mostrarListaAlTocar(v: MaterialAutoCompleteTextView) {
        v.setOnClickListener { if (!v.isPopupShowing) v.showDropDown() }
        v.setOnFocusChangeListener { _, foco -> if (foco && v.text.isNullOrEmpty()) v.post { v.showDropDown() } }
    }

    // ---------------- Mostrar y leer la OT ----------------

    private fun mostrar(ot: OrdenTrabajo) {
        b.edtNumero.setText(ot.numero)
        b.edtFecha.setText(ot.fecha.ifEmpty { formatoFecha.format(Date()) })
        b.edtCliente.setText(ot.cliente, false)
        b.edtLocal.setText(ot.local)
        b.edtDireccion.setText(ot.direccion)
        b.edtTecnico.setText(ot.tecnico.ifEmpty { almacen.ultimoTecnico() })
        b.edtEstado.setText(ot.estado, false)
        b.edtObservaciones.setText(ot.observaciones)
        b.edtRecibeNombre.setText(ot.recibeNombre)
        b.edtRecibeCargo.setText(ot.recibeCargo)

        b.contenedorLineas.removeAllViews()
        lineas.clear()
        val ls = ot.lineas.ifEmpty { listOf(LineaOt(cantidad = "1")) }
        ls.take(OrdenTrabajo.MAX_LINEAS).forEach { agregarLinea(it, enfocar = false) }

        archivoFirma = ot.firma
        b.firma.borrar()
        mostrarFirmaGuardada()
    }

    private fun leer() = OrdenTrabajo(
        numero = b.edtNumero.texto(),
        fecha = b.edtFecha.texto(),
        cliente = b.edtCliente.texto(),
        local = b.edtLocal.texto(),
        direccion = b.edtDireccion.texto(),
        tecnico = b.edtTecnico.texto(),
        estado = b.edtEstado.texto(),
        lineas = lineas.map {
            LineaOt(it.tipoActividad.texto(), it.cantidad.texto(), it.modelo.texto(), it.tipoEquipo.texto(), it.serie.texto())
        }.filterNot { it.vacia() },
        observaciones = b.edtObservaciones.text?.toString()?.trim().orEmpty(),
        recibeNombre = b.edtRecibeNombre.texto(),
        recibeCargo = b.edtRecibeCargo.texto(),
        firma = archivoFirma,
    )

    private fun EditText.texto() = text?.toString()?.trim().orEmpty()

    // ---------------- Líneas del detalle ----------------

    private fun agregarLinea(l: LineaOt, enfocar: Boolean) {
        if (lineas.size >= OrdenTrabajo.MAX_LINEAS) {
            aviso("La OT tiene espacio para ${OrdenTrabajo.MAX_LINEAS} líneas.")
            return
        }
        val v = layoutInflater.inflate(R.layout.item_linea_ot, b.contenedorLineas, false)
        val ui = LineaUI(
            v, v.findViewById(R.id.edtTipoActividad), v.findViewById(R.id.edtCantidad), v.findViewById(R.id.edtModelo),
            v.findViewById(R.id.edtTipoEquipo), v.findViewById(R.id.edtSerie), v.findViewById(R.id.txtLectura),
        )
        ui.tipoActividad.setAdapter(BuscadorLista(this, OrdenTrabajo.TIPOS_ACTIVIDAD))
        ui.tipoEquipo.setAdapter(BuscadorLista(this, OrdenTrabajo.TIPOS_EQUIPO))
        mostrarListaAlTocar(ui.tipoActividad)
        mostrarListaAlTocar(ui.tipoEquipo)
        ui.tipoActividad.setText(l.tipoActividad, false)
        ui.cantidad.setText(l.cantidad)
        ui.modelo.setText(l.modelo)
        ui.tipoEquipo.setText(l.tipoEquipo, false)
        ui.serie.setText(l.serie)

        v.findViewById<MaterialButton>(R.id.btnQuitarLinea).setOnClickListener { quitarLinea(ui) }
        v.findViewById<MaterialButton>(R.id.btnFotoModelo).setOnClickListener { abrirCamara(lineas.indexOf(ui), esSerie = false) }
        v.findViewById<MaterialButton>(R.id.btnFotoSerie).setOnClickListener { abrirCamara(lineas.indexOf(ui), esSerie = true) }

        lineas.add(ui)
        b.contenedorLineas.addView(v)
        numerarLineas()
        if (enfocar) {
            ui.tipoActividad.requestFocus()
            b.scroll.post { b.scroll.smoothScrollTo(0, b.contenedorLineas.top + v.top) }
        }
    }

    private fun quitarLinea(ui: LineaUI) {
        lineas.remove(ui)
        b.contenedorLineas.removeView(ui.vista)
        numerarLineas()
    }

    private fun numerarLineas() {
        lineas.forEachIndexed { i, ui -> ui.vista.findViewById<TextView>(R.id.txtNumLinea).text = "Línea ${i + 1}" }
        b.txtCuentaLineas.text = "${lineas.size} de ${OrdenTrabajo.MAX_LINEAS}"
        b.btnAgregarLinea.isEnabled = lineas.size < OrdenTrabajo.MAX_LINEAS
    }

    /** Llena el detalle con los equipos registrados en el módulo de inventario. */
    private fun copiarDesdeInventario() {
        val cajas = Almacen(this).cargar()
        val nuevas = mutableListOf<LineaOt>()
        for (c in cajas) {
            for (comp in Caja.POR_TIPO[c.tipo] ?: Caja.COMPONENTES) {
                val p = c.piezas[comp] ?: continue
                if (p.vacia()) continue
                nuevas.add(LineaOt(c.trabajo, "1", p.modelo, Caja.etiqueta(comp, c.tipo), p.serie))
            }
        }
        if (nuevas.isEmpty()) {
            aviso("El inventario no tiene equipos registrados.")
            return
        }
        val extra = if (nuevas.size > OrdenTrabajo.MAX_LINEAS) {
            "\n\nHay ${nuevas.size} equipos y la OT tiene ${OrdenTrabajo.MAX_LINEAS} líneas: se copian los primeros ${OrdenTrabajo.MAX_LINEAS}."
        } else ""
        MaterialAlertDialogBuilder(this)
            .setTitle("Copiar desde Inventario")
            .setMessage("Se reemplazará el detalle actual por ${minOf(nuevas.size, OrdenTrabajo.MAX_LINEAS)} equipos del inventario.$extra")
            .setPositiveButton("Copiar") { _, _ ->
                b.contenedorLineas.removeAllViews()
                lineas.clear()
                nuevas.take(OrdenTrabajo.MAX_LINEAS).forEach { agregarLinea(it, enfocar = false) }
                cajas.firstOrNull()?.let { c ->
                    if (b.edtCliente.texto().isEmpty()) b.edtCliente.setText(c.cliente, false)
                    if (b.edtLocal.texto().isEmpty()) b.edtLocal.setText(c.tienda)
                    if (b.edtDireccion.texto().isEmpty()) b.edtDireccion.setText(c.direccion)
                }
                aviso("Equipos copiados. Revisa y completa lo que falte.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- Cámara ----------------

    private fun abrirCamara(linea: Int, esSerie: Boolean) {
        if (linea < 0) return
        objetivo = linea to esSerie
        val uri = FileProvider.getUriForFile(this, "$packageName.files", fotoTemporal)
        try {
            tomarFoto.launch(uri)
        } catch (_: Exception) {
            aviso("No se encontró una app de cámara en este teléfono.")
        }
    }

    private fun leerEtiqueta(linea: Int, esSerie: Boolean) {
        val ui = lineas.getOrNull(linea) ?: return
        val campo = if (esSerie) ui.serie else ui.modelo
        ui.lectura.visibility = View.VISIBLE
        ui.lectura.setTextColor(ContextCompat.getColor(this, R.color.gris))
        ui.lectura.text = "Leyendo la foto…"
        val uri = FileProvider.getUriForFile(this, "$packageName.files", fotoTemporal)
        LectorCamara.leer(this, uri, esSerie) { mejor, opciones ->
            when {
                mejor != null -> {
                    campo.setText(mejor)
                    ui.lectura.setTextColor(ContextCompat.getColor(this, R.color.verde))
                    ui.lectura.text = "✓ Leído. Revisa que esté correcto."
                }
                opciones.isNotEmpty() -> {
                    ui.lectura.text = "Elige el valor correcto."
                    MaterialAlertDialogBuilder(this)
                        .setTitle(if (esSerie) "¿Cuál es la serie?" else "¿Cuál es el modelo?")
                        .setItems(opciones.toTypedArray()) { _, i ->
                            campo.setText(opciones[i])
                            ui.lectura.setTextColor(ContextCompat.getColor(this, R.color.verde))
                            ui.lectura.text = "✓ Valor elegido."
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
                else -> {
                    ui.lectura.setTextColor(ContextCompat.getColor(this, R.color.rojo))
                    ui.lectura.text = "No se encontró texto ni código. Acerca la cámara o escríbelo a mano."
                }
            }
        }
    }

    // ---------------- Fecha ----------------

    private fun elegirFecha() {
        val cal = Calendar.getInstance()
        try { formatoFecha.parse(b.edtFecha.texto())?.let { cal.time = it } } catch (_: Exception) {}
        DatePickerDialog(
            this,
            { _, a, m, d -> b.edtFecha.setText("%02d-%02d-%04d".format(d, m + 1, a)) },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    // ---------------- Firma ----------------

    private fun carpetaOt() = File(filesDir, "ot").apply { mkdirs() }

    private fun guardarFirma() {
        val bmp = b.firma.comoImagen()
        b.txtFirmaAqui.visibility = if (b.firma.tieneFirma) View.GONE else View.VISIBLE
        if (bmp == null) return
        try {
            val nombre = "firma.png"
            File(carpetaOt(), nombre).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            archivoFirma = nombre
        } catch (_: Exception) {
        }
    }

    private fun borrarFirma() {
        File(carpetaOt(), "firma.png").delete()
        archivoFirma = ""
        b.firma.borrar()
        mostrarFirmaGuardada()
    }

    /** Si la OT ya tenía firma guardada, se muestra; «Borrar firma» permite firmar de nuevo. */
    private fun mostrarFirmaGuardada() {
        val f = if (archivoFirma.isNotEmpty()) File(carpetaOt(), archivoFirma) else null
        val bmp = f?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        if (bmp != null && !b.firma.tieneFirma) {
            b.imgFirma.setImageBitmap(bmp)
            b.imgFirma.visibility = View.VISIBLE
            b.txtFirmaAqui.visibility = View.GONE
        } else {
            b.imgFirma.visibility = View.GONE
            b.txtFirmaAqui.visibility = if (b.firma.tieneFirma) View.GONE else View.VISIBLE
        }
    }

    private fun firmaActual(): Bitmap? = b.firma.comoImagen()
        ?: archivoFirma.takeIf { it.isNotEmpty() }?.let { File(carpetaOt(), it) }?.takeIf { it.exists() }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }

    // ---------------- Nueva OT ----------------

    private fun confirmarNueva() {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Empezar una OT nueva?")
            .setMessage("Se borran los datos de la OT actual. Se mantienen la fecha de hoy y el nombre del técnico.")
            .setPositiveButton("Nueva OT") { _, _ ->
                File(carpetaOt(), "firma.png").delete()
                val tecnico = b.edtTecnico.texto()
                mostrar(OrdenTrabajo(fecha = formatoFecha.format(Date()), tecnico = tecnico))
                almacen.guardar(leer())
                b.scroll.smoothScrollTo(0, 0)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- Generar PDF ----------------

    private fun generar() {
        val ot = leer()
        val faltan = mutableListOf<String>()
        if (ot.numero.isEmpty()) faltan.add("Número de orden")
        if (ot.cliente.isEmpty()) faltan.add("Cliente")
        if (ot.tecnico.isEmpty()) faltan.add("Nombre técnico")
        if (ot.lineas.isEmpty()) faltan.add("Detalle de actividad")
        if (ot.recibeNombre.isEmpty()) faltan.add("Nombre quien recibe")
        if (firmaActual() == null) faltan.add("Firma del cliente")
        if (faltan.isNotEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Faltan datos")
                .setMessage("No has completado:\n\n• " + faltan.joinToString("\n• ") + "\n\n¿Generar la OT igual?")
                .setPositiveButton("Generar igual") { _, _ -> crearPdf(ot) }
                .setNegativeButton("Completar", null)
                .show()
        } else {
            crearPdf(ot)
        }
    }

    private fun nombrePdf(ot: OrdenTrabajo): String {
        val partes = listOf("OT", ot.numero, ot.local, ot.fecha).filter { it.isNotBlank() }
        return partes.joinToString("_").replace(Regex("[^A-Za-z0-9ÁÉÍÓÚáéíóúÑñ_-]+"), "-") + ".pdf"
    }

    private fun crearPdf(ot: OrdenTrabajo) {
        almacen.guardar(ot)
        val firma = firmaActual()
        val carpeta = File(cacheDir, "ot_pdf").apply { mkdirs() }
        carpeta.listFiles()?.forEach { it.delete() }
        val archivo = File(carpeta, nombrePdf(ot))
        Thread {
            val error = try { OtPdf.generar(this, ot, firma, archivo); null } catch (e: Exception) { e.message ?: "error" }
            runOnUiThread {
                if (error != null) {
                    aviso("No se pudo crear el PDF: $error")
                    return@runOnUiThread
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle("OT lista")
                    .setMessage("Se creó ${archivo.name}. ¿Qué quieres hacer?")
                    .setPositiveButton("Compartir") { _, _ -> compartirPdf(archivo) }
                    .setNeutralButton("Ver") { _, _ -> verPdf(archivo) }
                    .setNegativeButton("Guardar en Descargas") { _, _ -> guardarEnDescargas(archivo) }
                    .show()
            }
        }.start()
    }

    private fun uriDe(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", f)

    private fun verPdf(f: File) {
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriDe(f), "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(i)
        } catch (_: ActivityNotFoundException) {
            aviso("No hay una app para ver PDF. Usa «Compartir» o «Guardar en Descargas».")
        }
    }

    private fun compartirPdf(f: File) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uriDe(f))
            putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension.replace('_', ' '))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Enviar OT a…"))
    }

    private fun guardarEnDescargas(f: File) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val valores = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
                    ?: throw IllegalStateException("sin acceso a Descargas")
                contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                aviso("Guardada en Descargas: ${f.name}")
            } else {
                val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                f.copyTo(File(dir, f.name), overwrite = true)
                aviso("Guardada en ${dir.absolutePath}")
            }
        } catch (e: Exception) {
            aviso("No se pudo guardar: ${e.message}")
        }
    }

    private fun aviso(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    companion object {
        val CLIENTES = listOf(
            "Paris", "Easy", "Jumbo", "Santa Isabel", "Spid", "Lider", "Acuenta",
            "Mayorista", "Falabella", "Tottus", "Sodimac",
        )
    }
}

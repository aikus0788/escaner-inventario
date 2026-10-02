package cl.inventario.escaner

import android.app.DatePickerDialog
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cl.inventario.escaner.databinding.ActivityMainBinding
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var almacen: Almacen
    private var filas = mutableListOf<Equipo>()
    private var editandoId: Long? = null

    private var objetivo = CAMPO_MODELO
    private var fotoUri: Uri? = null
    private val candidatos = mutableMapOf<String, List<String>>()

    private val clientes = listOf(
        "Paris", "Easy", "Jumbo", "Santa Isabel", "Spid", "Lider", "Acuenta",
        "Mayorista", "Falabella", "Tottus", "Sodimac", OTROS,
    )
    private val tipos = listOf(
        "POS", "Pantalla", "Segunda pantalla", "Impresora", "Gaveta", "Teclado",
        "Scanner de mano", "Scanner de mesón",
    )
    private val regiones = arrayOf(
        "Arica y Parinacota", "Tarapacá", "Antofagasta", "Atacama", "Coquimbo", "Valparaíso",
        "Metropolitana de Santiago", "Lib. Gral. Bernardo O'Higgins", "Maule", "Ñuble", "Biobío",
        "La Araucanía", "Los Ríos", "Los Lagos", "Aysén", "Magallanes y la Antártica Chilena",
    )

    private val formatoFecha = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())

    private val tomarFoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = fotoUri
        if (ok && uri != null) procesarFoto(uri, objetivo)
    }

    private val abrirExcel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importar(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        savedInstanceState?.let {
            objetivo = it.getString("objetivo", CAMPO_MODELO)
            fotoUri = it.getString("fotoUri")?.let { s -> Uri.parse(s) }
        }

        almacen = Almacen(this)
        filas = almacen.cargar()

        // Cliente
        clientes.forEach { c -> b.chipsCliente.addView(nuevoChip(c)) }
        b.chipsCliente.setOnCheckedStateChangeListener { _, _ -> actualizarCliente() }

        // Tipo de equipo
        tipos.forEach { t -> b.chipsTipo.addView(nuevoChip(t)) }
        b.chipsTipo.setOnCheckedStateChangeListener { group, ids ->
            val elegido = ids.firstOrNull()?.let { group.findViewById<Chip>(it)?.text?.toString() }
            if (elegido != null) {
                if (b.edtTipo.text?.toString() != elegido) b.edtTipo.setText(elegido)
            } else if (b.edtTipo.text?.toString() in tipos) {
                b.edtTipo.setText("")
            }
        }
        b.edtTipo.doAfterTextChanged { t ->
            val elegido = chipElegido(b.chipsTipo)
            if (elegido != null && elegido != t?.toString()) b.chipsTipo.clearCheck()
        }

        // Región y fecha
        b.edtRegion.setSimpleItems(regiones)
        b.edtFecha.setText(formatoFecha.format(Date()))
        b.edtFecha.setOnClickListener { elegirFecha() }
        b.boxFecha.setEndIconOnClickListener { elegirFecha() }

        restaurarTienda()

        b.lista.layoutManager = LinearLayoutManager(this)
        b.lista.adapter = adaptador

        b.btnFotoModelo.setOnClickListener { abrirCamara(CAMPO_MODELO) }
        b.btnFotoSerie.setOnClickListener { abrirCamara(CAMPO_SERIE) }
        b.stModelo.setOnClickListener { mostrarOpciones(CAMPO_MODELO) }
        b.stSerie.setOnClickListener { mostrarOpciones(CAMPO_SERIE) }
        b.btnGuardar.setOnClickListener { guardar() }
        b.btnCancelar.setOnClickListener { terminarEdicion() }
        b.btnBorrar.setOnClickListener { confirmarBorrar() }
        b.btnExportar.setOnClickListener { menuExportar() }
        b.btnNuevo.setOnClickListener { nuevoExcel() }
        b.btnCargar.setOnClickListener { cargarExcel() }

        refrescar()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("objetivo", objetivo)
        fotoUri?.let { outState.putString("fotoUri", it.toString()) }
    }

    private fun nuevoChip(texto: String) = Chip(this).apply {
        id = View.generateViewId()
        text = texto
        isCheckable = true
        isCheckedIconVisible = false
    }

    private fun chipElegido(grupo: ChipGroup): String? =
        grupo.checkedChipId.takeIf { it != View.NO_ID }?.let { grupo.findViewById<Chip>(it)?.text?.toString() }

    private fun marcarChip(grupo: ChipGroup, texto: String): Boolean {
        for (i in 0 until grupo.childCount) {
            val c = grupo.getChildAt(i) as? Chip ?: continue
            if (c.text.toString().equals(texto, ignoreCase = true)) {
                c.isChecked = true
                return true
            }
        }
        return false
    }

    // ---------------- Tienda ----------------

    private fun actualizarCliente() {
        val elegido = chipElegido(b.chipsCliente)
        b.boxClienteOtro.visibility = if (elegido == OTROS) View.VISIBLE else View.GONE
        val hayCliente = elegido != null || !b.edtTienda.text.isNullOrEmpty()
        b.grupoTienda.visibility = if (hayCliente) View.VISIBLE else View.GONE
        if (elegido == OTROS && b.edtClienteOtro.text.isNullOrEmpty()) b.edtClienteOtro.requestFocus()
    }

    private fun clienteActual(): String {
        val elegido = chipElegido(b.chipsCliente) ?: return ""
        return if (elegido == OTROS) b.edtClienteOtro.text?.toString()?.trim().orEmpty() else elegido
    }

    private fun ponerCliente(nombre: String) {
        b.chipsCliente.clearCheck()
        b.edtClienteOtro.setText("")
        if (nombre.isNotBlank() && !marcarChip(b.chipsCliente, nombre)) {
            marcarChip(b.chipsCliente, OTROS)
            b.edtClienteOtro.setText(nombre)
        }
        actualizarCliente()
    }

    private fun ponerTienda(cliente: String, tienda: String, direccion: String, region: String) {
        b.edtTienda.setText(tienda)
        ponerCliente(cliente)
        b.edtDireccion.setText(direccion)
        b.edtRegion.setText(region, false)
    }

    private fun recordarTienda() {
        almacen.guardarTienda(
            mapOf(
                "cliente" to clienteActual(),
                "tienda" to b.edtTienda.text?.toString()?.trim().orEmpty(),
                "direccion" to b.edtDireccion.text?.toString()?.trim().orEmpty(),
                "region" to b.edtRegion.text?.toString()?.trim().orEmpty(),
                "ubicacion" to b.edtUbicacion.text?.toString()?.trim().orEmpty(),
            )
        )
    }

    private fun restaurarTienda() {
        ponerTienda(
            almacen.leerTienda("cliente"), almacen.leerTienda("tienda"),
            almacen.leerTienda("direccion"), almacen.leerTienda("region"),
        )
        b.edtUbicacion.setText(almacen.leerTienda("ubicacion"))
    }

    private fun elegirFecha() {
        val cal = Calendar.getInstance()
        try {
            formatoFecha.parse(b.edtFecha.text?.toString().orEmpty())?.let { cal.time = it }
        } catch (_: Exception) {
        }
        DatePickerDialog(
            this,
            { _, anio, mes, dia -> b.edtFecha.setText("%02d-%02d-%04d".format(dia, mes + 1, anio)) },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    // ---------------- Cámara y lectura ----------------

    private fun abrirCamara(campo: String) {
        objetivo = campo
        val archivo = File(cacheDir, "foto_$campo.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", archivo)
        fotoUri = uri
        try {
            tomarFoto.launch(uri)
        } catch (e: Exception) {
            aviso("No se encontró una app de cámara en este teléfono.")
        }
    }

    private fun estado(campo: String): TextView = if (campo == CAMPO_MODELO) b.stModelo else b.stSerie
    private fun campoTexto(campo: String) = if (campo == CAMPO_MODELO) b.edtModelo else b.edtSerie

    private fun procesarFoto(uri: Uri, campo: String) {
        val st = estado(campo)
        st.visibility = View.VISIBLE
        st.setTextColor(ContextCompat.getColor(this, R.color.gris))
        st.text = "Leyendo la foto…"

        val imagen = try {
            InputImage.fromFilePath(this, uri)
        } catch (e: Exception) {
            st.text = "No se pudo abrir la foto. Sácala de nuevo."
            return
        }

        BarcodeScanning.getClient().process(imagen)
            .addOnCompleteListener { tareaCodigos ->
                val codigos = if (tareaCodigos.isSuccessful) {
                    tareaCodigos.result.mapNotNull { it.rawValue?.trim() }.filter { it.isNotEmpty() }.distinct()
                } else emptyList()

                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(imagen)
                    .addOnCompleteListener { tareaTexto ->
                        val lineas = if (tareaTexto.isSuccessful) {
                            tareaTexto.result.textBlocks.flatMap { bl -> bl.lines.map { it.text.trim() } }
                                .filter { it.length >= 2 }
                        } else emptyList()
                        decidir(campo, codigos, lineas)
                    }
            }
    }

    private fun decidir(campo: String, codigos: List<String>, lineas: List<String>) {
        val st = estado(campo)
        val sugeridos = Lector.buscarConEtiqueta(lineas, campo == CAMPO_SERIE)
        val todos = LinkedHashSet<String>()
        todos.addAll(sugeridos)
        todos.addAll(codigos)
        todos.addAll(lineas.filter { Lector.pareceCodigo(it) })
        todos.addAll(lineas)
        val opciones = todos.toList().take(30)
        candidatos[campo] = opciones

        val mejor = sugeridos.firstOrNull() ?: codigos.singleOrNull()
        when {
            mejor != null -> {
                campoTexto(campo).setText(mejor)
                st.setTextColor(ContextCompat.getColor(this, R.color.verde))
                val origen = if (sugeridos.isNotEmpty()) "Texto leído" else "Código de barras leído"
                st.text = if (opciones.size > 1) "✓ $origen. ¿No es el correcto? Toca aquí para elegir otro." else "✓ $origen. Revisa que esté correcto."
            }
            opciones.isNotEmpty() -> {
                st.setTextColor(ContextCompat.getColor(this, R.color.gris))
                st.text = "Elige el valor correcto. Toca aquí para ver las opciones otra vez."
                mostrarOpciones(campo)
            }
            else -> {
                st.setTextColor(ContextCompat.getColor(this, R.color.rojo))
                st.text = "No se encontró texto ni código. Acerca la cámara, usa más luz o escríbelo a mano."
            }
        }
    }

    private fun mostrarOpciones(campo: String) {
        val opciones = candidatos[campo].orEmpty()
        if (opciones.isEmpty()) return
        val titulo = if (campo == CAMPO_MODELO) "¿Cuál es el modelo?" else "¿Cuál es el número de serie?"
        MaterialAlertDialogBuilder(this)
            .setTitle(titulo)
            .setItems(opciones.toTypedArray()) { _, i ->
                campoTexto(campo).setText(opciones[i])
                val st = estado(campo)
                st.setTextColor(ContextCompat.getColor(this, R.color.verde))
                st.text = "✓ Valor elegido. Toca aquí para cambiarlo."
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- Guardar / editar ----------------

    private fun leerFormulario(id: Long) = Equipo(
        id = id,
        fecha = b.edtFecha.text?.toString()?.trim().orEmpty(),
        cliente = clienteActual(),
        tienda = b.edtTienda.text?.toString()?.trim().orEmpty(),
        direccion = b.edtDireccion.text?.toString()?.trim().orEmpty(),
        region = b.edtRegion.text?.toString()?.trim().orEmpty(),
        tipo = b.edtTipo.text?.toString()?.trim().orEmpty(),
        modelo = b.edtModelo.text?.toString()?.trim().orEmpty(),
        serie = b.edtSerie.text?.toString()?.trim().orEmpty(),
        ubicacion = b.edtUbicacion.text?.toString()?.trim().orEmpty(),
        notas = b.edtNotas.text?.toString()?.trim().orEmpty(),
    )

    private fun guardar() {
        val id = editandoId
        val e = leerFormulario(id ?: System.currentTimeMillis())

        if (e.tipo.isEmpty() && e.modelo.isEmpty() && e.serie.isEmpty()) {
            aviso("Completa al menos el tipo de equipo, el modelo o la serie.")
            return
        }
        if (chipElegido(b.chipsCliente) == OTROS && e.cliente.isEmpty()) {
            aviso("Escribe el nombre del cliente o elige otro.")
            b.edtClienteOtro.requestFocus()
            return
        }

        if (id != null) {
            val i = filas.indexOfFirst { it.id == id }
            if (i >= 0) filas[i] = e
            almacen.guardar(filas)
            aviso("Cambios guardados")
            terminarEdicion()
            return
        }

        if (e.serie.isNotEmpty() && filas.any { it.serie.equals(e.serie, ignoreCase = true) }) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Serie repetida")
                .setMessage("Ya hay un equipo con la serie ${e.serie} en la planilla. ¿Guardarlo igual?")
                .setPositiveButton("Guardar igual") { _, _ -> agregar(e) }
                .setNegativeButton("No guardar", null)
                .show()
            return
        }
        agregar(e)
    }

    private fun agregar(e: Equipo) {
        filas.add(e)
        almacen.guardar(filas)
        recordarTienda()
        aviso("Equipo ${filas.size} guardado")
        limpiarEquipo()
    }

    /** Deja los datos de la tienda, la fecha y la ubicación POS para registrar el siguiente equipo. */
    private fun limpiarEquipo() {
        b.chipsTipo.clearCheck()
        b.edtTipo.setText("")
        b.edtModelo.setText("")
        b.edtSerie.setText("")
        b.edtNotas.setText("")
        b.stModelo.visibility = View.GONE
        b.stSerie.visibility = View.GONE
        candidatos.clear()
        editandoId = null
        b.btnGuardar.text = "GUARDAR FILA"
        b.barEdicion.visibility = View.GONE
        refrescar()
    }

    private fun terminarEdicion() {
        limpiarEquipo()
        restaurarTienda()
        b.edtFecha.setText(fechaDeTrabajo.ifEmpty { formatoFecha.format(Date()) })
    }

    private var fechaDeTrabajo = ""

    private fun editar(e: Equipo) {
        if (editandoId == null) fechaDeTrabajo = b.edtFecha.text?.toString().orEmpty()
        editandoId = e.id
        ponerTienda(e.cliente, e.tienda, e.direccion, e.region)
        b.edtFecha.setText(e.fecha)
        b.chipsTipo.clearCheck()
        b.edtTipo.setText(e.tipo)
        marcarChip(b.chipsTipo, e.tipo)
        b.edtModelo.setText(e.modelo)
        b.edtSerie.setText(e.serie)
        b.edtUbicacion.setText(e.ubicacion)
        b.edtNotas.setText(e.notas)
        b.stModelo.visibility = View.GONE
        b.stSerie.visibility = View.GONE
        b.btnGuardar.text = "GUARDAR CAMBIOS"
        b.barEdicion.visibility = View.VISIBLE
        refrescar()
        b.scroll.smoothScrollTo(0, 0)
    }

    private fun confirmarBorrar() {
        val id = editandoId ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Borrar esta fila?")
            .setMessage("No se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                filas.removeAll { it.id == id }
                almacen.guardar(filas)
                aviso("Fila borrada")
                terminarEdicion()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun refrescar() {
        b.txtCount.text = if (filas.size == 1) "1 fila" else "${filas.size} filas"
        b.txtVacio.visibility = if (filas.isEmpty()) View.VISIBLE else View.GONE
        adaptador.notifyDataSetChanged()
    }

    // ---------------- Nuevo / cargar Excel ----------------

    private fun nuevoExcel() {
        if (filas.isEmpty()) {
            empezarDeCero()
            aviso("Listo para un Excel nuevo")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Empezar un Excel nuevo?")
            .setMessage("La planilla actual tiene ${filas.size} equipos y se borrará de este teléfono. Si todavía no la exportas, toca «Exportar primero».")
            .setPositiveButton("Empezar nuevo") { _, _ ->
                empezarDeCero()
                aviso("Planilla nueva lista")
            }
            .setNeutralButton("Exportar primero") { _, _ -> compartir() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun empezarDeCero() {
        filas.clear()
        almacen.guardar(filas)
        ponerTienda("", "", "", "")
        b.edtUbicacion.setText("")
        recordarTienda()
        b.edtFecha.setText(formatoFecha.format(Date()))
        limpiarEquipo()
    }

    private fun cargarExcel() {
        try {
            abrirExcel.launch(arrayOf(MIME_XLSX, "application/octet-stream", "application/zip", "application/vnd.ms-excel"))
        } catch (e: Exception) {
            aviso("No se encontró un explorador de archivos en este teléfono.")
        }
    }

    private fun importar(uri: Uri) {
        aviso("Leyendo el Excel…")
        Thread {
            val resultado = try {
                val equipos = contentResolver.openInputStream(uri)?.use { XlsxReader.leerEquipos(it) }
                    ?: throw XlsxReader.FormatoNoValido("No se pudo abrir el archivo.")
                Result.success(equipos)
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                resultado.fold(
                    onSuccess = { equipos -> confirmarImportacion(equipos) },
                    onFailure = { e ->
                        val msg = if (e is XlsxReader.FormatoNoValido) e.message else "No se pudo leer el Excel. Revisa que sea un archivo .xlsx."
                        MaterialAlertDialogBuilder(this)
                            .setTitle("No se pudo cargar")
                            .setMessage(msg)
                            .setPositiveButton("Entendido", null)
                            .show()
                    },
                )
            }
        }.start()
    }

    private fun confirmarImportacion(equipos: List<Equipo>) {
        if (equipos.isEmpty()) {
            aviso("El Excel no tiene filas de equipos.")
            return
        }
        if (filas.isEmpty()) {
            aplicarImportacion(equipos, reemplazar = true)
            return
        }
        val opciones = arrayOf(
            "Reemplazar la planilla actual por el Excel (${equipos.size} equipos)",
            "Agregar los ${equipos.size} equipos al final de la planilla actual",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Ya tienes ${filas.size} equipos en la planilla")
            .setItems(opciones) { _, i -> aplicarImportacion(equipos, reemplazar = i == 0) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun aplicarImportacion(equipos: List<Equipo>, reemplazar: Boolean) {
        if (reemplazar) filas.clear()
        filas.addAll(equipos)
        almacen.guardar(filas)
        val ultimo = equipos.last()
        ponerTienda(ultimo.cliente, ultimo.tienda, ultimo.direccion, ultimo.region)
        b.edtUbicacion.setText(ultimo.ubicacion)
        recordarTienda()
        limpiarEquipo()
        aviso("Excel cargado: ${equipos.size} equipos. Puedes seguir agregando.")
    }

    // ---------------- Exportar ----------------

    private fun menuExportar() {
        if (filas.isEmpty()) {
            aviso("La planilla está vacía.")
            return
        }
        val opciones = mutableListOf("Compartir (Drive, WhatsApp, correo…)")
        if (Build.VERSION.SDK_INT >= 29) opciones.add("Guardar en Descargas")
        MaterialAlertDialogBuilder(this)
            .setTitle("Exportar Excel (${filas.size} equipos)")
            .setItems(opciones.toTypedArray()) { _, i ->
                if (opciones[i] == "Guardar en Descargas") guardarEnDescargas() else compartir()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun nombreArchivo(): String {
        val partes = mutableListOf("inventario")
        val ultimo = filas.lastOrNull()
        ultimo?.cliente?.takeIf { it.isNotBlank() }?.let { partes.add(it) }
        ultimo?.tienda?.takeIf { it.isNotBlank() }?.let { partes.add(it) }
        partes.add(SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(Date()))
        val base = partes.joinToString("_").replace(Regex("[^A-Za-z0-9ÁÉÍÓÚáéíóúÑñ_-]+"), "-")
        return "$base.xlsx"
    }

    private fun crearExcel(nombre: String): File {
        val carpeta = File(cacheDir, "excel").apply { mkdirs() }
        carpeta.listFiles()?.forEach { it.delete() }
        val archivo = File(carpeta, nombre)
        XlsxWriter.escribir(
            archivo,
            Equipo.ENCABEZADO,
            filas.mapIndexed { i, e -> e.comoFila(i + 1) },
            Equipo.ANCHOS,
        )
        return archivo
    }

    private fun compartir() {
        try {
            val archivo = crearExcel(nombreArchivo())
            val uri = FileProvider.getUriForFile(this, "$packageName.files", archivo)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = MIME_XLSX
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Inventario de equipos")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Enviar Excel a…"))
        } catch (e: Exception) {
            aviso("No se pudo crear el Excel: ${e.message}")
        }
    }

    private fun guardarEnDescargas() {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val nombre = nombreArchivo()
            val archivo = crearExcel(nombre)
            val valores = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME_XLSX)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
                ?: throw IllegalStateException("sin acceso a Descargas")
            contentResolver.openOutputStream(uri)?.use { out -> archivo.inputStream().use { it.copyTo(out) } }
            aviso("Guardado en Descargas: $nombre")
        } catch (e: Exception) {
            aviso("No se pudo guardar en Descargas: ${e.message}")
        }
    }

    private fun aviso(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------------- Lista ----------------

    private val adaptador = object : RecyclerView.Adapter<FilaVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilaVH =
            FilaVH(LayoutInflater.from(parent.context).inflate(R.layout.item_equipo, parent, false))

        override fun getItemCount() = filas.size

        override fun onBindViewHolder(h: FilaVH, position: Int) {
            val e = filas[position]
            h.num.text = (position + 1).toString()
            h.titulo.text = listOf(e.tipo.ifEmpty { "Sin tipo" }, e.ubicacion).filter { it.isNotEmpty() }.joinToString(" · ")
            h.codigos.text = "Mod: ${e.modelo.ifEmpty { "—" }}\nS/N: ${e.serie.ifEmpty { "—" }}"
            val tienda = listOf(e.cliente, e.tienda.takeIf { it.isNotEmpty() }?.let { "N° $it" } ?: "")
                .filter { it.isNotEmpty() }.joinToString(" ")
            h.detalle.text = listOf(tienda, e.fecha, e.notas).filter { it.isNotEmpty() }.joinToString(" · ")
            h.itemView.setBackgroundColor(
                ContextCompat.getColor(h.itemView.context, if (e.id == editandoId) R.color.linea else R.color.blanco)
            )
            h.itemView.setOnClickListener { editar(e) }
        }
    }

    class FilaVH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.txtNum)
        val titulo: TextView = v.findViewById(R.id.txtTitulo)
        val codigos: TextView = v.findViewById(R.id.txtCodigos)
        val detalle: TextView = v.findViewById(R.id.txtDetalle)
    }

    companion object {
        const val CAMPO_MODELO = "modelo"
        const val CAMPO_SERIE = "serie"
        const val OTROS = "Otros"
        const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}

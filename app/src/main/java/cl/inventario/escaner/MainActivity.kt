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
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cl.inventario.escaner.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
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
    private var filas = mutableListOf<Caja>()
    private var editandoId: Long? = null

    /** Componente y campo que se está fotografiando, p. ej. "Impresora|serie". */
    private var objetivo = ""
    private var fotoUri: Uri? = null
    private val candidatos = mutableMapOf<String, List<String>>()

    /** Campos en pantalla de cada componente de la caja. */
    private class PiezaUI(
        val nombre: String,
        val modelo: EditText,
        val serie: EditText,
        val stModelo: TextView,
        val stSerie: TextView,
    )
    private val piezasUI = linkedMapOf<String, PiezaUI>()
    private var ignorarCambioTipo = false

    private val clientes = listOf(
        "Paris", "Easy", "Jumbo", "Santa Isabel", "Spid", "Lider", "Acuenta",
        "Mayorista", "Falabella", "Tottus", "Sodimac", OTROS,
    )
    private val regiones = arrayOf(
        "Arica y Parinacota", "Tarapacá", "Antofagasta", "Atacama", "Coquimbo", "Valparaíso",
        "Metropolitana de Santiago", "Lib. Gral. Bernardo O'Higgins", "Maule", "Ñuble", "Biobío",
        "La Araucanía", "Los Ríos", "Los Lagos", "Aysén", "Magallanes y la Antártica Chilena",
    )

    private val formatoFecha = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
    private var fechaDeTrabajo = ""

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
            objetivo = it.getString("objetivo", "")
            fotoUri = it.getString("fotoUri")?.let { s -> Uri.parse(s) }
        }

        almacen = Almacen(this)
        filas = almacen.cargar()

        // Cliente
        clientes.forEach { c -> b.chipsCliente.addView(nuevoChip(c)) }
        b.chipsCliente.setOnCheckedStateChangeListener { _, _ -> actualizarCliente() }

        // Región y fecha
        b.edtRegion.setSimpleItems(regiones)
        b.edtFecha.setText(formatoFecha.format(Date()))
        b.edtFecha.setOnClickListener { elegirFecha() }
        b.boxFecha.setEndIconOnClickListener { elegirFecha() }

        // Tipo de caja
        b.grupoTipo.addOnButtonCheckedListener { _, _, _ ->
            if (!ignorarCambioTipo) construirPiezas(tipoActual(), leerPiezas())
        }

        restaurarTienda()
        val tipoGuardado = almacen.leerTienda("tipo")
        if (tipoGuardado.isNotEmpty()) ponerTipo(tipoGuardado, emptyMap()) else construirPiezas("", emptyMap())

        b.lista.layoutManager = LinearLayoutManager(this)
        b.lista.adapter = adaptador

        b.btnGuardar.setOnClickListener { guardar() }
        b.btnCancelar.setOnClickListener { terminarEdicion() }
        b.btnBorrar.setOnClickListener { confirmarBorrar() }
        b.btnExportar.setOnClickListener { menuExportar() }
        b.btnNuevo.setOnClickListener { nuevoExcel() }
        b.btnCargar.setOnClickListener { cargarExcel() }
        b.btnVolver.setOnClickListener { finish() }

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
                "tipo" to tipoActual(),
            )
        )
    }

    private fun restaurarTienda() {
        ponerTienda(
            almacen.leerTienda("cliente"), almacen.leerTienda("tienda"),
            almacen.leerTienda("direccion"), almacen.leerTienda("region"),
        )
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

    // ---------------- Tipo de caja y componentes ----------------

    private fun tipoActual(): String = when (b.grupoTipo.checkedButtonId) {
        R.id.btnPos -> Caja.POS
        R.id.btnSsco -> Caja.SSCO
        else -> ""
    }

    private fun ponerTipo(tipo: String, piezas: Map<String, Pieza>) {
        ignorarCambioTipo = true
        when (tipo) {
            Caja.POS -> b.grupoTipo.check(R.id.btnPos)
            Caja.SSCO -> b.grupoTipo.check(R.id.btnSsco)
            else -> b.grupoTipo.clearChecked()
        }
        ignorarCambioTipo = false
        construirPiezas(tipo, piezas)
    }

    private fun leerPiezas(): Map<String, Pieza> = piezasUI.mapValues { (_, p) ->
        Pieza(p.modelo.text?.toString()?.trim().orEmpty(), p.serie.text?.toString()?.trim().orEmpty())
    }

    /** Arma los campos de los componentes según el tipo de caja, conservando lo ya escrito. */
    private fun construirPiezas(tipo: String, valores: Map<String, Pieza>) {
        b.contenedorPiezas.removeAllViews()
        piezasUI.clear()
        candidatos.clear()
        val nombres = Caja.POR_TIPO[tipo].orEmpty()
        b.txtElegirTipo.visibility = if (nombres.isEmpty()) View.VISIBLE else View.GONE
        b.txtComponentes.visibility = if (nombres.isEmpty()) View.GONE else View.VISIBLE

        for (nombre in nombres) {
            val v = layoutInflater.inflate(R.layout.item_componente, b.contenedorPiezas, false)
            v.findViewById<TextView>(R.id.txtNombre).text = Caja.etiqueta(nombre, tipo)
            val ui = PiezaUI(
                nombre,
                v.findViewById(R.id.edtModelo),
                v.findViewById(R.id.edtSerie),
                v.findViewById(R.id.stModelo),
                v.findViewById(R.id.stSerie),
            )
            valores[nombre]?.let {
                ui.modelo.setText(it.modelo)
                ui.serie.setText(it.serie)
            }
            v.findViewById<MaterialButton>(R.id.btnFotoModelo).setOnClickListener { abrirCamara(clave(nombre, CAMPO_MODELO)) }
            v.findViewById<MaterialButton>(R.id.btnFotoSerie).setOnClickListener { abrirCamara(clave(nombre, CAMPO_SERIE)) }
            ui.stModelo.setOnClickListener { mostrarOpciones(clave(nombre, CAMPO_MODELO)) }
            ui.stSerie.setOnClickListener { mostrarOpciones(clave(nombre, CAMPO_SERIE)) }
            piezasUI[nombre] = ui
            b.contenedorPiezas.addView(v)
        }
    }

    private fun clave(nombre: String, campo: String) = "$nombre|$campo"

    private fun campoDe(k: String): Pair<EditText, TextView>? {
        val nombre = k.substringBefore('|')
        val campo = k.substringAfter('|')
        val ui = piezasUI[nombre] ?: return null
        return if (campo == CAMPO_MODELO) ui.modelo to ui.stModelo else ui.serie to ui.stSerie
    }

    // ---------------- Cámara y lectura ----------------

    private fun abrirCamara(k: String) {
        objetivo = k
        val archivo = File(cacheDir, "foto_actual.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", archivo)
        fotoUri = uri
        try {
            tomarFoto.launch(uri)
        } catch (e: Exception) {
            aviso("No se encontró una app de cámara en este teléfono.")
        }
    }

    private fun procesarFoto(uri: Uri, k: String) {
        val (_, st) = campoDe(k) ?: return
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
                        decidir(k, codigos, lineas)
                    }
            }
    }

    private fun decidir(k: String, codigos: List<String>, lineas: List<String>) {
        val (campo, st) = campoDe(k) ?: return
        val esSerie = k.endsWith("|$CAMPO_SERIE")
        val sugeridos = Lector.buscarConEtiqueta(lineas, esSerie)
        val todos = LinkedHashSet<String>()
        todos.addAll(sugeridos)
        todos.addAll(codigos)
        todos.addAll(lineas.filter { Lector.pareceCodigo(it) })
        todos.addAll(lineas)
        val opciones = todos.toList().take(30)
        candidatos[k] = opciones

        val mejor = sugeridos.firstOrNull() ?: codigos.singleOrNull()
        when {
            mejor != null -> {
                campo.setText(mejor)
                st.setTextColor(ContextCompat.getColor(this, R.color.verde))
                val origen = if (sugeridos.isNotEmpty()) "Texto leído" else "Código de barras leído"
                st.text = if (opciones.size > 1) "✓ $origen. ¿No es el correcto? Toca aquí para elegir otro." else "✓ $origen. Revisa que esté correcto."
            }
            opciones.isNotEmpty() -> {
                st.setTextColor(ContextCompat.getColor(this, R.color.gris))
                st.text = "Elige el valor correcto. Toca aquí para ver las opciones otra vez."
                mostrarOpciones(k)
            }
            else -> {
                st.setTextColor(ContextCompat.getColor(this, R.color.rojo))
                st.text = "No se encontró texto ni código. Acerca la cámara, usa más luz o escríbelo a mano."
            }
        }
    }

    private fun mostrarOpciones(k: String) {
        val opciones = candidatos[k].orEmpty()
        if (opciones.isEmpty()) return
        val nombre = Caja.etiqueta(k.substringBefore('|'), tipoActual())
        val titulo = if (k.endsWith("|$CAMPO_MODELO")) "$nombre: ¿cuál es el modelo?" else "$nombre: ¿cuál es el N° de serie?"
        MaterialAlertDialogBuilder(this)
            .setTitle(titulo)
            .setItems(opciones.toTypedArray()) { _, i ->
                val (campo, st) = campoDe(k) ?: return@setItems
                campo.setText(opciones[i])
                st.setTextColor(ContextCompat.getColor(this, R.color.verde))
                st.text = "✓ Valor elegido. Toca aquí para cambiarlo."
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- Guardar / editar ----------------

    private fun leerFormulario(id: Long) = Caja(
        id = id,
        fecha = b.edtFecha.text?.toString()?.trim().orEmpty(),
        cliente = clienteActual(),
        tienda = b.edtTienda.text?.toString()?.trim().orEmpty(),
        direccion = b.edtDireccion.text?.toString()?.trim().orEmpty(),
        region = b.edtRegion.text?.toString()?.trim().orEmpty(),
        tipo = tipoActual(),
        ubicacion = b.edtUbicacion.text?.toString()?.trim().orEmpty(),
        numeroPos = b.edtNumeroPos.text?.toString()?.trim().orEmpty(),
        piezas = leerPiezas().filterValues { !it.vacia() },
        notas = b.edtNotas.text?.toString()?.trim().orEmpty(),
    )

    private fun guardar() {
        val id = editandoId
        val c = leerFormulario(id ?: System.currentTimeMillis())

        if (c.tipo.isEmpty()) {
            aviso("Elige si la caja es POS o SSCO.")
            return
        }
        if (c.piezas.isEmpty()) {
            aviso("Completa el modelo o la serie de al menos un componente.")
            return
        }
        if (chipElegido(b.chipsCliente) == OTROS && c.cliente.isEmpty()) {
            aviso("Escribe el nombre del cliente o elige otro.")
            b.edtClienteOtro.requestFocus()
            return
        }

        val repetidas = seriesRepetidas(c)
        if (repetidas.isNotEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Serie repetida")
                .setMessage("Estas series ya están en la planilla:\n\n" + repetidas.joinToString("\n") + "\n\n¿Guardar igual?")
                .setPositiveButton("Guardar igual") { _, _ -> confirmarGuardado(c) }
                .setNegativeButton("Revisar", null)
                .show()
            return
        }
        confirmarGuardado(c)
    }

    private fun seriesRepetidas(c: Caja): List<String> {
        val resultado = mutableListOf<String>()
        val propias = c.piezas.filterValues { it.serie.isNotBlank() }
        val vistas = mutableMapOf<String, String>()
        for ((nombre, p) in propias) {
            val s = p.serie.uppercase()
            vistas[s]?.let { otro -> resultado.add("${p.serie} (en $otro y $nombre de esta caja)") }
            vistas[s] = nombre
            for (otra in filas) {
                if (otra.id == c.id) continue
                val hit = otra.piezas.entries.firstOrNull { it.value.serie.equals(p.serie, ignoreCase = true) }
                if (hit != null) {
                    resultado.add("${p.serie} ($nombre) · ya está en ${otra.tipo} ${otra.numeroPos} ${otra.ubicacion}".replace(Regex(" +"), " ").trim())
                    break
                }
            }
        }
        return resultado
    }

    private fun confirmarGuardado(c: Caja) {
        if (editandoId != null) {
            val i = filas.indexOfFirst { it.id == c.id }
            if (i >= 0) filas[i] = c
            almacen.guardar(filas)
            aviso("Cambios guardados")
            terminarEdicion()
        } else {
            filas.add(c)
            almacen.guardar(filas)
            recordarTienda()
            aviso("Caja ${filas.size} guardada")
            limpiarCaja()
            b.scroll.post { b.scroll.smoothScrollTo(0, b.txtSeccionCaja.top) }
        }
    }

    /** Deja la tienda, la fecha y el tipo de caja para registrar la siguiente caja. */
    private fun limpiarCaja() {
        b.edtUbicacion.setText("")
        b.edtNumeroPos.setText("")
        b.edtNotas.setText("")
        construirPiezas(tipoActual(), emptyMap())
        editandoId = null
        b.btnGuardar.text = "GUARDAR CAJA"
        b.barEdicion.visibility = View.GONE
        refrescar()
    }

    private fun terminarEdicion() {
        editandoId = null
        restaurarTienda()
        ponerTipo(almacen.leerTienda("tipo"), emptyMap())
        b.edtFecha.setText(fechaDeTrabajo.ifEmpty { formatoFecha.format(Date()) })
        limpiarCaja()
    }

    private fun editar(c: Caja) {
        if (editandoId == null) fechaDeTrabajo = b.edtFecha.text?.toString().orEmpty()
        editandoId = c.id
        ponerTienda(c.cliente, c.tienda, c.direccion, c.region)
        b.edtFecha.setText(c.fecha)
        ponerTipo(c.tipo, c.piezas)
        b.edtUbicacion.setText(c.ubicacion)
        b.edtNumeroPos.setText(c.numeroPos)
        b.edtNotas.setText(c.notas)
        b.btnGuardar.text = "GUARDAR CAMBIOS"
        b.barEdicion.visibility = View.VISIBLE
        refrescar()
        b.scroll.smoothScrollTo(0, 0)
    }

    private fun confirmarBorrar() {
        val id = editandoId ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Borrar esta caja?")
            .setMessage("Se borra la fila completa. No se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                filas.removeAll { it.id == id }
                almacen.guardar(filas)
                aviso("Caja borrada")
                terminarEdicion()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun refrescar() {
        b.txtCount.text = if (filas.size == 1) "1 caja" else "${filas.size} cajas"
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
            .setMessage("La planilla actual tiene ${filas.size} cajas y se borrará de este teléfono. Si todavía no la exportas, toca «Exportar primero».")
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
        editandoId = null
        ponerTienda("", "", "", "")
        ponerTipo("", emptyMap())
        recordarTienda()
        b.edtFecha.setText(formatoFecha.format(Date()))
        limpiarCaja()
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
                val cajas = contentResolver.openInputStream(uri)?.use { XlsxReader.leerCajas(it) }
                    ?: throw XlsxReader.FormatoNoValido("No se pudo abrir el archivo.")
                Result.success(cajas)
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                resultado.fold(
                    onSuccess = { cajas -> confirmarImportacion(cajas) },
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

    private fun confirmarImportacion(cajas: List<Caja>) {
        if (cajas.isEmpty()) {
            aviso("El Excel no tiene filas con datos.")
            return
        }
        if (filas.isEmpty()) {
            aplicarImportacion(cajas, reemplazar = true)
            return
        }
        val opciones = arrayOf(
            "Reemplazar la planilla actual por el Excel (${cajas.size} cajas)",
            "Agregar las ${cajas.size} cajas al final de la planilla actual",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Ya tienes ${filas.size} cajas en la planilla")
            .setItems(opciones) { _, i -> aplicarImportacion(cajas, reemplazar = i == 0) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun aplicarImportacion(cajas: List<Caja>, reemplazar: Boolean) {
        if (reemplazar) filas.clear()
        filas.addAll(cajas)
        almacen.guardar(filas)
        val ultima = cajas.last()
        editandoId = null
        ponerTienda(ultima.cliente, ultima.tienda, ultima.direccion, ultima.region)
        ponerTipo(ultima.tipo, emptyMap())
        recordarTienda()
        limpiarCaja()
        aviso("Excel cargado: ${cajas.size} cajas. Puedes seguir agregando.")
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
            .setTitle("Exportar Excel (${filas.size} cajas)")
            .setItems(opciones.toTypedArray()) { _, i ->
                if (opciones[i] == "Guardar en Descargas") guardarEnDescargas() else compartir()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun nombreArchivo(): String {
        val partes = mutableListOf("inventario")
        val ultima = filas.lastOrNull()
        ultima?.cliente?.takeIf { it.isNotBlank() }?.let { partes.add(it) }
        ultima?.tienda?.takeIf { it.isNotBlank() }?.let { partes.add(it) }
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
            Caja.ENCABEZADO,
            filas.mapIndexed { i, c -> c.comoFila(i + 1) },
            Caja.ANCHOS,
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
                putExtra(Intent.EXTRA_SUBJECT, "Inventario de cajas")
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
            val c = filas[position]
            h.num.text = (position + 1).toString()
            h.titulo.text = listOf(c.tipo + (c.numeroPos.takeIf { it.isNotEmpty() }?.let { " N° $it" } ?: ""), c.ubicacion).filter { it.isNotEmpty() }.joinToString(" · ")
            h.codigos.text = Caja.COMPONENTES.mapNotNull { n ->
                val p = c.piezas[n] ?: return@mapNotNull null
                if (p.vacia()) null else "${Caja.etiqueta(n, c.tipo)}: " + listOf(p.modelo, p.serie.takeIf { it.isNotEmpty() }?.let { "S/N $it" } ?: "")
                    .filter { it.isNotEmpty() }.joinToString(" · ")
            }.joinToString("\n").ifEmpty { "Sin componentes" }
            val tienda = listOf(c.cliente, c.tienda.takeIf { it.isNotEmpty() }?.let { "N° $it" } ?: "")
                .filter { it.isNotEmpty() }.joinToString(" ")
            h.detalle.text = listOf(tienda, c.fecha, c.notas).filter { it.isNotEmpty() }.joinToString(" · ")
            h.itemView.setBackgroundColor(
                ContextCompat.getColor(h.itemView.context, if (c.id == editandoId) R.color.linea else R.color.blanco)
            )
            h.itemView.setOnClickListener { editar(c) }
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

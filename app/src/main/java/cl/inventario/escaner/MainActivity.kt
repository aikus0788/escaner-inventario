package cl.inventario.escaner

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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cl.inventario.escaner.databinding.ActivityMainBinding
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
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

    private val tipos = listOf("Impresora", "Notebook", "PC", "Monitor", "Router", "Switch", "Teléfono IP", "Proyector", "Escáner")

    private val tomarFoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = fotoUri
        if (ok && uri != null) procesarFoto(uri, objetivo)
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

        tipos.forEach { t ->
            val chip = Chip(this).apply {
                text = t
                isCheckable = true
                setOnClickListener {
                    b.edtTipo.setText(t)
                    b.edtTipo.setSelection(t.length)
                }
            }
            b.chipsTipo.addView(chip)
        }

        b.lista.layoutManager = LinearLayoutManager(this)
        b.lista.adapter = adaptador

        b.btnFotoModelo.setOnClickListener { abrirCamara(CAMPO_MODELO) }
        b.btnFotoSerie.setOnClickListener { abrirCamara(CAMPO_SERIE) }
        b.stModelo.setOnClickListener { mostrarOpciones(CAMPO_MODELO) }
        b.stSerie.setOnClickListener { mostrarOpciones(CAMPO_SERIE) }
        b.btnGuardar.setOnClickListener { guardar() }
        b.btnCancelar.setOnClickListener { limpiar(mantenerTipo = false) }
        b.btnBorrar.setOnClickListener { confirmarBorrar() }
        b.btnExportar.setOnClickListener { menuExportar() }

        refrescar()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("objetivo", objetivo)
        fotoUri?.let { outState.putString("fotoUri", it.toString()) }
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

    private fun guardar() {
        val tipo = b.edtTipo.text?.toString()?.trim().orEmpty()
        val modelo = b.edtModelo.text?.toString()?.trim().orEmpty()
        val serie = b.edtSerie.text?.toString()?.trim().orEmpty()
        val ubicacion = b.edtUbicacion.text?.toString()?.trim().orEmpty()
        val notas = b.edtNotas.text?.toString()?.trim().orEmpty()

        if (tipo.isEmpty() && modelo.isEmpty() && serie.isEmpty()) {
            aviso("Completa al menos el tipo, el modelo o la serie.")
            return
        }

        val id = editandoId
        if (id != null) {
            val i = filas.indexOfFirst { it.id == id }
            if (i >= 0) {
                filas[i] = filas[i].copy(tipo = tipo, modelo = modelo, serie = serie, ubicacion = ubicacion, notas = notas)
            }
            almacen.guardar(filas)
            aviso("Cambios guardados")
            limpiar(mantenerTipo = false)
        } else {
            if (serie.isNotEmpty() && filas.any { it.serie.equals(serie, ignoreCase = true) }) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Serie repetida")
                    .setMessage("Ya hay un equipo con la serie $serie en la planilla. ¿Guardarlo igual?")
                    .setPositiveButton("Guardar igual") { _, _ -> agregar(tipo, modelo, serie, ubicacion, notas) }
                    .setNegativeButton("No guardar", null)
                    .show()
                return
            }
            agregar(tipo, modelo, serie, ubicacion, notas)
        }
    }

    private fun agregar(tipo: String, modelo: String, serie: String, ubicacion: String, notas: String) {
        val fecha = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date())
        filas.add(Equipo(System.currentTimeMillis(), fecha, tipo, modelo, serie, ubicacion, notas))
        almacen.guardar(filas)
        aviso("Equipo ${filas.size} guardado")
        limpiar(mantenerTipo = true)
    }

    private fun limpiar(mantenerTipo: Boolean) {
        if (!mantenerTipo) {
            b.edtTipo.setText("")
            b.edtUbicacion.setText("")
            b.chipsTipo.clearCheck()
        }
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

    private fun editar(e: Equipo) {
        editandoId = e.id
        b.edtTipo.setText(e.tipo)
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
                limpiar(mantenerTipo = false)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun refrescar() {
        b.txtCount.text = if (filas.size == 1) "1 fila" else "${filas.size} filas"
        b.txtVacio.visibility = if (filas.isEmpty()) View.VISIBLE else View.GONE
        adaptador.notifyDataSetChanged()
    }

    // ---------------- Exportar ----------------

    private fun menuExportar() {
        if (filas.isEmpty()) {
            aviso("La planilla está vacía.")
            return
        }
        val opciones = mutableListOf("Compartir (Drive, WhatsApp, correo…)")
        if (Build.VERSION.SDK_INT >= 29) opciones.add("Guardar en Descargas")
        opciones.add("Vaciar la planilla")
        MaterialAlertDialogBuilder(this)
            .setTitle("Exportar Excel (${filas.size} equipos)")
            .setItems(opciones.toTypedArray()) { _, i ->
                when (opciones[i]) {
                    "Guardar en Descargas" -> guardarEnDescargas()
                    "Vaciar la planilla" -> confirmarVaciar()
                    else -> compartir()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun nombreArchivo(): String =
        "inventario_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(Date()) + ".xlsx"

    private fun crearExcel(nombre: String): File {
        val carpeta = File(cacheDir, "excel").apply { mkdirs() }
        carpeta.listFiles()?.forEach { it.delete() }
        val archivo = File(carpeta, nombre)
        XlsxWriter.escribir(
            archivo,
            listOf("N°", "Fecha", "Tipo", "Modelo", "Serie", "Ubicación", "Notas"),
            filas.mapIndexed { i, e -> listOf((i + 1).toString(), e.fecha, e.tipo, e.modelo, e.serie, e.ubicacion, e.notas) },
            listOf(6, 18, 16, 26, 26, 24, 30),
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

    private fun confirmarVaciar() {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Vaciar la planilla?")
            .setMessage("Se borrarán los ${filas.size} equipos de este teléfono. Exporta el Excel antes si lo necesitas.")
            .setPositiveButton("Vaciar") { _, _ ->
                filas.clear()
                almacen.guardar(filas)
                limpiar(mantenerTipo = false)
                aviso("Planilla vacía")
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
            h.titulo.text = e.tipo.ifEmpty { "Sin tipo" }
            h.codigos.text = "Mod: ${e.modelo.ifEmpty { "—" }}\nS/N: ${e.serie.ifEmpty { "—" }}"
            h.detalle.text = listOf(e.ubicacion, e.notas, e.fecha).filter { it.isNotEmpty() }.joinToString(" · ")
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
        const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}

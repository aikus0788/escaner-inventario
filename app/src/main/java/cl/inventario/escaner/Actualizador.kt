package cl.inventario.escaner

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Busca versiones nuevas publicadas en GitHub, las descarga y abre el instalador de Android. */
class Actualizador(private val activity: Activity) {

    data class Version(val codigo: Long, val nombre: String, val url: String, val notas: String)

    private val api = "https://api.github.com/repos/aikus0788/escaner-inventario/releases/latest"

    fun codigoInstalado(): Long = try {
        PackageInfoCompat.getLongVersionCode(activity.packageManager.getPackageInfo(activity.packageName, 0))
    } catch (_: Exception) {
        0L
    }

    fun nombreInstalado(): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    /** Consulta GitHub en segundo plano. `listo` recibe la versión publicada, o null si no se pudo consultar. */
    fun buscar(listo: (Version?) -> Unit) {
        Thread {
            val v = try {
                val con = (URL(api).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                val json = con.inputStream.bufferedReader().use { it.readText() }
                con.disconnect()
                val o = JSONObject(json)
                val tag = o.optString("tag_name").removePrefix("v")
                val codigo = tag.substringAfterLast('.').toLongOrNull() ?: 0L
                val assets = o.optJSONArray("assets")
                var url = ""
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
                    }
                }
                if (url.isEmpty()) null else Version(codigo, tag, url, o.optString("body"))
            } catch (_: Exception) {
                null
            }
            activity.runOnUiThread { listo(v) }
        }.start()
    }

    /** Lo que hace el botón «Actualizar». */
    fun revisarYPreguntar() {
        val espera = MaterialAlertDialogBuilder(activity)
            .setTitle("Buscando actualización…")
            .setMessage("Versión instalada: ${nombreInstalado()}")
            .setCancelable(false)
            .show()
        buscar { v ->
            espera.dismiss()
            when {
                v == null -> MaterialAlertDialogBuilder(activity)
                    .setTitle("No se pudo revisar")
                    .setMessage("Revisa que el teléfono tenga internet e inténtalo de nuevo.")
                    .setPositiveButton("Entendido", null)
                    .show()
                v.codigo <= codigoInstalado() -> MaterialAlertDialogBuilder(activity)
                    .setTitle("Ya tienes la última versión")
                    .setMessage("Versión instalada: ${nombreInstalado()}")
                    .setPositiveButton("Entendido", null)
                    .show()
                else -> MaterialAlertDialogBuilder(activity)
                    .setTitle("Hay una versión nueva: ${v.nombre}")
                    .setMessage("Tienes la ${nombreInstalado()}. ¿Descargar e instalar la nueva versión?\n\nTus datos guardados se mantienen.")
                    .setPositiveButton("Actualizar") { _, _ -> descargar(v) }
                    .setNegativeButton("Ahora no", null)
                    .show()
            }
        }
    }

    private fun descargar(v: Version) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Falta un permiso")
                .setMessage("Para instalar la actualización, Android necesita que permitas a «Escáner Inventario» instalar apps.\n\nEn la pantalla que se abrirá, activa «Permitir de esta fuente», vuelve atrás y toca «Actualizar» otra vez.")
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }

        val barra = LinearProgressIndicator(activity).apply { isIndeterminate = true; max = 100 }
        val texto = TextView(activity).apply { text = "Conectando…"; gravity = Gravity.CENTER }
        val caja = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (24 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(barra)
            addView(texto)
        }
        var cancelado = false
        val dialogo = MaterialAlertDialogBuilder(activity)
            .setTitle("Descargando ${v.nombre}")
            .setView(caja)
            .setCancelable(false)
            .setNegativeButton("Cancelar") { _, _ -> cancelado = true }
            .show()

        Thread {
            val carpeta = File(activity.cacheDir, "actualizacion").apply { mkdirs() }
            carpeta.listFiles()?.forEach { it.delete() }
            val destino = File(carpeta, "EscanerInventario.apk")
            val error = try {
                val con = abrirSiguiendoRedirecciones(URL(v.url))
                val total = con.contentLengthLong
                con.inputStream.use { entrada ->
                    destino.outputStream().use { salida ->
                        val buf = ByteArray(64 * 1024)
                        var leido = 0L
                        var ultimoPct = -1
                        while (!cancelado) {
                            val n = entrada.read(buf)
                            if (n < 0) break
                            salida.write(buf, 0, n)
                            leido += n
                            if (total > 0) {
                                val pct = (leido * 100 / total).toInt()
                                if (pct != ultimoPct) {
                                    ultimoPct = pct
                                    val mb = "%.1f de %.1f MB".format(leido / 1048576.0, total / 1048576.0)
                                    activity.runOnUiThread {
                                        barra.isIndeterminate = false
                                        barra.setProgressCompat(pct, true)
                                        texto.text = "$pct %  ·  $mb"
                                    }
                                }
                            }
                        }
                    }
                }
                con.disconnect()
                if (cancelado) "cancelado" else null
            } catch (e: Exception) {
                e.message ?: "error desconocido"
            }

            activity.runOnUiThread {
                dialogo.dismiss()
                when (error) {
                    null -> instalar(destino)
                    "cancelado" -> destino.delete()
                    else -> MaterialAlertDialogBuilder(activity)
                        .setTitle("No se pudo descargar")
                        .setMessage("Revisa la conexión a internet e inténtalo de nuevo.\n\nDetalle: $error")
                        .setPositiveButton("Entendido", null)
                        .show()
                }
            }
        }.start()
    }

    /** GitHub redirige la descarga a otro servidor; se siguen hasta 5 redirecciones. */
    private fun abrirSiguiendoRedirecciones(inicio: URL): HttpURLConnection {
        var url = inicio
        repeat(6) {
            val con = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = false
            }
            val codigo = con.responseCode
            if (codigo in 300..399) {
                val siguiente = con.getHeaderField("Location") ?: throw IllegalStateException("Redirección sin destino")
                con.disconnect()
                url = URL(url, siguiente)
            } else if (codigo == 200) {
                return con
            } else {
                con.disconnect()
                throw IllegalStateException("El servidor respondió $codigo")
            }
        }
        throw IllegalStateException("Demasiadas redirecciones")
    }

    private fun instalar(apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("No se pudo abrir el instalador")
                .setMessage(e.message ?: "")
                .setPositiveButton("Entendido", null)
                .show()
        }
    }
}

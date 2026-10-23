package cl.inventario.escaner

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** Conversa con el script «Infomaxis · Servidor Drive» instalado en la cuenta de la empresa. */
class ServidorDrive(context: Context) {

    private val prefs = context.getSharedPreferences("drive", Context.MODE_PRIVATE)

    class ErrorServidor(val codigo: String, mensaje: String, val datos: JSONObject? = null) : Exception(mensaje)

    var url: String
        get() = prefs.getString("url", "") ?: ""
        set(v) = prefs.edit().putString("url", v.trim()).apply()

    var nombre: String
        get() = prefs.getString("nombre", "") ?: ""
        set(v) = prefs.edit().putString("nombre", v.trim()).apply()

    /** "", "nuevo", "pendiente", "aprobado" o "rechazado". */
    var estado: String
        get() = prefs.getString("estado", "") ?: ""
        set(v) = prefs.edit().putString("estado", v).apply()

    /** Identificador secreto de este teléfono; el administrador lo aprueba una vez. */
    val dispositivo: String
        get() {
            var id = prefs.getString("dispositivo", null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString("dispositivo", id).apply()
            }
            return id
        }

    val modeloTelefono: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** Llama al servidor (en segundo plano). Lanza [ErrorServidor] con un mensaje listo para mostrar. */
    fun llamar(params: Map<String, String>, tiempoLectura: Int = 60_000): JSONObject {
        if (url.isBlank()) throw ErrorServidor("SIN_URL", "Falta la dirección del servidor de Drive.")
        val todos = params + ("id" to dispositivo)
        val consulta = todos.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        val sep = if (url.contains("?")) "&" else "?"

        val texto = try {
            val con = (URL(url + sep + consulta).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = tiempoLectura
                instanceFollowRedirects = true
            }
            val codigo = con.responseCode
            val cuerpo = (if (codigo in 200..299) con.inputStream else con.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            con.disconnect()
            cuerpo
        } catch (e: java.net.UnknownHostException) {
            throw ErrorServidor("SIN_INTERNET", "No hay conexión a internet. Revisa el Wi-Fi o los datos móviles.")
        } catch (e: java.net.SocketTimeoutException) {
            throw ErrorServidor("LENTO", "El Drive tardó demasiado en responder. Inténtalo de nuevo.")
        } catch (e: java.io.IOException) {
            throw ErrorServidor("RED", "No se pudo conectar con el Drive: ${e.message}")
        }

        val o = try {
            JSONObject(texto)
        } catch (e: Exception) {
            throw ErrorServidor(
                "RESPUESTA",
                "El servidor de Drive no respondió como se esperaba. Revisa que la dirección termine en /exec " +
                    "y que el script esté implementado con acceso para «Cualquier usuario».",
            )
        }
        if (!o.optBoolean("ok", false)) {
            val c = o.optString("error")
            throw ErrorServidor(c, mensaje(c), o)
        }
        return o
    }

    /** Busca la dirección del servidor publicada en GitHub, para no tener que escribirla en cada teléfono. */
    fun buscarDireccionPublicada(): String? = try {
        val con = (URL(DIRECCION_PUBLICADA).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        val t = if (con.responseCode == 200) con.inputStream.bufferedReader().use { it.readText() } else ""
        con.disconnect()
        t.lines().map { it.trim() }.firstOrNull { it.startsWith("https://script.google.com/") }
    } catch (_: Exception) {
        null
    }

    private fun mensaje(codigo: String): String = when (codigo) {
        "NO_AUTORIZADO" -> "Este teléfono no tiene acceso al Drive. Pide acceso al administrador."
        "BUSQUEDA_CORTA" -> "Escribe al menos 2 letras para buscar."
        "MUY_GRANDE" -> "El archivo es muy grande para abrirlo desde la app."
        "NO_CONVERTIBLE" -> "Este tipo de archivo de Google no se puede abrir en el teléfono."
        "FALTA_NOMBRE" -> "Escribe tu nombre para pedir acceso."
        else -> "El Drive respondió con un error: $codigo"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val DIRECCION_PUBLICADA =
            "https://raw.githubusercontent.com/aikus0788/escaner-inventario/main/config/servidor-drive.txt"
    }
}

package cl.inventario.escaner

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Llamadas a Google Drive (API v3) con el token de la cuenta que inició sesión. Todo es bloqueante: usar en segundo plano. */
object DriveApi {

    class ErrorDrive(val codigo: String, mensaje: String) : Exception(mensaje)

    data class Item(
        val id: String,
        val nombre: String,
        val tipo: String,
        val tamano: Long,
        val modificado: String,
        val url: String,
        val padre: String,
        var carpeta: String = "",
    ) {
        val esCarpeta get() = tipo == CARPETA
        val esDeGoogle get() = tipo.startsWith("application/vnd.google-apps.")
    }

    const val CARPETA = "application/vnd.google-apps.folder"
    private const val ACCESO_DIRECTO = "application/vnd.google-apps.shortcut"
    private const val BASE = "https://www.googleapis.com/drive/v3"
    private const val CAMPOS = "id,name,mimeType,size,modifiedTime,webViewLink,parents,shortcutDetails(targetId,targetMimeType)"

    fun correo(token: String): String {
        val o = JSONObject(leer(abrir("$BASE/about?fields=user(emailAddress)", token)))
        return o.optJSONObject("user")?.optString("emailAddress").orEmpty()
    }

    /** Busca en el nombre y el contenido. Devuelve carpetas primero y si hay más resultados. */
    fun buscar(token: String, texto: String): Pair<List<Item>, Boolean> {
        val s = texto.replace("\\", "\\\\").replace("'", "\\'")
        val q = "(name contains '$s' or fullText contains '$s') and trashed = false"
        val o = listar(token, q, null, 100)
        val items = o.first
        completarCarpetas(token, items)
        val ql = texto.lowercase()
        val ordenados = items.sortedWith(
            compareBy<Item>({ !it.esCarpeta }, { !it.nombre.lowercase().contains(ql) })
                .thenByDescending { it.modificado },
        )
        return ordenados to o.second
    }

    fun carpeta(token: String, id: String): List<Item> {
        val todos = mutableListOf<Item>()
        var pagina: String? = null
        do {
            val r = listar(token, "'$id' in parents and trashed = false", "folder,name", 200, pagina)
            todos.addAll(r.first)
            pagina = r.third
        } while (pagina != null && todos.size < 1000)
        return todos
    }

    fun nombreCarpeta(token: String, id: String): String = try {
        JSONObject(leer(abrir("$BASE/files/${enc(id)}?fields=name&supportsAllDrives=true", token))).optString("name")
    } catch (_: Exception) {
        ""
    }

    private fun listar(token: String, q: String, orden: String?, cantidad: Int, pagina: String? = null): Triple<List<Item>, Boolean, String?> {
        val url = StringBuilder("$BASE/files?q=${enc(q)}&pageSize=$cantidad")
            .append("&fields=").append(enc("nextPageToken,files($CAMPOS)"))
            .append("&includeItemsFromAllDrives=true&supportsAllDrives=true")
        if (orden != null) url.append("&orderBy=").append(enc(orden))
        if (pagina != null) url.append("&pageToken=").append(enc(pagina))
        val o = JSONObject(leer(abrir(url.toString(), token)))
        val arr = o.optJSONArray("files")
        val items = (0 until (arr?.length() ?: 0)).map { i -> desdeJson(arr!!.getJSONObject(i)) }
        val siguiente = o.optString("nextPageToken").ifEmpty { null }
        return Triple(items, siguiente != null, siguiente)
    }

    private fun desdeJson(f: JSONObject): Item {
        var id = f.optString("id")
        var tipo = f.optString("mimeType")
        if (tipo == ACCESO_DIRECTO) {
            f.optJSONObject("shortcutDetails")?.let {
                id = it.optString("targetId", id)
                tipo = it.optString("targetMimeType", tipo)
            }
        }
        return Item(
            id = id,
            nombre = f.optString("name"),
            tipo = tipo,
            tamano = f.optString("size").toLongOrNull() ?: 0L,
            modificado = f.optString("modifiedTime"),
            url = f.optString("webViewLink"),
            padre = f.optJSONArray("parents")?.optString(0).orEmpty(),
        )
    }

    /** Agrega el nombre de la carpeta donde está cada resultado (hasta 15 carpetas distintas). */
    private fun completarCarpetas(token: String, items: List<Item>) {
        val ids = items.map { it.padre }.filter { it.isNotEmpty() }.distinct().take(15)
        val nombres = ids.associateWith { nombreCarpeta(token, it) }
        items.forEach { it.carpeta = nombres[it.padre].orEmpty() }
    }

    /**
     * Descarga el archivo a [carpeta]. Los Documentos, Hojas y Presentaciones de Google se convierten a PDF.
     * Devuelve el archivo creado y su tipo.
     */
    fun descargar(token: String, item: Item, carpeta: File, progreso: (Int) -> Unit): Pair<File, String> {
        val (url, nombre, tipo) = if (item.esDeGoogle) {
            val n = if (item.nombre.endsWith(".pdf", true)) item.nombre else item.nombre + ".pdf"
            Triple("$BASE/files/${enc(item.id)}/export?mimeType=${enc("application/pdf")}", n, "application/pdf")
        } else {
            Triple("$BASE/files/${enc(item.id)}?alt=media&supportsAllDrives=true", item.nombre, item.tipo)
        }
        val destino = File(carpeta, nombreSeguro(nombre))
        val con = abrir(url, token, tiempo = 120_000)
        val total = if (item.esDeGoogle) -1L else item.tamano
        con.inputStream.use { entrada ->
            destino.outputStream().use { salida ->
                val buf = ByteArray(64 * 1024)
                var leido = 0L
                var ultimo = -1
                while (true) {
                    val n = entrada.read(buf)
                    if (n < 0) break
                    salida.write(buf, 0, n)
                    leido += n
                    if (total > 0) {
                        val pct = (leido * 100 / total).toInt().coerceAtMost(100)
                        if (pct != ultimo) { ultimo = pct; progreso(pct) }
                    }
                }
            }
        }
        con.disconnect()
        return destino to tipo
    }

    fun revocar(token: String) {
        try {
            val con = (URL("https://oauth2.googleapis.com/revoke?token=${enc(token)}").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            con.outputStream.use { }
            con.responseCode
            con.disconnect()
        } catch (_: Exception) {
        }
    }

    fun nombreSeguro(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "archivo" }

    // ---------------- HTTP ----------------

    private fun abrir(url: String, token: String, tiempo: Int = 60_000): HttpURLConnection {
        val con = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = tiempo
                setRequestProperty("Authorization", "Bearer $token")
            }
        } catch (e: Exception) {
            throw ErrorDrive("RED", "No se pudo conectar con Google Drive.")
        }
        val codigo = try {
            con.responseCode
        } catch (e: java.net.UnknownHostException) {
            throw ErrorDrive("SIN_INTERNET", "No hay conexión a internet. Revisa el Wi-Fi o los datos móviles.")
        } catch (e: java.net.SocketTimeoutException) {
            throw ErrorDrive("LENTO", "Google Drive tardó demasiado en responder. Inténtalo de nuevo.")
        } catch (e: java.io.IOException) {
            throw ErrorDrive("RED", "No se pudo conectar con Google Drive: ${e.message}")
        }
        if (codigo in 200..299) return con
        val cuerpo = try { con.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty() } catch (_: Exception) { "" }
        con.disconnect()
        val motivo = try {
            JSONObject(cuerpo).optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        } catch (_: Exception) { "" }
        throw when {
            codigo == 401 -> ErrorDrive("TOKEN", "La sesión de Google venció.")
            motivo == "exportSizeLimitExceeded" ->
                ErrorDrive("MUY_GRANDE", "Este documento de Google es muy grande para convertirlo a PDF.")
            motivo == "fileNotDownloadable" || motivo == "cannotDownloadFile" ->
                ErrorDrive("NO_DESCARGABLE", "Este archivo no se puede descargar (por ejemplo, un formulario de Google).")
            motivo == "accessNotConfigured" ->
                ErrorDrive("SIN_API", "Falta activar la «Google Drive API» en el proyecto de Google Cloud.")
            codigo == 403 -> ErrorDrive("PROHIBIDO", "Google no permitió esta acción ($motivo).")
            codigo == 404 -> ErrorDrive("NO_EXISTE", "El archivo ya no existe o no tienes acceso.")
            else -> ErrorDrive("HTTP_$codigo", "Google Drive respondió con un error ($codigo).")
        }
    }

    private fun leer(con: HttpURLConnection): String {
        val t = con.inputStream.bufferedReader().use { it.readText() }
        con.disconnect()
        return t
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

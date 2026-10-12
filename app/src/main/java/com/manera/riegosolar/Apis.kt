package com.manera.riegosolar

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

class HttpError(val codigo: Int, mensaje: String) : IOException(mensaje)

object Http {
    fun pedir(
        metodo: String,
        url: String,
        encabezados: Map<String, String> = emptyMap(),
        cuerpo: String? = null,
    ): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("Accept", "application/json")
            for ((k, v) in encabezados) c.setRequestProperty(k, v)
            if (cuerpo != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            }
            val codigo = c.responseCode
            val stream = if (codigo in 200..299) c.inputStream else c.errorStream
            val texto = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (codigo !in 200..299) throw HttpError(codigo, "HTTP $codigo ${texto.take(200)}")
            return texto
        } finally {
            c.disconnect()
        }
    }

    fun q(v: String): String = URLEncoder.encode(v, "UTF-8")
}

// ------------------------------------------------------------------ Omixom ---

data class Modulo(val id: Int, val titulo: String, val tipo: String)

data class LecturaClima(val radiacion: Double, val horaDato: Instant, val lluviaMm: Double)

/** Cliente mínimo de la Omixom Data API v3 (https://clima.omixom.com/api/v3). */
class Omixom(private val token: String, private val estacion: Int) {
    private val base = "https://clima.omixom.com/api/v3"

    private fun get(ruta: String): JSONObject =
        JSONObject(Http.pedir("GET", base + ruta, mapOf("Authorization" to "Bearer $token")))

    fun modulos(): List<Modulo> {
        val arr = get("/stations/$estacion").getJSONArray("modules")
        return (0 until arr.length()).map {
            val m = arr.getJSONObject(it)
            Modulo(m.getInt("id"), m.optString("title"), m.optString("type"))
        }
    }

    fun buscar(mods: List<Modulo>, palabras: List<String>): Int? =
        mods.firstOrNull { m ->
            val t = "${m.titulo} ${m.tipo}".lowercase()
            palabras.any { t.contains(it) }
        }?.id

    fun leer(modRadConfig: Int?, modLluviaConfig: Int?, horasLluvia: Long = 24): LecturaClima {
        var modRad = modRadConfig
        var modLluvia = modLluviaConfig
        if (modRad == null || modLluvia == null) {
            val mods = modulos()
            if (modRad == null) modRad = buscar(mods, PALABRAS_RADIACION)
            if (modLluvia == null) modLluvia = buscar(mods, PALABRAS_LLUVIA)
        }
        val rad = modRad ?: throw IOException("No encontré el sensor de radiación en la estación")
        val llu = modLluvia

        val ahora = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val hasta = ahora.plus(1, ChronoUnit.MINUTES).toString()
        var desde = ahora.minus(horasLluvia, ChronoUnit.HOURS).toString()
        var cursor: String? = null
        val modulosQ = buildString {
            append("&modules=").append(rad)
            if (llu != null) append("&modules=").append(llu)
        }

        var ultimaHora: Instant? = null
        var ultimoValor = 0.0
        var lluvia = 0.0
        var paginas = 0
        while (paginas < 30) {
            paginas++
            var ruta = "/stations/$estacion/measurements/series" +
                "?date_from=${Http.q(desde)}&date_to=${Http.q(hasta)}$modulosQ&category=ok"
            if (cursor != null) ruta += "&cursor=${Http.q(cursor)}"
            val pagina = get(ruta)
            val modulos = pagina.optJSONObject("modules") ?: JSONObject()
            val claves = modulos.keys()
            while (claves.hasNext()) {
                val clave = claves.next()
                val id = clave.toIntOrNull() ?: continue
                val serie = modulos.getJSONObject(clave)
                val tiempos: JSONArray = serie.getJSONArray("times")
                val valores: JSONArray = serie.getJSONArray("values")
                for (i in 0 until tiempos.length()) {
                    if (valores.isNull(i)) continue
                    val t = parsearHora(tiempos.getString(i))
                    val v = valores.getDouble(i)
                    if (id == rad) {
                        val previa = ultimaHora
                        if (previa == null || t.isAfter(previa)) {
                            ultimaHora = t
                            ultimoValor = v
                        }
                    } else if (id == llu) {
                        lluvia += v
                    }
                }
            }
            if (!pagina.optBoolean("has_more", false) || pagina.isNull("next_from")) break
            cursor = pagina.optString("cursor").ifEmpty { null }
            desde = pagina.getString("next_from")
        }
        val hora = ultimaHora ?: throw IOException("La estación no informó radiación en las últimas horas")
        return LecturaClima(ultimoValor, hora, lluvia)
    }

    private fun parsearHora(texto: String): Instant = try {
        OffsetDateTime.parse(texto).toInstant()
    } catch (e: Exception) {
        LocalDateTime.parse(texto).toInstant(ZoneOffset.UTC) // sin zona: se asume UTC
    }

    companion object {
        val PALABRAS_RADIACION = listOf("radiac", "piran", "irradia")
        val PALABRAS_LLUVIA = listOf("lluv", "precip", "pluvi", "rain")
    }
}

// --------------------------------------------------------------------- GSI ---

/**
 * Cliente mínimo de Galcon GSI. Usa las mismas llamadas que la web
 * gsi.galcon-smart.com (no es una API oficial).
 * El proyecto, la unidad y el número de serie se detectan solos si no se cargan a mano,
 * y quedan guardados en [memoria] junto con el token de sesión.
 */
class Gsi(private val cfg: Config, private val memoria: SharedPreferences) {
    private val base = "https://gsi.galcon-smart.com/api"
    private var token: String? = memoria.getString("tokenGsi", null)

    private fun login(): String {
        val cuerpo = JSONObject().put("username", cfg.gsiUser).put("password", cfg.gsiPass).toString()
        val r = JSONObject(Http.pedir("POST", "$base/Auth/Login", cuerpo = cuerpo))
        val tk = r.optJSONObject("Body")?.optString("AccountToken").orEmpty()
        if (!r.optBoolean("Result", false) || tk.isEmpty()) {
            throw IOException("GSI rechazó el usuario o la clave")
        }
        token = tk
        memoria.edit().putString("tokenGsi", tk).apply()
        return tk
    }

    private fun llamar(metodo: String, ruta: String, cuerpo: JSONObject? = null): JSONObject {
        val tk = token ?: login()
        val texto = try {
            Http.pedir(metodo, base + ruta, mapOf("Authorization" to "Bearer $tk"), cuerpo?.toString())
        } catch (e: HttpError) {
            if (e.codigo == 401 || e.codigo == 403) {
                val nuevo = login()
                Http.pedir(metodo, base + ruta, mapOf("Authorization" to "Bearer $nuevo"), cuerpo?.toString())
            } else {
                throw e
            }
        }
        val j = if (texto.isBlank()) JSONObject() else JSONObject(texto)
        if (j.has("Result") && !j.optBoolean("Result", true)) {
            throw IOException("GSI respondió con error: ${j.opt("Messages") ?: texto.take(200)}")
        }
        return j
    }

    private fun primero(ruta: String, campo: String): Int {
        val lista = llamar("GET", ruta).optJSONArray("Body")
        if (lista == null || lista.length() == 0) throw IOException("No encontré equipos en tu cuenta de GSI")
        return lista.getJSONObject(0).getInt(campo)
    }

    val proyecto: Int by lazy {
        cfg.proyecto ?: memoria.getInt("gsiProyecto", 0).takeIf { it != 0 }
            ?: primero("/api/project/UserProjects", "ProjectID").also { memoria.edit().putInt("gsiProyecto", it).apply() }
    }

    val unidad: Int by lazy {
        cfg.unidad ?: memoria.getInt("gsiUnidad", 0).takeIf { it != 0 }
            ?: primero("/api/project/$proyecto/UnitList", "ControlUnitID").also { memoria.edit().putInt("gsiUnidad", it).apply() }
    }

    val sn: String by lazy {
        cfg.sn ?: memoria.getString("gsiSn", null)?.takeIf { it.isNotBlank() }
            ?: llamar("GET", "/api/Unit/$unidad/InfoSetting").getJSONObject("Body")
                .getJSONObject("DeviceInfo").getString("SN").also { memoria.edit().putString("gsiSn", it).apply() }
    }

    fun programas(): JSONArray =
        llamar("GET", "/api/Unit/$unidad/ProgramsListPro?ProjectID=$proyecto").getJSONArray("Body")

    /** true si el cabezal de riego (línea 1) está en pausa. */
    fun cabezalPausado(): Boolean {
        val body = llamar("GET", "/api/unit/$unidad?ProjectID=$proyecto").getJSONObject("Body")
        val pausa = body.optJSONObject("OnlinePro")?.optInt("TempPauseLine1", 0) ?: 0
        return (pausa and 1) != 0
    }

    /** Pausa el cabezal de riego completo (igual que el botón "Pausa el cabezal de riego" de GSI). */
    fun pausarCabezal() {
        llamar("POST", "/API/Online/PauseCurrentPipe", JSONObject().put("SN", sn).put("PipeNum", 1))
    }

    /** Libera el cabezal de riego (botón "Libera el cabezal de riego"). */
    fun liberarCabezal() {
        llamar("POST", "/api/Online/ReleasePipe?ProjectID=$proyecto", JSONObject().put("SN", sn).put("PipeNum", 1))
    }
}

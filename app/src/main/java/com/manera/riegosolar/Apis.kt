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

/**
 * Cliente mínimo de la API REST v2 de Omixom (https://new.omixom.com/api/v2),
 * autenticada con "Authorization: Token <token>".
 *
 * Límites de Omixom, por eso se guarda todo lo posible en [memoria]:
 *  - /stations: 4 por hora y 24 por día  -> los sensores se buscan una sola vez.
 *  - /private_last_measure: 150 por día   -> una por revisión (cada 15 min, solo de día).
 *  - /private_samples_range de 24 h: 6 por día -> la lluvia de 24 h se pide cada 4 h como mucho.
 */
class Omixom(
    private val token: String,
    private val estacion: Int,
    private val memoria: SharedPreferences,
) {
    private val base = "https://new.omixom.com/api/v2"
    private val auth = mapOf("Authorization" to "Token $token")

    private fun post(ruta: String, cuerpo: JSONObject): JSONArray =
        JSONArray(Http.pedir("POST", base + ruta, auth, cuerpo.toString()))

    private fun pedidoEstacion(modulos: List<Int>, extra: JSONObject = JSONObject()): JSONObject {
        val est = extra.put("modules", JSONArray(modulos))
        return JSONObject().put("stations", JSONObject().put(estacion.toString(), est))
    }

    fun modulos(): List<Modulo> {
        val estaciones = JSONArray(Http.pedir("GET", "$base/stations", auth))
        for (i in 0 until estaciones.length()) {
            val e = estaciones.getJSONObject(i)
            if (e.optInt("code") != estacion) continue
            val arr = e.optJSONArray("modules") ?: JSONArray()
            return (0 until arr.length()).map {
                val m = arr.getJSONObject(it)
                Modulo(m.getInt("id"), m.optString("title"), m.optString("type"))
            }
        }
        throw IOException("La estación $estacion no aparece en tu cuenta de Omixom")
    }

    fun buscar(mods: List<Modulo>, palabras: List<String>): Int? =
        mods.firstOrNull { m ->
            val t = "${m.titulo} ${m.tipo}".lowercase()
            palabras.any { t.contains(it) }
        }?.id

    /** Sensores de radiación y lluvia: los de ajustes, o los detectados (y guardados) una sola vez. */
    fun sensores(modRadConfig: Int?, modLluviaConfig: Int?): Pair<Int, Int?> {
        val clave = "omx_$estacion"
        var rad = modRadConfig ?: memoria.getInt("${clave}_rad", 0).takeIf { it != 0 }
        var llu = modLluviaConfig ?: memoria.getInt("${clave}_llu", 0).takeIf { it != 0 }
        if (rad == null || (llu == null && !memoria.getBoolean("${clave}_sinLluvia", false))) {
            val mods = modulos()
            if (rad == null) rad = buscar(mods, PALABRAS_RADIACION)
            if (llu == null) llu = buscar(mods, PALABRAS_LLUVIA)
            memoria.edit()
                .putInt("${clave}_rad", rad ?: 0)
                .putInt("${clave}_llu", llu ?: 0)
                .putBoolean("${clave}_sinLluvia", llu == null)
                .apply()
        }
        val r = rad ?: throw IOException("No encontré el sensor de radiación en la estación")
        return r to llu
    }

    fun leer(modRadConfig: Int?, modLluviaConfig: Int?): LecturaClima {
        val (rad, llu) = sensores(modRadConfig, modLluviaConfig)

        // Última muestra (radiación y, si hay, lluvia de los últimos 10 min).
        val ultima = post("/private_last_measure", pedidoEstacion(listOfNotNull(rad, llu)))
        if (ultima.length() == 0) throw IOException("Omixom no devolvió datos de la estación")
        val m = ultima.getJSONObject(0)
        if (!m.has(rad.toString()) || m.isNull(rad.toString())) {
            throw IOException("La estación no informó radiación en la última muestra")
        }
        val radiacion = m.getDouble(rad.toString())
        val hora = parsearHora(m.getString("date"))
        val lluviaAhora = if (llu != null) m.optDouble(llu.toString(), 0.0).takeIf { !it.isNaN() } ?: 0.0 else 0.0

        return LecturaClima(radiacion, hora, lluvia24h(llu) + lluviaAhora)
    }

    /** Lluvia de las últimas 24 h, pedida cada 4 h como mucho (Omixom permite 6 por día). */
    private fun lluvia24h(llu: Int?): Double {
        if (llu == null) return 0.0
        val ahora = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clave = "omx_${estacion}_lluvia24"
        val cuando = memoria.getLong("${clave}_t", 0L)
        if (ahora.toEpochMilli() - cuando < 4 * 3_600_000L) {
            return memoria.getFloat(clave, 0f).toDouble()
        }
        val extra = JSONObject()
            .put("date_from", ahora.minus(24, ChronoUnit.HOURS).toString())
            .put("date_to", ahora.toString())
        val muestras = try {
            post("/private_samples_range", pedidoEstacion(listOf(llu), extra))
        } catch (e: HttpError) {
            if (e.codigo == 429) return memoria.getFloat(clave, 0f).toDouble() // límite: uso lo último que tenía
            throw e
        }
        var total = 0.0
        for (i in 0 until muestras.length()) {
            val v = muestras.getJSONObject(i).optDouble(llu.toString(), 0.0)
            if (!v.isNaN()) total += v
        }
        memoria.edit().putFloat(clave, total.toFloat()).putLong("${clave}_t", ahora.toEpochMilli()).apply()
        return total
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

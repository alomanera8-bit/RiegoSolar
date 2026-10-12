package com.manera.riegosolar

import android.content.Context
import android.text.InputType
import java.time.LocalTime

/** Un campo editable de la pantalla de ajustes. */
data class Campo(
    val clave: String,
    val etiqueta: String,
    val porDefecto: String,
    val tipo: Int = InputType.TYPE_CLASS_TEXT,
    val seccion: String = "",
)

/** Configuración ya interpretada, lista para usar en la lógica. */
data class Config(
    val omixomToken: String,
    val estacion: Int,
    val modRad: Int?,
    val modLluvia: Int?,
    val gsiUser: String,
    val gsiPass: String,
    val proyecto: Int?,
    val unidad: Int?,
    val sn: String?,
    val inicio: LocalTime,
    val horaPregunta: LocalTime,
    val minSinSol: Long,
    val sinRespuestaMin: Long,
    val finInvierno: LocalTime,
    val finVerano: LocalTime,
    val mesesVerano: Set<Int>,
    val radOn: Double,
    val radOff: Double,
    val lluviaMm: Double,
    val pausarSiNoContesto: Boolean,
    val activo: Boolean,
    val pausaDiaMin: Int = 30,
)

object Ajustes {
    const val ARCHIVO = "ajustes"
    const val ACTIVO = "activo"
    const val PAUSAR_SI_NO_CONTESTO = "pausarSiNoContesto"

    private const val NUM = InputType.TYPE_CLASS_NUMBER
    private const val DEC = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    private const val CLAVE = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    private const val HORA = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME

    val CAMPOS = listOf(
        Campo("inicio", "Hora de inicio (si hay sol, arranca)", "08:30", HORA, "Horarios"),
        Campo("horaPregunta", "Desde qué hora preguntar si falta sol", "12:30", HORA),
        Campo("minSinSol", "Minutos sin sol antes de preguntar", "90", NUM),
        Campo("sinRespuestaMin", "Minutos que espera tu respuesta", "30", NUM),
        Campo("finInvierno", "Hora de fin en invierno", "18:30", HORA),
        Campo("finVerano", "Hora de fin en verano", "20:30", HORA),
        Campo("mesesVerano", "Meses de horario de verano (1 a 12)", "10,11,12,1,2,3"),

        Campo("radOn", "Radiación para regar (W/m²)", "450", DEC, "Sol y lluvia"),
        Campo("radOff", "Radiación que cuenta como \"sin sol\" (W/m²)", "300", DEC),
        Campo("lluviaMm", "Lluvia en 24 h que suspende el riego (mm)", "5", DEC),

        Campo("omixomToken", "Token de la API Omixom", "", CLAVE, "Estación Omixom"),
        Campo("estacion", "Número de estación", "", NUM),
        Campo("modRad", "Sensor de radiación (vacío = lo busca solo)", "", NUM),
        Campo("modLluvia", "Sensor de lluvia (vacío = lo busca solo)", "", NUM),

        Campo("gsiUser", "Usuario GSI", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, "Galcon GSI"),
        Campo("gsiPass", "Clave GSI", "", CLAVE),
        Campo("proyecto", "Proyecto GSI (vacío = lo detecta solo)", "", NUM),
        Campo("unidad", "Unidad GSI (vacío = la detecta sola)", "", NUM),
        Campo("sn", "N° de serie del Galcon (vacío = lo detecta solo)", ""),
    )

    fun porDefecto(clave: String): String = CAMPOS.firstOrNull { it.clave == clave }?.porDefecto ?: ""

    fun cargar(ctx: Context): Config {
        val p = ctx.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

        fun s(k: String): String {
            val v = p.getString(k, null)?.trim()
            return if (v.isNullOrEmpty()) porDefecto(k) else v
        }

        fun entero(k: String): Int =
            s(k).toIntOrNull() ?: throw IllegalArgumentException("Revisá el campo \"${etiqueta(k)}\"")

        fun decimal(k: String): Double =
            s(k).replace(',', '.').toDoubleOrNull() ?: throw IllegalArgumentException("Revisá el campo \"${etiqueta(k)}\"")

        fun hora(k: String): LocalTime = try {
            val t = s(k).replace('.', ':')
            LocalTime.parse(if (t.length == 4) "0$t" else t)
        } catch (e: Exception) {
            throw IllegalArgumentException("La hora de \"${etiqueta(k)}\" tiene que ser tipo 08:30")
        }

        fun lista(k: String): List<Int> =
            s(k).split(',', ';', ' ').mapNotNull { it.trim().toIntOrNull() }

        return Config(
            omixomToken = s("omixomToken"),
            estacion = s("estacion").toIntOrNull() ?: 0,
            modRad = s("modRad").toIntOrNull(),
            modLluvia = s("modLluvia").toIntOrNull(),
            gsiUser = s("gsiUser"),
            gsiPass = s("gsiPass"),
            proyecto = s("proyecto").toIntOrNull(),
            unidad = s("unidad").toIntOrNull(),
            sn = s("sn").ifBlank { null },
            inicio = hora("inicio"),
            horaPregunta = hora("horaPregunta"),
            minSinSol = entero("minSinSol").toLong(),
            sinRespuestaMin = entero("sinRespuestaMin").toLong(),
            finInvierno = hora("finInvierno"),
            finVerano = hora("finVerano"),
            mesesVerano = lista("mesesVerano").toSet(),
            radOn = decimal("radOn"),
            radOff = decimal("radOff"),
            lluviaMm = decimal("lluviaMm"),
            pausarSiNoContesto = p.getBoolean(PAUSAR_SI_NO_CONTESTO, true),
            activo = p.getBoolean(ACTIVO, true),
        )
    }

    private fun etiqueta(k: String) = CAMPOS.firstOrNull { it.clave == k }?.etiqueta ?: k

    /** Devuelve un texto con lo que falta configurar, o null si está todo. */
    fun faltantes(c: Config): String? {
        val f = mutableListOf<String>()
        if (c.omixomToken.isBlank()) f += "token de Omixom"
        if (c.estacion == 0) f += "número de estación"
        if (c.gsiUser.isBlank() || c.gsiPass.isBlank()) f += "usuario y clave de GSI"
        return if (f.isEmpty()) null else "Falta completar: " + f.joinToString(", ")
    }
}

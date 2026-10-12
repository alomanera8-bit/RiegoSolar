package com.manera.riegosolar

import android.content.Context
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * La lógica de riego. Se ejecuta cada 15 minutos (y al tocar un botón).
 *
 * Reglas:
 *  - Antes de la hora de inicio (08:30) y después de la hora de fin (18:30 invierno /
 *    20:30 verano): pausado.
 *  - Desde las 08:30, apenas hay sol suficiente: se despausa y riega.
 *  - Si falta el sol, sigue como estaba; a partir de las 12:30, si ya pasaron
 *    90 minutos sin sol suficiente, manda una notificación preguntando si pausar o
 *    seguir con red. Si no se contesta a tiempo, aplica la opción elegida en ajustes.
 *  - Cuando vuelve el sol, se olvida la decisión y vuelve a regar normal.
 *  - Si llovió lo suficiente en las últimas 24 h, pausa.
 */
object Motor {
    const val REGAR = "regando"
    const val PAUSA = "pausado"
    const val DECISION_PAUSAR = "pausar"
    const val DECISION_RED = "red"
    const val DECISION_AUTO = ""

    private const val ESTADO = "estado"
    private const val MAX_LINEAS_REGISTRO = 80
    private const val MAX_ANTIGUEDAD_DATO_MIN = 40L
    private const val LECTURAS_SEGUIDAS = 2

    private val fmtHora = DateTimeFormatter.ofPattern("HH:mm")
    private val fmtRegistro = DateTimeFormatter.ofPattern("dd/MM HH:mm")

    fun estado(ctx: Context) = ctx.getSharedPreferences(ESTADO, Context.MODE_PRIVATE)

    fun registrar(ctx: Context, mensaje: String) {
        val st = estado(ctx)
        val linea = "${ZonedDateTime.now().format(fmtRegistro)}  $mensaje"
        val previo = st.getString("registro", "") ?: ""
        val lineas = (listOf(linea) + previo.lines().filter { it.isNotBlank() }).take(MAX_LINEAS_REGISTRO)
        st.edit().putString("registro", lineas.joinToString("\n")).apply()
    }

    /** Decisión tomada desde la notificación o desde los botones de la app. */
    fun decidirManual(ctx: Context, decision: String, fija: Boolean) {
        val texto = when {
            decision == DECISION_PAUSAR && fija -> "Pausaste a mano (hasta que toques Automático o termine el día)"
            decision == DECISION_PAUSAR -> "Elegiste PAUSAR hasta que vuelva el sol"
            decision == DECISION_RED -> "Elegiste REGAR CON RED hasta que vuelva el sol o termine el horario"
            else -> "Volviste al modo automático"
        }
        val st = estado(ctx)
        // Se guarda la fecha de la decisión aparte, para que el reinicio diario no la borre.
        st.edit()
            .putString("fechaDecision", ZonedDateTime.now().toLocalDate().toString())
            .putString("decision", decision)
            .putBoolean("decisionFija", fija && decision != DECISION_AUTO)
            .putLong("preguntaEnviada", if (decision == DECISION_AUTO) 0L else st.getLong("preguntaEnviada", 0L))
            .apply()
        Notificaciones.cancelarPregunta(ctx)
        registrar(ctx, texto)
    }

    @Synchronized
    fun ejecutar(ctx: Context) {
        val cfg = try {
            Ajustes.cargar(ctx)
        } catch (e: IllegalArgumentException) {
            registrar(ctx, "Ajustes inválidos: ${e.message}")
            return
        }
        if (!cfg.activo) {
            guardarResumen(ctx, "Control automático apagado", null)
            return
        }
        Ajustes.faltantes(cfg)?.let {
            guardarResumen(ctx, it, null)
            return
        }

        val st = estado(ctx)
        val zona = ZoneId.systemDefault()
        val ahora = ZonedDateTime.now(zona)
        val ahoraMs = ahora.toInstant().toEpochMilli()
        val hoy = ahora.toLocalDate()
        val t = ahora.toLocalTime()

        var modo = st.getString("modo", "") ?: ""
        var sol = st.getBoolean("sol", false)
        var lecSol = st.getInt("lecSol", 0)
        var lecSombra = st.getInt("lecSombra", 0)
        var sinSolDesde = st.getLong("sinSolDesde", 0L)
        var decision = st.getString("decision", DECISION_AUTO) ?: DECISION_AUTO
        var decisionFija = st.getBoolean("decisionFija", false)
        val decisionLeida = decision
        val fechaDecisionLeida = st.getString("fechaDecision", "")
        var preguntaEnviada = st.getLong("preguntaEnviada", 0L)
        var arrancoHoy = st.getBoolean("arrancoHoy", false)
        var avisoLluvia = st.getBoolean("avisoLluvia", false)
        var lecturaTxt = st.getString("lectura", "") ?: ""

        // Día nuevo: se arranca de cero.
        if (st.getString("fecha", "") != hoy.toString()) {
            sol = false; lecSol = 0; lecSombra = 0; sinSolDesde = 0L
            preguntaEnviada = 0L; arrancoHoy = false; avisoLluvia = false
            if (st.getString("fechaDecision", "") != hoy.toString()) {
                // Una decisión de ayer no vale hoy (una tomada hoy a la madrugada, sí).
                decision = DECISION_AUTO; decisionFija = false
            }
            Notificaciones.cancelarPregunta(ctx)
        }

        val esVerano = ahora.monthValue in cfg.mesesVerano
        val fin = if (esVerano) cfg.finVerano else cfg.finInvierno
        var deseado = PAUSA
        var motivo: String

        if (t.isBefore(cfg.inicio) || !t.isBefore(fin)) {
            // ---------------------------------------------------- fuera de horario
            motivo = "Fuera del horario de riego (${cfg.inicio.format(fmtHora)} a ${fin.format(fmtHora)})"
            if (modo == REGAR) {
                Notificaciones.aviso(ctx, "Riego pausado", "Son las ${t.format(fmtHora)}: terminó el horario de riego de hoy.")
            }
            Notificaciones.cancelarPregunta(ctx)
        } else {
            // ------------------------------------------------- dentro del horario
            var lectura: LecturaClima? = null
            var error: String? = null
            try {
                lectura = Omixom(cfg.omixomToken, cfg.estacion).leer(cfg.modRad, cfg.modLluvia)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            val antiguedad = lectura?.let { Duration.between(it.horaDato, Instant.now()).toMinutes() }
            if (lectura != null) {
                val horaDato = lectura.horaDato.atZone(zona).format(fmtHora)
                lecturaTxt = "Radiación ${lectura.radiacion.roundToInt()} W/m² a las $horaDato · " +
                    "lluvia 24 h ${"%.1f".format(lectura.lluviaMm)} mm"
            }
            val datoValido = lectura != null && antiguedad != null && antiguedad <= MAX_ANTIGUEDAD_DATO_MIN

            if (lectura != null && lectura.lluviaMm >= cfg.lluviaMm) {
                motivo = "Llovió ${"%.1f".format(lectura.lluviaMm)} mm en las últimas 24 h"
                deseado = PAUSA
                if (!avisoLluvia) {
                    Notificaciones.aviso(ctx, "Riego suspendido por lluvia", "$motivo. No se riega hoy por ahora.")
                    avisoLluvia = true
                }
            } else {
                // Histéresis: hacen falta 2 lecturas seguidas para cambiar de sol a sombra o al revés.
                if (datoValido && lectura != null) {
                    val r = lectura.radiacion
                    if (r >= cfg.radOn) {
                        lecSol++; lecSombra = 0
                    } else if (r <= cfg.radOff) {
                        lecSombra++; lecSol = 0
                    }
                } else {
                    lecSombra++; lecSol = 0
                }
                if (!sol && lecSol >= LECTURAS_SEGUIDAS) sol = true
                if (sol && lecSombra >= LECTURAS_SEGUIDAS) sol = false

                motivo = if (datoValido && lectura != null) {
                    "Radiación ${lectura.radiacion.roundToInt()} W/m² (" + (if (sol) "hay sol" else "sin sol suficiente") + ")"
                } else {
                    "Sin datos frescos de la estación" + (error?.let { ": $it" } ?: "")
                }

                if (sol && decisionFija && decision == DECISION_PAUSAR) {
                    // Pausado a mano desde la app: se respeta aunque haya sol.
                    deseado = PAUSA
                    motivo += " · pausado a mano"
                } else if (sol) {
                    if (preguntaEnviada != 0L) Notificaciones.cancelarPregunta(ctx)
                    sinSolDesde = 0L
                    decision = DECISION_AUTO
                    decisionFija = false
                    preguntaEnviada = 0L
                    arrancoHoy = true
                    deseado = REGAR
                } else {
                    if (sinSolDesde == 0L) {
                        // Si todavía no arrancó hoy, el "sin sol" se cuenta desde la hora de inicio.
                        sinSolDesde = if (arrancoHoy) ahoraMs else ahora.with(cfg.inicio).toInstant().toEpochMilli()
                    }
                    val minSinSol = (ahoraMs - sinSolDesde) / 60_000

                    if (decision == DECISION_AUTO && !t.isBefore(cfg.horaPregunta) && minSinSol >= cfg.minSinSol) {
                        if (preguntaEnviada == 0L) {
                            Notificaciones.pregunta(ctx, minSinSol, modo == REGAR, cfg.sinRespuestaMin, cfg.pausarSiNoContesto)
                            preguntaEnviada = ahoraMs
                            Programador.enMinutos(ctx, cfg.sinRespuestaMin + 1)
                            registrar(ctx, "Pregunté si pausar o seguir con red ($minSinSol min sin sol)")
                        } else if (ahoraMs - preguntaEnviada >= cfg.sinRespuestaMin * 60_000) {
                            decision = if (cfg.pausarSiNoContesto) DECISION_PAUSAR else DECISION_RED
                            Notificaciones.cancelarPregunta(ctx)
                            val txt = if (decision == DECISION_PAUSAR) "Pausé el riego hasta que vuelva el sol." else "Sigo regando con red."
                            Notificaciones.aviso(ctx, "No contestaste", txt)
                            registrar(ctx, "Sin respuesta: $txt")
                        }
                    }

                    deseado = when (decision) {
                        DECISION_PAUSAR -> PAUSA
                        DECISION_RED -> REGAR
                        else -> if (modo == REGAR) REGAR else PAUSA // mientras tanto, sigue como estaba
                    }
                    if (decision == DECISION_RED) motivo += " · regando con red por tu elección"
                    if (decision == DECISION_PAUSAR) motivo += " · pausado por tu elección"
                    if (decision == DECISION_AUTO && preguntaEnviada != 0L) motivo += " · esperando tu respuesta"
                }
            }
        }

        // ------------------------------------------------------- aplicar en GSI
        var errorGsi: String? = null
        try {
            val gsi = Gsi(cfg, st)
            // Se mira el estado real del cabezal en GSI y solo se manda la orden si hace falta.
            val pausado = gsi.cabezalPausado()
            if (deseado == PAUSA && !pausado) gsi.pausarCabezal()
            if (deseado == REGAR && pausado) gsi.liberarCabezal()
            if (modo != deseado) {
                registrar(ctx, "${if (deseado == REGAR) "REGAR" else "PAUSAR"} · $motivo")
                if (deseado == REGAR && sol) Notificaciones.aviso(ctx, "Hay sol: riego activado", motivo)
            }
            modo = deseado
        } catch (e: Exception) {
            errorGsi = e.message ?: e.javaClass.simpleName
            registrar(ctx, "No pude ${if (deseado == REGAR) "despausar" else "pausar"} en GSI: $errorGsi")
            if (st.getString("ultimoErrorGsi", "") != errorGsi) {
                Notificaciones.aviso(ctx, "Problema con Galcon GSI", errorGsi, error = true)
            }
        }

        // Si tocaste un botón mientras esta revisión estaba en curso, se respeta tu elección
        // y se vuelve a revisar enseguida.
        val cambioDurante = st.getString("decision", DECISION_AUTO) != decisionLeida ||
            st.getString("fechaDecision", "") != fechaDecisionLeida
        val ed = st.edit()
        if (!cambioDurante) {
            ed.putString("decision", decision)
                .putBoolean("decisionFija", decisionFija)
                .putLong("preguntaEnviada", preguntaEnviada)
        }
        ed
            .putString("fecha", hoy.toString())
            .putString("modo", modo)
            .putBoolean("sol", sol)
            .putInt("lecSol", lecSol)
            .putInt("lecSombra", lecSombra)
            .putLong("sinSolDesde", sinSolDesde)
            .putBoolean("arrancoHoy", arrancoHoy)
            .putBoolean("avisoLluvia", avisoLluvia)
            .putString("lectura", lecturaTxt)
            .putString("ultimoErrorGsi", errorGsi ?: "")
            .apply()
        guardarResumen(ctx, motivo, errorGsi)
        if (cambioDurante) Programador.ahora(ctx)
    }

    private fun guardarResumen(ctx: Context, motivo: String, errorGsi: String?) {
        estado(ctx).edit()
            .putString("motivo", motivo + (errorGsi?.let { " · ERROR GSI: $it" } ?: ""))
            .putString("ultimaRevision", ZonedDateTime.now().format(fmtRegistro))
            .apply()
    }
}

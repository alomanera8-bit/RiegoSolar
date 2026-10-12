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
 * La app siempre está en uno de tres estados, y el productor lo ve en un aviso fijo:
 *
 *  - AUTOMÁTICO SOLAR (de la hora de inicio a la de fin): la app maneja el cabezal según
 *    el sol. Riega con sol, sigue como estaba si se nubla, y desde las 12:30 pregunta si
 *    pasaron 90 min sin sol. Si llovió, pausa.
 *  - FUERA DE HORARIO: al llegar la hora de fin pausa UNA sola vez, manda el resumen del día
 *    y después no toca nada hasta la hora de inicio del día siguiente.
 *  - MANUAL: si alguien pausa o libera el cabezal a mano (en GSI, en el equipo o con los
 *    botones de la app), la app lo detecta y no interviene hasta la próxima hora de inicio,
 *    salvo que toquen "Volver a automático".
 */
object Motor {
    const val REGAR = "regando"
    const val PAUSA = "pausado"

    // Respuestas a la pregunta de "falta sol" (dentro del modo automático).
    const val DECISION_PAUSAR = "pausar"
    const val DECISION_RED = "red"
    const val DECISION_AUTO = ""

    // Botones de la app y del aviso fijo.
    const val ACCION_PAUSAR_YA = "pausar_ya"
    const val ACCION_LIBERAR_YA = "liberar_ya"
    const val ACCION_AUTO = "auto"

    // Quién manda.
    const val CONTROL_AUTO = "auto"
    const val CONTROL_FUERA = "fuera"
    const val CONTROL_MANUAL = "manual"
    const val CONTROL_APAGADO = "apagado"

    private val VERDE = 0xFF2E7D32.toInt()
    private val GRIS = 0xFF757575.toInt()
    private val AMARILLO = 0xFFF9A825.toInt()

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

    /** Respuesta a la pregunta de "falta sol" (botones de esa notificación). */
    fun decidirPregunta(ctx: Context, decision: String) {
        val texto = if (decision == DECISION_PAUSAR) {
            "Elegiste PAUSAR hasta que vuelva el sol"
        } else {
            "Elegiste SEGUIR CON RED hasta que vuelva el sol o termine el horario"
        }
        estado(ctx).edit()
            .putString("fechaDecision", ZonedDateTime.now().toLocalDate().toString())
            .putString("decision", decision)
            .apply()
        Notificaciones.cancelarPregunta(ctx)
        registrar(ctx, texto)
    }

    /** Botones de la app: pausar o regar a mano (pasa a modo manual) o volver a automático. */
    fun pedirAccion(ctx: Context, accion: String) {
        val st = estado(ctx)
        if (accion == ACCION_AUTO) {
            st.edit()
                .putLong("manualDesde", 0L)
                .putString("esperado", "")
                .putString("pendiente", "")
                .putString("decision", DECISION_AUTO)
                .putLong("preguntaEnviada", 0L)
                .apply()
            registrar(ctx, "Volviste al modo automático")
        } else {
            st.edit().putString("pendiente", accion).apply()
            registrar(ctx, if (accion == ACCION_PAUSAR_YA) "Pediste PAUSAR a mano" else "Pediste REGAR a mano")
        }
        Notificaciones.cancelarPregunta(ctx)
        Programador.ahora(ctx)
    }

    @Synchronized
    fun ejecutar(ctx: Context) {
        val cfg = try {
            Ajustes.cargar(ctx)
        } catch (e: IllegalArgumentException) {
            registrar(ctx, "Ajustes inválidos: ${e.message}")
            return
        }
        val st = estado(ctx)
        if (!cfg.activo) {
            st.edit().putString("control", CONTROL_APAGADO).putString("esperado", "").apply()
            guardarResumen(ctx, "Control automático apagado: la app no toca el riego.", null)
            Notificaciones.estadoFijo(ctx, "La app está apagada", "No toca el riego. Para que maneje el riego con el sol, encendela en la app.", GRIS, false)
            return
        }
        Ajustes.faltantes(cfg)?.let {
            st.edit().putString("control", CONTROL_APAGADO).putString("esperado", "").apply()
            guardarResumen(ctx, it, null)
            Notificaciones.estadoFijo(ctx, "Falta configurar la app", it, GRIS, false)
            return
        }

        val zona = ZoneId.systemDefault()
        val ahora = ZonedDateTime.now(zona)
        val ahoraMs = ahora.toInstant().toEpochMilli()
        val hoy = ahora.toLocalDate().toString()
        val t = ahora.toLocalTime()
        val inicioTxt = cfg.inicio.format(fmtHora)
        val zonaLocal = ZoneId.systemDefault()
        // "hasta las 08:30" o "hasta mañana a las 08:30", según cuándo sea el próximo inicio.
        fun hastaInicio(desdeMs: Long): String {
            val prox = Instant.ofEpochMilli(proximoInicio(desdeMs, cfg.inicio, zonaLocal)).atZone(zonaLocal).toLocalDate()
            return if (prox == ahora.toLocalDate()) "hasta las $inicioTxt" else "hasta mañana a las $inicioTxt"
        }

        var modo = st.getString("modo", "") ?: ""
        var sol = st.getBoolean("sol", false)
        var lecSol = st.getInt("lecSol", 0)
        var lecSombra = st.getInt("lecSombra", 0)
        var sinSolDesde = st.getLong("sinSolDesde", 0L)
        var decision = st.getString("decision", DECISION_AUTO) ?: DECISION_AUTO
        var preguntaEnviada = st.getLong("preguntaEnviada", 0L)
        var arrancoHoy = st.getBoolean("arrancoHoy", false)
        var avisoLluvia = st.getBoolean("avisoLluvia", false)
        var lecturaTxt = st.getString("lectura", "") ?: ""
        var manualDesde = st.getLong("manualDesde", 0L)
        var esperado = st.getString("esperado", "") ?: ""
        var cierreHecho = st.getString("cierreHecho", "") ?: ""
        var inicioAvisado = st.getString("inicioAvisado", "") ?: ""
        var minSolar = st.getLong("minSolar", 0L)
        var minRed = st.getLong("minRed", 0L)
        val ultimaMarca = st.getLong("ultimaMarca", 0L)
        val cabezalAntes = st.getString("cabezal", "") ?: ""
        val solAntes = st.getBoolean("sol", false)
        val pendiente = st.getString("pendiente", "") ?: ""

        // Para no pisar un botón tocado mientras esta revisión estaba en curso.
        val decisionLeida = decision
        val fechaDecisionLeida = st.getString("fechaDecision", "")
        val manualLeido = manualDesde

        // Día nuevo: se arranca de cero.
        if (st.getString("fecha", "") != hoy) {
            sol = false; lecSol = 0; lecSombra = 0; sinSolDesde = 0L
            preguntaEnviada = 0L; arrancoHoy = false; avisoLluvia = false
            minSolar = 0L; minRed = 0L
            esperado = "" // lo de anoche no cuenta como cambio manual
            if (st.getString("fechaDecision", "") != hoy) decision = DECISION_AUTO
            Notificaciones.cancelarPregunta(ctx)
        }

        val esVerano = ahora.monthValue in cfg.mesesVerano
        val fin = if (esVerano) cfg.finVerano else cfg.finInvierno
        val finTxt = fin.format(fmtHora)
        val enHorario = !t.isBefore(cfg.inicio) && t.isBefore(fin)

        // El modo manual dura hasta la próxima hora de inicio.
        if (manualDesde > 0L && ahoraMs >= proximoInicio(manualDesde, cfg.inicio, zona)) {
            manualDesde = 0L
            esperado = ""
            registrar(ctx, "Terminó el modo manual: la app vuelve a manejar el riego")
        }

        // Estado real del cabezal en GSI.
        val gsi = Gsi(cfg, st)
        var errorGsi: String? = null
        var pausado: Boolean? = null
        try {
            pausado = gsi.cabezalPausado()
        } catch (e: Exception) {
            errorGsi = e.message ?: e.javaClass.simpleName
        }

        // 1) Botón de la app: pausar o regar a mano -> modo manual.
        var pendienteHecho = false
        if (pendiente.isNotEmpty() && pausado != null) {
            try {
                if (pendiente == ACCION_PAUSAR_YA) {
                    if (pausado == false) gsi.pausarCabezal()
                    pausado = true
                } else {
                    if (pausado == true) gsi.liberarCabezal()
                    pausado = false
                }
                manualDesde = ahoraMs
                esperado = ""
                pendienteHecho = true
                val que = if (pausado == true) "pausado" else "regando"
                Notificaciones.aviso(
                    ctx, "Control manual",
                    "El riego quedó $que a mano. La app no interviene ${hastaInicio(ahoraMs)}, " +
                        "salvo que toques \"Volver a automático\".",
                )
                registrar(ctx, "MANUAL · riego $que desde la app")
            } catch (e: Exception) {
                errorGsi = e.message ?: e.javaClass.simpleName
            }
        }

        // 2) Alguien cambió el cabezal desde GSI o el equipo -> modo manual.
        if (manualDesde == 0L && pausado != null && esperado.isNotEmpty() && pausado != (esperado == PAUSA)) {
            manualDesde = ahoraMs
            esperado = ""
            val que = if (pausado == true) "pausó" else "liberó"
            Notificaciones.aviso(
                ctx, "Tomaste el control manual",
                "Alguien $que el riego desde GSI o desde el equipo. La app no interviene " +
                    "${hastaInicio(ahoraMs)}. Si querés que vuelva a manejarlo antes, tocá \"Volver a automático\".",
            )
            registrar(ctx, "MANUAL · alguien $que el riego desde GSI o el equipo")
        }

        val control: String
        var motivo: String
        val ordenTrabada = pendiente.isNotEmpty() && !pendienteHecho

        if (manualDesde > 0L) {
            // ------------------------------------------------------------- manual
            control = CONTROL_MANUAL
            val desde = Instant.ofEpochMilli(manualDesde).atZone(zona).format(fmtHora)
            motivo = "Control manual desde las $desde. La app no interviene ${hastaInicio(manualDesde)}."
            Notificaciones.cancelarPregunta(ctx)
        } else if (!enHorario) {
            // -------------------------------------------------- fuera de horario
            control = CONTROL_FUERA
            if (!t.isBefore(fin) && cierreHecho != hoy && pausado != null) {
                // Cierre del día: pausa una sola vez y manda el resumen.
                try {
                    if (pausado == false) gsi.pausarCabezal()
                    pausado = true
                    cierreHecho = hoy
                    val resumen = "Hoy regó ${duracion(minSolar)} con sol y ${duracion(minRed)} con luz de red."
                    Notificaciones.aviso(
                        ctx, "Terminó el horario solar",
                        "Son las ${t.format(fmtHora)}. El riego quedó pausado. $resumen " +
                            "Desde ahora la app no interviene hasta mañana a las $inicioTxt.",
                    )
                    registrar(ctx, "CIERRE · $resumen")
                } catch (e: Exception) {
                    errorGsi = e.message ?: e.javaClass.simpleName
                }
            }
            esperado = "" // de noche el productor puede hacer lo que quiera
            motivo = if (t.isBefore(cfg.inicio)) {
                "Fuera del horario solar. La app arranca a las $inicioTxt."
            } else {
                "Terminó el horario solar de hoy. La app no interviene hasta mañana a las $inicioTxt."
            }
            Notificaciones.cancelarPregunta(ctx)
        } else {
            // ------------------------------------------------- automático solar
            control = CONTROL_AUTO
            if (inicioAvisado != hoy) {
                Notificaciones.aviso(
                    ctx, "Arrancó el horario solar",
                    "Hoy la app maneja el riego de $inicioTxt a $finTxt según el sol. No tenés que hacer nada.",
                )
                inicioAvisado = hoy
            }

            var deseado = PAUSA
            var lectura: LecturaClima? = null
            var error: String? = null
            try {
                lectura = Omixom(cfg.omixomToken, cfg.estacion, st).leer(cfg.modRad, cfg.modLluvia)
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
                    Notificaciones.aviso(ctx, "Riego suspendido por lluvia", "$motivo. No se riega por ahora.")
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

                if (sol) {
                    if (preguntaEnviada != 0L) Notificaciones.cancelarPregunta(ctx)
                    sinSolDesde = 0L
                    decision = DECISION_AUTO
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
                        if (preguntaEnviada == 0L && pausado != null) {
                            Notificaciones.pregunta(ctx, minSinSol, pausado == false, cfg.sinRespuestaMin, cfg.pausarSiNoContesto)
                            preguntaEnviada = ahoraMs
                            Programador.enMinutos(ctx, cfg.sinRespuestaMin + 1)
                            registrar(ctx, "Pregunté si pausar o seguir con red ($minSinSol min sin sol)")
                        } else if (preguntaEnviada != 0L && ahoraMs - preguntaEnviada >= cfg.sinRespuestaMin * 60_000) {
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
                        // Mientras tanto sigue como estaba; si hoy todavía no hubo sol, queda pausado.
                        else -> if (arrancoHoy && pausado == false) REGAR else PAUSA
                    }
                    if (decision == DECISION_RED) motivo += " · regando con red por tu elección"
                    if (decision == DECISION_PAUSAR) motivo += " · pausado por tu elección"
                    if (decision == DECISION_AUTO && preguntaEnviada != 0L) motivo += " · esperando tu respuesta"
                }
            }

            // Aplicar en GSI solo si hace falta.
            if (pausado != null) {
                try {
                    if (deseado == PAUSA && pausado == false) {
                        gsi.pausarCabezal()
                        pausado = true
                        Notificaciones.aviso(ctx, "Riego pausado", motivo)
                        registrar(ctx, "PAUSAR · $motivo")
                    } else if (deseado == REGAR && pausado == true) {
                        gsi.liberarCabezal()
                        pausado = false
                        Notificaciones.aviso(ctx, if (sol) "Hay sol: riego activado" else "Riego activado con red", motivo)
                        registrar(ctx, "REGAR · $motivo")
                    }
                    modo = deseado
                    esperado = deseado
                } catch (e: Exception) {
                    errorGsi = e.message ?: e.javaClass.simpleName
                }
            }
        }

        // Tiempo de riego dentro del horario solar, contado sobre el intervalo que pasó
        // (desde la revisión anterior), con el estado que tenía el cabezal en ese intervalo.
        if (ultimaMarca > 0L && cabezalAntes == REGAR) {
            val ini = maxOf(ultimaMarca, ahora.with(cfg.inicio).toInstant().toEpochMilli())
            val fi = minOf(ahoraMs, ahora.with(fin).toInstant().toEpochMilli())
            val mins = ((fi - ini) / 60_000).coerceIn(0L, 30L)
            if (solAntes) minSolar += mins else minRed += mins
        }

        if (ordenTrabada) motivo += " · tu orden manual queda pendiente: no hay conexión con GSI"

        // Errores de GSI: se avisa una vez por error distinto.
        val err = errorGsi
        if (err != null) {
            registrar(ctx, "Problema con GSI: $err")
            if (st.getString("ultimoErrorGsi", "") != err) {
                Notificaciones.aviso(ctx, "Problema con Galcon GSI", err, error = true)
            }
        }

        // Aviso fijo: quién manda ahora.
        val riego = when (pausado) {
            true -> "Riego pausado"
            false -> "Regando"
            null -> "Sin conexión con GSI"
        }
        when (control) {
            CONTROL_AUTO -> Notificaciones.estadoFijo(
                ctx, "Automático solar · $riego", "$motivo. La app maneja el riego hasta las $finTxt.", VERDE, false,
            )
            CONTROL_FUERA -> Notificaciones.estadoFijo(ctx, "La app no interviene · $riego", motivo, GRIS, false)
            else -> Notificaciones.estadoFijo(ctx, "Control manual · $riego", motivo, AMARILLO, true)
        }

        // Guardar. Si tocaste un botón durante la revisión, se respeta y se revisa de nuevo.
        val cambioDecision = st.getString("decision", DECISION_AUTO) != decisionLeida ||
            st.getString("fechaDecision", "") != fechaDecisionLeida
        val cambioManual = st.getLong("manualDesde", 0L) != manualLeido
        val cambioPendiente = (st.getString("pendiente", "") ?: "") != pendiente
        val ed = st.edit()
        if (!cambioDecision) {
            ed.putString("decision", decision).putLong("preguntaEnviada", preguntaEnviada)
        }
        if (!cambioManual) {
            ed.putLong("manualDesde", manualDesde).putString("esperado", esperado)
        }
        if (pendienteHecho && !cambioPendiente) ed.putString("pendiente", "")
        ed
            .putString("fecha", hoy)
            .putString("control", control)
            .putString("modo", modo)
            .putBoolean("sol", sol)
            .putInt("lecSol", lecSol)
            .putInt("lecSombra", lecSombra)
            .putLong("sinSolDesde", sinSolDesde)
            .putBoolean("arrancoHoy", arrancoHoy)
            .putBoolean("avisoLluvia", avisoLluvia)
            .putString("lectura", lecturaTxt)
            .putString("cierreHecho", cierreHecho)
            .putString("inicioAvisado", inicioAvisado)
            .putLong("minSolar", minSolar)
            .putLong("minRed", minRed)
            .putLong("ultimaMarca", ahoraMs)
            .putString("cabezal", when (pausado) { true -> PAUSA; false -> REGAR; null -> "" })
            .putString("ultimoErrorGsi", errorGsi ?: "")
            .apply()
        guardarResumen(ctx, motivo, errorGsi)
        if (cambioDecision || cambioManual || cambioPendiente) Programador.ahora(ctx)
    }

    private fun guardarResumen(ctx: Context, motivo: String, errorGsi: String?) {
        estado(ctx).edit()
            .putString("motivo", motivo + (errorGsi?.let { " · ERROR GSI: $it" } ?: ""))
            .putString("ultimaRevision", ZonedDateTime.now().format(fmtRegistro))
            .apply()
    }

    /** La próxima vez que el reloj marque [inicio] después de [desdeMs]. */
    private fun proximoInicio(desdeMs: Long, inicio: LocalTime, zona: ZoneId): Long {
        val desde = Instant.ofEpochMilli(desdeMs).atZone(zona)
        var objetivo = desde.with(inicio)
        if (!objetivo.isAfter(desde)) objetivo = objetivo.plusDays(1)
        return objetivo.toInstant().toEpochMilli()
    }

    private fun duracion(minutos: Long): String {
        val h = minutos / 60
        val m = minutos % 60
        return if (h > 0) "$h h $m min" else "$m min"
    }

    /** Textos para la pantalla de la app. */
    fun textoControl(control: String?): Pair<String, Int> = when (control) {
        CONTROL_AUTO -> "AUTOMÁTICO SOLAR\nLa app maneja el riego" to VERDE
        CONTROL_FUERA -> "FUERA DE HORARIO\nLa app no interviene" to GRIS
        CONTROL_MANUAL -> "CONTROL MANUAL\nLa app no interviene hasta la hora de inicio" to AMARILLO
        CONTROL_APAGADO -> "APAGADA\nLa app no toca el riego" to GRIS
        else -> "Todavía sin revisar" to GRIS
    }
}

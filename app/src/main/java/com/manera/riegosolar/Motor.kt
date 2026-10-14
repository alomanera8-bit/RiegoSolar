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
 *  REGLA PRINCIPAL: la app NUNCA pausa el riego sin permiso. Solo puede liberarlo sola
 *  (cuando hay sol y estaba pausado). Para pausar siempre pregunta, y si no le contestan,
 *  no toca nada.
 *
 *  - AUTOMÁTICO SOLAR (de la hora de inicio a la de fin): con sol, si el cabezal está
 *    pausado lo libera. Si se nubla no hace nada; desde las 12:30, si pasaron 90 min sin sol,
 *    pregunta si pausar. Si llovió, pregunta si pausar por hoy.
 *  - FUERA DE HORARIO: a la hora de fin pregunta UNA vez si pausar hasta mañana y manda el
 *    resumen del día. Después no toca nada hasta la hora de inicio del día siguiente.
 *  - MANUAL: si alguien pausa o libera el cabezal a mano (en GSI, en el equipo o con los
 *    botones de la app), la app lo detecta y no interviene hasta la próxima hora de inicio,
 *    salvo que toquen "Volver a automático".
 */
object Motor {
    const val REGAR = "regando"
    const val PAUSA = "pausado"

    // Respuestas a las preguntas (el productor decide; la app nunca pausa sola).
    const val DECISION_PAUSAR = "pausar"            // falta sol: pausar hasta que vuelva el sol
    const val DECISION_RED = "red"                  // falta sol: seguir regando con red
    const val DECISION_PAUSAR_LLUVIA = "pausar_lluvia" // llovió: pausar por hoy
    const val DECISION_SEGUIR_LLUVIA = "seguir_lluvia" // llovió: seguir igual
    const val DECISION_NOCHE_PAUSAR = "noche_pausar"   // fin del horario: pausar hasta mañana
    const val DECISION_NOCHE_DEJAR = "noche_dejar"     // fin del horario: dejar como está
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
    fun decidirPregunta(ctx: Context, decision: String, fechaPregunta: String?) {
        val hoy = ZonedDateTime.now().toLocalDate().toString()
        val st = estado(ctx)
        Notificaciones.cancelarPregunta(ctx)
        // Una respuesta a una pregunta de otro día no vale (por ejemplo, tocada pasada la medianoche).
        if (fechaPregunta != null && fechaPregunta != hoy) {
            registrar(ctx, "Respuesta a una pregunta de otro día: no se aplicó")
            return
        }
        when (decision) {
            DECISION_NOCHE_PAUSAR, DECISION_NOCHE_DEJAR -> {
                st.edit().putString("respuestaNoche", decision).putString("fechaNoche", hoy).apply()
                registrar(ctx, if (decision == DECISION_NOCHE_PAUSAR) "Elegiste PAUSAR hasta mañana" else "Elegiste DEJAR el riego como está esta noche")
            }
            DECISION_PAUSAR_LLUVIA, DECISION_SEGUIR_LLUVIA -> {
                st.edit().putString("respuestaLluvia", decision).putString("fechaLluvia", hoy).apply()
                registrar(ctx, if (decision == DECISION_PAUSAR_LLUVIA) "Elegiste PAUSAR por la lluvia" else "Elegiste SEGUIR regando pese a la lluvia")
            }
            else -> {
                st.edit().putString("fechaDecision", hoy).putString("decision", decision).apply()
                registrar(ctx, if (decision == DECISION_PAUSAR) "Elegiste PAUSAR hasta que vuelva el sol" else "Elegiste SEGUIR CON RED hasta que vuelva el sol o termine el horario")
            }
        }
        Notificaciones.cancelarPregunta(ctx)
    }

    /** Botones de la app: pausar o regar a mano (pasa a modo manual) o volver a automático. */
    fun pedirAccion(ctx: Context, accion: String) {
        val st = estado(ctx)
        if (accion == ACCION_AUTO) {
            st.edit()
                .putLong("manualDesde", 0L)
                .putString("esperado", "")
                .putString("pendiente", "")
                .remove("respuestaLluvia").remove("fechaLluvia")
                .remove("respuestaNoche").remove("fechaNoche")
                .putString("decision", DECISION_AUTO)
                .putLong("preguntaEnviada", 0L)
                .apply()
            registrar(ctx, "Volviste al modo automático")
        } else {
            st.edit().putString("pendiente", accion).putLong("pendienteT", System.currentTimeMillis()).apply()
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
        var pendiente = st.getString("pendiente", "") ?: ""
        // Una orden manual que no se pudo mandar en 30 min (sin conexión con GSI) se descarta.
        if (pendiente.isNotEmpty() && System.currentTimeMillis() - st.getLong("pendienteT", 0L) > 30 * 60_000L) {
            registrar(ctx, "No se pudo mandar tu orden manual a tiempo (sin conexión con GSI): se descartó")
            Notificaciones.aviso(ctx, "No se pudo mandar tu orden", "No hubo conexión con GSI. La orden se descartó; volvé a intentarlo.", error = true)
            st.edit().putString("pendiente", "").apply()
            pendiente = ""
        }

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
            val respuestaNoche = if (st.getString("fechaNoche", "") == hoy) st.getString("respuestaNoche", "") ?: "" else ""
            if (!t.isBefore(fin) && cierreHecho != hoy && pausado != null) {
                // Cierre del día: resumen + PREGUNTA si pausar. La app no pausa sola.
                cierreHecho = hoy
                val resumen = "Hoy regó ${duracion(minSolar)} con sol y ${duracion(minRed)} con luz de red."
                if (pausado == false) {
                    Notificaciones.pregunta(
                        ctx, "Terminó el horario solar",
                        "Son las ${t.format(fmtHora)}. $resumen El riego sigue andando. " +
                            "¿Lo pauso hasta mañana a las $inicioTxt? Si no contestás, no toco nada.",
                        "Pausar hasta mañana" to DECISION_NOCHE_PAUSAR,
                        "Dejar como está" to DECISION_NOCHE_DEJAR,
                    )
                } else {
                    Notificaciones.aviso(
                        ctx, "Terminó el horario solar",
                        "$resumen La app no interviene hasta mañana a las $inicioTxt.",
                    )
                }
                registrar(ctx, "CIERRE · $resumen")
            }
            // Pausa de la noche: solo si el productor tocó "Pausar hasta mañana".
            if (respuestaNoche == DECISION_NOCHE_PAUSAR && !t.isBefore(fin) && pausado == false &&
                st.getString("nochePausadaHecha", "") != hoy
            ) {
                try {
                    gsi.pausarCabezal()
                    pausado = true
                    st.edit().putString("nochePausadaHecha", hoy).apply()
                    Notificaciones.aviso(ctx, "Riego pausado", "Pausado hasta mañana, como elegiste.")
                    registrar(ctx, "PAUSAR · hasta mañana (lo elegiste vos)")
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
            if (t.isBefore(cfg.inicio)) Notificaciones.cancelarPregunta(ctx)
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

            // Lo que la app hará: null = no tocar nada. La app solo pausa si el productor lo eligió.
            var deseado: String? = null
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
            val respuestaLluvia = if (st.getString("fechaLluvia", "") == hoy) st.getString("respuestaLluvia", "") ?: "" else ""

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

            val llovio = lectura != null && lectura.lluviaMm >= cfg.lluviaMm
            if (llovio && respuestaLluvia.isEmpty() && !avisoLluvia && pausado == false && lectura != null) {
                // Llovió: se pregunta, no se pausa solo.
                Notificaciones.pregunta(
                    ctx, "Llovió ${"%.1f".format(lectura.lluviaMm)} mm",
                    "En las últimas 24 h llovieron ${"%.1f".format(lectura.lluviaMm)} mm y el riego está andando. " +
                        "¿Lo pauso por hoy? Si no contestás, no toco nada.",
                    "Pausar por hoy" to DECISION_PAUSAR_LLUVIA,
                    "Seguir regando" to DECISION_SEGUIR_LLUVIA,
                )
                avisoLluvia = true
                registrar(ctx, "Pregunté si pausar por lluvia")
            }

            if (respuestaLluvia == DECISION_PAUSAR_LLUVIA) {
                deseado = PAUSA
                motivo += " · pausado por la lluvia (lo elegiste vos)"
            } else if (sol) {
                // Con sol: si estaba pausado, la app lo libera (esto sí lo hace sola).
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

                // Desde las 12:30, si el riego anda con red hace rato: preguntar. Si no contesta, nada.
                if (decision == DECISION_AUTO && preguntaEnviada == 0L && pausado == false &&
                    !t.isBefore(cfg.horaPregunta) && minSinSol >= cfg.minSinSol
                ) {
                    val horas = minSinSol / 60
                    val mins = minSinSol % 60
                    val tiempo = if (horas > 0) "$horas h $mins min" else "$mins min"
                    Notificaciones.pregunta(
                        ctx, "Falta sol para regar",
                        "Hace $tiempo que no hay sol suficiente y el riego está andando con luz de red. " +
                            "¿Lo pauso hasta que vuelva el sol? Si no contestás, no toco nada.",
                        "Pausar" to DECISION_PAUSAR,
                        "Seguir con red" to DECISION_RED,
                    )
                    preguntaEnviada = ahoraMs
                    registrar(ctx, "Pregunté si pausar ($minSinSol min sin sol)")
                }

                if (decision == DECISION_PAUSAR) {
                    deseado = PAUSA
                    motivo += " · pausado hasta que vuelva el sol (lo elegiste vos)"
                } else if (decision == DECISION_RED) {
                    motivo += " · sigue con red (lo elegiste vos)"
                } else if (preguntaEnviada != 0L) {
                    motivo += " · te pregunté si pausar; si no contestás no toco nada"
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
                        Notificaciones.aviso(ctx, "Hay sol: riego liberado", motivo)
                        registrar(ctx, "REGAR · $motivo")
                    }
                    modo = if (pausado == true) PAUSA else REGAR
                    // Se recuerda cómo quedó, para notar si alguien lo cambia a mano.
                    esperado = modo
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

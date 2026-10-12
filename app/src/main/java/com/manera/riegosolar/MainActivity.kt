package com.manera.riegosolar

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var estadoTv: TextView
    private lateinit var registroTv: TextView
    private lateinit var activoSw: Switch
    private lateinit var pausarSiNoCb: CheckBox
    private lateinit var bateriaBtn: Button
    private val campos = mutableMapOf<String, EditText>()
    private val handler = Handler(Looper.getMainLooper())
    private val refresco = object : Runnable {
        override fun run() {
            mostrarEstado()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notificaciones.crearCanales(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContentView(construirPantalla())
        cargarCampos()
        Programador.programar(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresco)
        actualizarBotonBateria()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresco)
    }

    // ------------------------------------------------------------- pantalla ---

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun titulo(texto: String, tamano: Float = 18f) = TextView(this).apply {
        text = texto
        textSize = tamano
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun boton(texto: String, accion: () -> Unit) = Button(this).apply {
        text = texto
        isAllCaps = false
        setOnClickListener { accion() }
    }

    private fun fila(vararg vistas: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in vistas) addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun construirPantalla(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }

        col.addView(titulo("Estado", 20f))
        estadoTv = TextView(this).apply {
            textSize = 15f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.parseColor("#FFEFF6FC"))
            setTextColor(Color.parseColor("#FF1B2A38"))
        }
        col.addView(estadoTv)

        col.addView(fila(
            boton("Revisar ahora") { revisarAhora() },
            boton("Automático") { decidir(Motor.DECISION_AUTO, false) },
        ))
        col.addView(fila(
            boton("Regar con red") { decidir(Motor.DECISION_RED, true) },
            boton("Pausar") { decidir(Motor.DECISION_PAUSAR, true) },
        ))

        activoSw = Switch(this).apply {
            text = "Control automático encendido"
            textSize = 16f
            setPadding(0, dp(12), 0, dp(4))
            setOnCheckedChangeListener { _, activo ->
                prefs().edit().putBoolean(Ajustes.ACTIVO, activo).apply()
            }
        }
        col.addView(activoSw)

        var seccion = ""
        for (c in Ajustes.CAMPOS) {
            if (c.seccion.isNotEmpty() && c.seccion != seccion) {
                seccion = c.seccion
                col.addView(titulo(seccion))
            }
            col.addView(TextView(this).apply {
                text = c.etiqueta
                textSize = 13f
                setPadding(0, dp(6), 0, 0)
            })
            val et = EditText(this).apply {
                inputType = c.tipo
                isSingleLine = true
                hint = c.porDefecto
            }
            campos[c.clave] = et
            col.addView(et)
            if (c.clave == "lluviaMm") {
                pausarSiNoCb = CheckBox(this).apply {
                    text = "Si no contesto la pregunta, pausar (si no, sigue con red)"
                    setPadding(0, dp(8), 0, 0)
                }
                col.addView(TextView(this).apply {
                    text = "Cuando falta el sol"
                    textSize = 13f
                    setPadding(0, dp(10), 0, 0)
                })
                col.addView(pausarSiNoCb)
            }
        }

        col.addView(fila(
            boton("Guardar") { guardar() },
            boton("Probar conexión") { probarConexion() },
        ))
        bateriaBtn = boton("Permitir que funcione siempre (batería)") { pedirBateria() }
        col.addView(bateriaBtn)

        col.addView(titulo("Registro"))
        registroTv = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        col.addView(registroTv)

        return ScrollView(this).apply { addView(col) }
    }

    // --------------------------------------------------------------- datos ---

    private fun prefs() = getSharedPreferences(Ajustes.ARCHIVO, Context.MODE_PRIVATE)

    private fun cargarCampos() {
        val p = prefs()
        for ((clave, et) in campos) {
            et.setText(p.getString(clave, Ajustes.porDefecto(clave)))
        }
        activoSw.isChecked = p.getBoolean(Ajustes.ACTIVO, true)
        pausarSiNoCb.isChecked = p.getBoolean(Ajustes.PAUSAR_SI_NO_CONTESTO, true)
    }

    private fun guardar() {
        val e = prefs().edit()
        for ((clave, et) in campos) e.putString(clave, et.text.toString().trim())
        e.putBoolean(Ajustes.PAUSAR_SI_NO_CONTESTO, pausarSiNoCb.isChecked)
        e.putBoolean(Ajustes.ACTIVO, activoSw.isChecked)
        e.apply()
        // Por si cambió la cuenta de GSI: se vuelve a iniciar sesión y a detectar el equipo.
        Motor.estado(this).edit()
            .remove("tokenGsi").remove("gsiProyecto").remove("gsiUnidad").remove("gsiSn")
            .apply()
        try {
            val cfg = Ajustes.cargar(this)
            val falta = Ajustes.faltantes(cfg)
            Toast.makeText(this, falta ?: "Guardado. Revisando…", Toast.LENGTH_LONG).show()
            Programador.programar(this)
            if (falta == null) Programador.ahora(this)
        } catch (ex: IllegalArgumentException) {
            Toast.makeText(this, ex.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun revisarAhora() {
        Programador.ahora(this)
        Toast.makeText(this, "Revisando… en unos segundos se actualiza el estado", Toast.LENGTH_SHORT).show()
    }

    private fun decidir(decision: String, fija: Boolean) {
        Motor.decidirManual(this, decision, fija)
        Programador.ahora(this)
        mostrarEstado()
    }

    private fun mostrarEstado() {
        val st = Motor.estado(this)
        val modo = when (st.getString("modo", "")) {
            Motor.REGAR -> "REGANDO"
            Motor.PAUSA -> "PAUSADO"
            else -> "todavía sin revisar"
        }
        val decision = when (st.getString("decision", "")) {
            Motor.DECISION_RED -> "\nTu elección: regar con red"
            Motor.DECISION_PAUSAR -> "\nTu elección: pausado"
            else -> ""
        }
        val sb = StringBuilder()
        sb.append("Galcon: ").append(modo).append(decision)
        st.getString("motivo", null)?.let { sb.append("\n").append(it) }
        st.getString("lectura", null)?.takeIf { it.isNotBlank() }?.let { sb.append("\n").append(it) }
        st.getString("ultimaRevision", null)?.let { sb.append("\nÚltima revisión: ").append(it) }
        estadoTv.text = sb.toString()
        registroTv.text = st.getString("registro", "")?.ifBlank { "Sin movimientos todavía." }
    }

    private fun probarConexion() {
        guardar()
        val dialogo = AlertDialog.Builder(this)
            .setTitle("Probando conexión…")
            .setMessage("Consultando Omixom y GSI.")
            .setPositiveButton("Cerrar", null)
            .show()
        Thread {
            val sb = StringBuilder()
            try {
                val cfg = Ajustes.cargar(this)
                try {
                    val om = Omixom(cfg.omixomToken, cfg.estacion)
                    val mods = om.modulos()
                    val rad = cfg.modRad ?: om.buscar(mods, Omixom.PALABRAS_RADIACION)
                    val llu = cfg.modLluvia ?: om.buscar(mods, Omixom.PALABRAS_LLUVIA)
                    sb.append("OMIXOM: conectado ✔\nSensores:\n")
                    for (m in mods) {
                        val marca = when (m.id) { rad -> "  ← radiación"; llu -> "  ← lluvia"; else -> "" }
                        sb.append("  ${m.id}  ${m.titulo} (${m.tipo})$marca\n")
                    }
                    if (rad == null) sb.append("⚠ No identifiqué el de radiación: poné su número en ajustes.\n")
                    val l = om.leer(cfg.modRad, cfg.modLluvia)
                    sb.append("Última radiación: ${l.radiacion} W/m²\n")
                } catch (e: Exception) {
                    sb.append("OMIXOM: error ✘ ${e.message}\n")
                }
                sb.append("\n")
                try {
                    val st = Motor.estado(this)
                    val gsi = Gsi(cfg, st)
                    val pausado = gsi.cabezalPausado()
                    val progs = gsi.programas()
                    sb.append("GSI: conectado ✔\nCabezal de riego: ")
                        .append(if (pausado) "PAUSADO" else "liberado").append("\nProgramas:\n")
                    for (i in 0 until progs.length()) {
                        val p = progs.getJSONObject(i)
                        val nombre = p.optString("Name").trim().ifEmpty { "(sin nombre)" }
                        sb.append("  ${p.optInt("OrderNum")}. $nombre\n")
                    }
                } catch (e: Exception) {
                    sb.append("GSI: error ✘ ${e.message}\n")
                }
            } catch (e: Exception) {
                sb.append(e.message)
            }
            runOnUiThread {
                dialogo.setTitle("Prueba de conexión")
                dialogo.setMessage(sb.toString())
            }
        }.start()
    }

    // ------------------------------------------------------------- batería ---

    private fun sinRestriccionBateria(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun actualizarBotonBateria() {
        bateriaBtn.visibility = if (sinRestriccionBateria()) View.GONE else View.VISIBLE
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun pedirBateria() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

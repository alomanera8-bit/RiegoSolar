package com.manera.riegosolar

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notificaciones {
    private const val CANAL_PREGUNTA = "pregunta"
    private const val CANAL_AVISOS = "avisos"
    private const val CANAL_ESTADO = "estado"
    const val ID_PREGUNTA = 1
    private const val ID_AVISO = 2
    private const val ID_ERROR = 3
    private const val ID_ESTADO = 4
    const val ACCION_DECISION = "com.manera.riegosolar.DECISION"
    const val EXTRA_DECISION = "decision"
    const val EXTRA_FECHA = "fecha"

    fun crearCanales(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CANAL_PREGUNTA, "Preguntas de riego", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Cuando falta sol y hay que decidir si pausar o seguir con red"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CANAL_AVISOS, "Avisos de riego", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Cuando el riego arranca, se pausa o hay un problema"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CANAL_ESTADO, "Estado del riego", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aviso fijo que muestra si la app está manejando el riego o no"
                setShowBadge(false)
            },
        )
    }

    private fun abrirApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun botonDecision(ctx: Context, decision: String, codigo: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, codigo,
            Intent(ctx, AccionReceiver::class.java)
                .setAction(ACCION_DECISION)
                .putExtra(EXTRA_DECISION, decision)
                // Fecha de la pregunta: una respuesta tocada otro día se ignora.
                .putExtra(EXTRA_FECHA, java.time.LocalDate.now().toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    @SuppressLint("MissingPermission")
    private fun mostrar(ctx: Context, id: Int, n: Notification) {
        try {
            val nm = NotificationManagerCompat.from(ctx)
            if (nm.areNotificationsEnabled()) nm.notify(id, n)
        } catch (e: SecurityException) {
            // Sin permiso de notificaciones: no se puede avisar.
        }
    }

    fun aviso(ctx: Context, titulo: String, texto: String, error: Boolean = false) {
        crearCanales(ctx)
        val n = NotificationCompat.Builder(ctx, CANAL_AVISOS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(abrirApp(ctx))
            .setAutoCancel(true)
            .build()
        mostrar(ctx, if (error) ID_ERROR else ID_AVISO, n)
    }

    /**
     * Pregunta con dos botones. La app nunca pausa sin que el productor toque "sí".
     * [si] y [no] son (texto del botón, decisión que se guarda).
     */
    fun pregunta(ctx: Context, titulo: String, texto: String, si: Pair<String, String>, no: Pair<String, String>) {
        crearCanales(ctx)
        val n = NotificationCompat.Builder(ctx, CANAL_PREGUNTA)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(abrirApp(ctx))
            .setAutoCancel(true)
            .addAction(0, si.first, botonDecision(ctx, si.second, 11))
            .addAction(0, no.first, botonDecision(ctx, no.second, 12))
            .build()
        mostrar(ctx, ID_PREGUNTA, n)
    }

    /**
     * Aviso fijo que dice quién manda en este momento (en Android 14 se puede deslizar,
     * pero vuelve a aparecer en la próxima revisión).
     * [colorArgb] pinta el ícono: verde automático, gris fuera de horario, amarillo manual.
     */
    fun estadoFijo(ctx: Context, titulo: String, texto: String, colorArgb: Int, manual: Boolean) {
        crearCanales(ctx)
        val b = NotificationCompat.Builder(ctx, CANAL_ESTADO)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(colorArgb)
            .setColorized(false)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(abrirApp(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (manual) {
            b.addAction(0, "Volver a automático", botonDecision(ctx, Motor.ACCION_AUTO, 13))
        }
        mostrar(ctx, ID_ESTADO, b.build())
    }

    fun quitarEstadoFijo(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(ID_ESTADO)
    }

    fun cancelarPregunta(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(ID_PREGUNTA)
    }
}

package com.manera.riegosolar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Trabajo que Android ejecuta en segundo plano cada 15 minutos. */
class RiegoWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        try {
            Motor.ejecutar(applicationContext)
        } catch (e: Exception) {
            Motor.registrar(applicationContext, "Error inesperado: ${e.message ?: e.javaClass.simpleName}")
        }
        return Result.success()
    }
}

object Programador {
    private const val PERIODICO = "riego-periodico"
    private const val YA = "riego-ya"

    private val conRed = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Programa la revisión cada 15 minutos (el mínimo que permite Android). */
    fun programar(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<RiegoWorker>(15, TimeUnit.MINUTES)
            .setConstraints(conRed)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODICO, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Revisa una vez dentro de [minutos] (para aplicar la opción si no contestaste). */
    fun enMinutos(ctx: Context, minutos: Long) {
        val req = OneTimeWorkRequestBuilder<RiegoWorker>()
            .setInitialDelay(minutos, TimeUnit.MINUTES)
            .setConstraints(conRed)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("riego-sin-respuesta", ExistingWorkPolicy.REPLACE, req)
    }

    /** Revisa una vez, ya mismo. */
    fun ahora(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<RiegoWorker>().setConstraints(conRed).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(YA, ExistingWorkPolicy.REPLACE, req)
    }
}

/** Recibe los toques en los botones de la notificación. */
class AccionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Notificaciones.ACCION_DECISION) return
        val decision = intent.getStringExtra(Notificaciones.EXTRA_DECISION) ?: return
        Motor.decidirManual(ctx, decision, fija = false)
        Programador.ahora(ctx)
    }
}

package app.flux.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.IBinder
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.flux.MainActivity
import app.flux.FluxApp
import app.flux.R
import kotlinx.coroutines.launch

/**
 * The reason Flux keeps running: a minimal foreground service (type `specialUse`), which Android
 * requires for a persistent overlay and which keeps One UI from reaping the process. It owns the
 * [OverlayWindowHost] and the [app.flux.engine.FluxEngine]; when it stops, everything stops.
 */
class FluxOverlayService : Service() {

    private var host: OverlayWindowHost? = null
    private val graph get() = (application as FluxApp).graph

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            graph.log("overlay permission missing; service stopping")
            stopSelf()
            return
        }
        graph.engine.start()
        host = OverlayWindowHost(this, graph).also { it.start() }
        graph.serviceRunning.value = true
        graph.log("overlay service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Remember that the user turned it off, so it does not come back on boot.
            graph.appScope.launch { graph.settingsRepo.update { it.copy(overlayEnabled = false) } }
            stopSelf()
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        host?.onConfigurationChanged()
    }

    override fun onDestroy() {
        host?.stop()
        host = null
        graph.engine.stop()
        graph.serviceRunning.value = false
        graph.log("overlay service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Flux is running", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Required by Android to keep the overlay available. You can hide this notification from its settings."
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FluxOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_flux)
            .setContentTitle("Flux")
            .setContentText("Overlay is on")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private companion object {
        const val CHANNEL_ID = "overlay_service"
        const val NOTIFICATION_ID = 4001
        const val ACTION_STOP = "app.flux.action.STOP_OVERLAY"
    }
}

object OverlayServiceControl {
    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, FluxOverlayService::class.java))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, FluxOverlayService::class.java))
    }
}

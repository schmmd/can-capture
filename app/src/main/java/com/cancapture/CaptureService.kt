package com.cancapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.cancapture.data.CaptureEngine
import com.cancapture.data.RecordUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while [CaptureEngine] records. Holds no capture
 * state itself: it starts the engine, mirrors its state into a notification,
 * and stops itself once the engine leaves Recording.
 */
class CaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine: CaptureEngine get() = (application as App).container.captureEngine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("Starting…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        engine.start()
        scope.launch {
            engine.state
                .takeWhile { it is RecordUiState.Recording }
                .conflate()
                .collect { s ->
                    val r = s as RecordUiState.Recording
                    val secs = r.elapsedMs / 1000
                    val text = "%d:%02d:%02d · %d frames".format(secs / 3600, secs / 60 % 60, secs % 60, r.frameCount)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
                    delay(NOTIFICATION_PERIOD_MS) // conflate + delay = at most one update per period
                }
            // Engine left Recording (stopped, errored, or all buses disconnected).
            ServiceCompat.stopForeground(this@CaptureService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // Android 14 caps dataSync services at 6h; stop cleanly so the capture
    // lands in PendingSave instead of dying with the process.
    override fun onTimeout(startId: Int) {
        engine.stop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Recording CAN")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_PERIOD_MS = 1000L
    }
}

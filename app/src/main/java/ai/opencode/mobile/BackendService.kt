package ai.opencode.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class BackendService : Service() {

    private val CHANNEL_ID = "opencode_backend"
    private val CHANNEL_TASK_ID = "opencode_task"
    private val NOTIFICATION_ID = 1
    private val NOTIFICATION_TASK_ID = 2

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundNotification("Backend running")
            ACTION_STOP -> stopSelf()
            ACTION_TASK_COMPLETE -> {
                val title = intent.getStringExtra("title") ?: "Task Complete"
                val text = intent.getStringExtra("text") ?: "Your task has finished"
                showTaskNotification(title, text)
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent.getStringExtra("status") ?: "Working..."
                updateForegroundNotification(status)
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification(status: String) {
        val notification = buildNotification(CHANNEL_ID, "OpenCode", status, ongoing = true)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateForegroundNotification(status: String) {
        val notification = buildNotification(CHANNEL_ID, "OpenCode", status, ongoing = true)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun showTaskNotification(title: String, text: String) {
        val notification = buildNotification(CHANNEL_TASK_ID, title, text, ongoing = false)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_TASK_ID, notification)
    }

    private fun buildNotification(channelId: String, title: String, text: String, ongoing: Boolean): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val backendChannel = NotificationChannel(
                CHANNEL_ID,
                "OpenCode Backend",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "OpenCode backend process"
            }

            val taskChannel = NotificationChannel(
                CHANNEL_TASK_ID,
                "OpenCode Tasks",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Task completion notifications"
                enableVibration(true)
            }

            manager.createNotificationChannel(backendChannel)
            manager.createNotificationChannel(taskChannel)
        }
    }

    companion object {
        const val ACTION_START = "ai.opencode.mobile.START_BACKEND"
        const val ACTION_STOP = "ai.opencode.mobile.STOP_BACKEND"
        const val ACTION_TASK_COMPLETE = "ai.opencode.mobile.TASK_COMPLETE"
        const val ACTION_UPDATE_STATUS = "ai.opencode.mobile.UPDATE_STATUS"
    }
}

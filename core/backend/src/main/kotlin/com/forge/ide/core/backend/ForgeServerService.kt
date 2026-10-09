package com.forge.ide.core.backend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Foreground service that owns the backend server.
 *
 * Running the server in a dedicated `:backend` process means an Activity death
 * (or an LMK reclaim of the UI process) never takes the server down. The
 * service is sticky so Android restarts it if it is killed; state that must
 * survive restarts is journaled on disk (milestone M1).
 *
 * minSdk is 26, so the framework Notification.Builder is used directly and the
 * module needs no androidx.core dependency.
 */
class ForgeServerService : LifecycleService() {

    private var server: EmbeddedServer<*, *>? = null
    private val token: String = newToken()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun startServer() {
        if (server != null) return
        val embedded = embeddedServer(CIO, host = LOCAL_HOST, port = 0) {
            forgeServerModule()
        }
        server = embedded
        lifecycleScope.launch {
            try {
                embedded.start(wait = false)
                val port = embedded.engine.resolvedConnectors().firstOrNull()?.port
                    ?: error("Ktor server did not bind a port")
                ForgeRuntime.onServerStarted(LOCAL_HOST, port, token)
            } catch (t: Throwable) {
                ForgeRuntime.onServerStopped()
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        server?.stop(gracePeriodMillis = 250, timeoutMillis = 1_000)
        server = null
        ForgeRuntime.onServerStopped()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = if (launchIntent != null) {
            PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE)
        } else {
            null
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.backend_notification_title))
            .setContentText(getString(R.string.backend_notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.backend_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.backend_notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val LOCAL_HOST = "127.0.0.1"
        private const val CHANNEL_ID = "forge_backend"
        private const val NOTIFICATION_ID = 1001

        fun newToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun start(context: Context) {
            val intent = Intent(context, ForgeServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

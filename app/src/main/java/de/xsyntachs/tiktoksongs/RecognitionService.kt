package de.xsyntachs.tiktoksongs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import kotlin.concurrent.thread

class RecognitionService : Service() {

    private val manager get() = getSystemService(NotificationManager::class.java)
    private var polling = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel_recognition), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val progress = build(getString(R.string.notif_recognizing), getString(R.string.notif_working), ongoing = true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, progress, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(1, progress)
        }
        if (!polling) {
            polling = true
            thread { poll() }
        }
        return START_NOT_STICKY
    }

    private fun poll() {
        var marker = runCatching {
            JSONObject(Api.songs()).getJSONArray("songs").optJSONObject(0)?.getString("saved_at")
        }.getOrNull() ?: ""
        var found = false
        val deadline = System.currentTimeMillis() + 150_000

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(3_000)
            val feed = runCatching { JSONObject(Api.songs()) }.getOrNull() ?: continue
            val newest = feed.getJSONArray("songs").optJSONObject(0)
            if (newest != null && newest.getString("saved_at") > marker) {
                marker = newest.getString("saved_at")
                found = true
                val source = when {
                    newest.optBoolean("recognized") -> getString(R.string.src_shazam)
                    newest.optBoolean("from_caption") -> getString(R.string.src_caption)
                    newest.optBoolean("original") -> getString(R.string.src_original_saved)
                    else -> getString(R.string.src_official)
                }
                manager.notify(2, build(newest.optString("name").ifBlank { getString(R.string.unknown_artist) },
                    "${newest.optString("artist").ifBlank { getString(R.string.unknown_artist) }} · $source"))
            }
            if (feed.getJSONArray("pending").length() == 0 && found) break
            if (feed.getJSONArray("pending").length() == 0 && !found &&
                System.currentTimeMillis() > deadline - 130_000) {
                manager.notify(2, build(getString(R.string.save_failed), getString(R.string.notif_link_failed)))
                break
            }
        }
        stopSelf()
    }

    private fun build(title: String, text: String, ongoing: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
    }

    companion object {
        private const val CHANNEL = "erkennung"
    }
}

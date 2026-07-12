package de.xsyntachs.tiktoksongs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.time.OffsetDateTime
import kotlin.concurrent.thread

class WeeklyDigestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val async = goAsync()
        thread {
            runCatching { notifyDigest(context) }
            async.finish()
        }
    }

    private fun notifyDigest(context: Context) {
        Api.load(context)
        val songs = JSONObject(Api.songs()).getJSONArray("songs")
        val limit = OffsetDateTime.now().minusDays(7)
        val week = (0 until songs.length()).map(songs::getJSONObject).filter {
            runCatching { OffsetDateTime.parse(it.getString("saved_at")).isAfter(limit) }.getOrDefault(false)
        }
        if (week.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("digest", "Wochen-Rückblick", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val style = Notification.InboxStyle()
        week.take(5).forEach { style.addLine("${it.optString("artist")} - ${it.optString("name")}") }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(3, Notification.Builder(context, "digest")
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle("Deine Woche")
            .setContentText("${week.size} neue Songs gesammelt")
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }
}

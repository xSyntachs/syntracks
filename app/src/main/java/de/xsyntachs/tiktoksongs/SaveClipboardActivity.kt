package de.xsyntachs.tiktoksongs

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import kotlin.concurrent.thread

class SaveClipboardActivity : Activity() {

    private var handled = false

    // Zwischenablage ist ab Android 10 erst mit Fenster-Fokus lesbar, nicht in onCreate
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        val clip = getSystemService(ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (!clip.contains("tiktok", ignoreCase = true)) {
            Toast.makeText(this, "Kein TikTok-Link in der Zwischenablage", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Api.load(this)
        val app = applicationContext
        thread {
            runCatching { Api.add(clip) }
                .onSuccess { app.startForegroundService(Intent(app, RecognitionService::class.java)) }
                .onFailure {
                    runOnUiThread { Toast.makeText(app, "Fehler: ${it.message}", Toast.LENGTH_LONG).show() }
                }
        }
        finish()
    }
}

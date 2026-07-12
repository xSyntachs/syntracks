package de.xsyntachs.tiktoksongs

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import kotlin.concurrent.thread

class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = intent.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        if (shared == null) {
            finish()
            return
        }
        Api.load(this)
        val app = applicationContext
        thread {
            runCatching { Api.add(shared) }
                .onSuccess {
                    app.startForegroundService(Intent(app, RecognitionService::class.java))
                }
                .onFailure {
                    val prefs = app.getSharedPreferences("queue", MODE_PRIVATE)
                    prefs.edit()
                        .putStringSet("pending", prefs.getStringSet("pending", emptySet())!! + shared)
                        .apply()
                    runOnUiThread {
                        Toast.makeText(app, "Offline, Song wird nachgereicht sobald Netz da ist", Toast.LENGTH_LONG).show()
                    }
                }
        }
        finish()
    }
}

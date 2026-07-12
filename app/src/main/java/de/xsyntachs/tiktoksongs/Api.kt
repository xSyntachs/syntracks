package de.xsyntachs.tiktoksongs

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Api {
    private const val BASE = "https://tiktok.xsyntachs.de"
    private var token: String? = null
    var user: String? = null
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
        token = prefs.getString("token", null)
        user = prefs.getString("user", null)
    }

    val loggedIn get() = token != null

    fun syncUser(context: Context, name: String) {
        if (name.isNotBlank() && name != user) {
            user = name
            context.getSharedPreferences("auth", Context.MODE_PRIVATE).edit().putString("user", name).apply()
        }
    }

    fun logout(context: Context) {
        context.getSharedPreferences("auth", Context.MODE_PRIVATE).edit().clear().apply()
        token = null
        user = null
    }

    /** wirft bei falschen Daten mit der Server-Meldung als message */
    fun register(context: Context, name: String, password: String) = auth(context, "/register", name, password)
    fun login(context: Context, name: String, password: String) = auth(context, "/login", name, password)

    private fun auth(context: Context, path: String, name: String, password: String) {
        val body = JSONObject().put("user", name).put("pass", password).toString()
        val answer = JSONObject(request("POST", path, body, withToken = false))
        token = answer.getString("token")
        user = answer.getString("user")
        context.getSharedPreferences("auth", Context.MODE_PRIVATE).edit()
            .putString("token", token).putString("user", user).apply()
    }

    fun add(sharedText: String) = request("POST", "/add", sharedText)
    fun songs() = request("GET", "/songs")
    fun delete(savedAt: String) = request("POST", "/delete", savedAt)
    fun similar(key: String) = request("GET", "/similar?id=$key")
    fun saveSimilar(trackJson: String) = request("POST", "/save-similar", trackJson)
    fun previewUrl(key: String) = "$BASE/preview?id=$key&token=$token"
    fun fullUrl(key: String) = "$BASE/full?id=$key&token=$token"
    fun downloadMp3Url(key: String) = "$BASE/download-mp3?id=$key&token=$token"
    fun downloadMp4Url(key: String) = "$BASE/download-mp4?id=$key&token=$token"

    fun rename(context: Context, newName: String): String {
        val answer = JSONObject(request("POST", "/rename", JSONObject().put("new", newName).toString()))
        user = answer.getString("user")
        context.getSharedPreferences("auth", Context.MODE_PRIVATE).edit().putString("user", user).apply()
        return user!!
    }

    fun changePassword(old: String, new: String) =
        request("POST", "/change-password", JSONObject().put("old", old).put("new", new).toString())

    fun adminUsers() = request("GET", "/admin/users")
    fun adminDeleteUser(name: String) = request("POST", "/admin/delete-user", name)
    fun adminResetPassword(name: String, new: String) =
        request("POST", "/admin/reset-password", JSONObject().put("user", name).put("new", new).toString())

    private fun request(method: String, path: String, body: String? = null, withToken: Boolean = true): String {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        if (withToken) token?.let { conn.setRequestProperty("X-Token", it) }
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        if (conn.responseCode >= 400) {
            val error = (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText() }
            throw RuntimeException(error.ifBlank { "Fehler ${conn.responseCode}" })
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}

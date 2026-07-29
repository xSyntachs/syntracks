package de.xsyntachs.tiktoksongs

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.time.DayOfWeek
import java.time.OffsetDateTime
import java.time.temporal.TemporalAdjusters

data class Song(
    val savedAt: String,
    val name: String,
    val artist: String,
    val url: String,
    val clip: String,
    val artwork: String?,
    val genre: String?,
    val favorite: Boolean,
    val source: Source,
) {
    enum class Source(val label: String, val filter: String) {
        TIKTOK("Offizieller Song", "Offiziell"),
        SHAZAM("Per Shazam erkannt", "Shazam"),
        CAPTION("Aus Caption gelesen", "Aus Caption"),
        SIMILAR("Empfehlung", "Empfehlungen"),
        ORIGINAL("Nicht erkannt", "Original"),
    }
}

data class SimilarTrack(
    val track: String,
    val artist: String,
    val preview: String?,
    val artwork: String?,
    val url: String?,
)

private fun parseSimilar(raw: String, key: String = "similar"): List<SimilarTrack> {
    val tracks = JSONObject(raw).getJSONArray(key)
    return (0 until tracks.length()).map { i ->
        val t = tracks.getJSONObject(i)
        SimilarTrack(
            track = t.optString("track", "Unbekannt"),
            artist = t.optString("artist", "Unbekannt"),
            preview = t.optString("preview").takeIf { it.isNotBlank() && it != "null" },
            artwork = t.optString("artwork").takeIf { it.isNotBlank() && it != "null" },
            url = t.optString("url").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}

data class Feed(val pending: Int, val pendingStage: String?, val admin: Boolean, val songs: List<Song>)

private fun parseFeed(raw: String): Feed {
    val root = JSONObject(raw)
    val songs = root.getJSONArray("songs")
    val pending = root.getJSONArray("pending")
    return Feed(
        pending = pending.length(),
        pendingStage = pending.optJSONObject(0)?.optString("stage")?.takeIf { it.isNotBlank() },
        admin = root.optBoolean("admin"),
        songs = (0 until songs.length()).map { i ->
            val s = songs.getJSONObject(i)
            Song(
                savedAt = s.getString("saved_at"),
                name = s.optString("name", "Unbekannt"),
                artist = s.optString("artist", "Unbekannt"),
                url = s.getString("url"),
                clip = s.optString("clip"),
                artwork = s.optString("artwork").takeIf { it.isNotBlank() && it != "null" },
                genre = s.optString("genre").takeIf { it.isNotBlank() && it != "null" },
                favorite = s.optBoolean("favorite"),
                source = when {
                    s.optBoolean("similar") -> Song.Source.SIMILAR
                    s.optBoolean("recognized") -> Song.Source.SHAZAM
                    s.optBoolean("from_caption") -> Song.Source.CAPTION
                    s.optBoolean("original") -> Song.Source.ORIGINAL
                    else -> Song.Source.TIKTOK
                },
            )
        },
    )
}

private fun relativeTime(savedAt: String): String = runCatching {
    DateUtils.getRelativeTimeSpanString(OffsetDateTime.parse(savedAt).toInstant().toEpochMilli()).toString()
}.getOrDefault(savedAt)

private fun thisWeek(songs: List<Song>): Int {
    val limit = OffsetDateTime.now().minusDays(7)
    return songs.count { runCatching { OffsetDateTime.parse(it.savedAt).isAfter(limit) }.getOrDefault(false) }
}

private fun topArtist(songs: List<Song>): String? =
    songs.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }?.key

val Pink = Color(0xFFFE2C55)
val Cyan = Color(0xFF25F4EE)
private val Violet = Color(0xFF7828C8)
private val Gold = Color(0xFFF5C542)

val Scheme = darkColorScheme(
    primary = Pink,
    secondary = Cyan,
    background = Color.Black,
    surface = Color(0xFF15151B),
    onBackground = Color(0xFFF4F4F8),
    onSurface = Color(0xFFF4F4F8),
    onSurfaceVariant = Color(0xFF9C9CA8),
)

private val GlassCard = Color.White.copy(alpha = 0.08f)

/** Lädt die aktuelle APK vom Server und öffnet den System-Installer (gleiche Signatur = Update) */
private fun downloadUpdate(context: Context) {
    val apk = java.io.File(context.cacheDir, "update.apk")
    URL(Api.apkUrl()).openStream().use { input -> apk.outputStream().use { input.copyTo(it) } }
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private val coverCache = HashMap<String, Bitmap?>()

@Composable
private fun rememberCover(url: String?): Bitmap? {
    var bitmap by remember(url) { mutableStateOf(url?.let(coverCache::get)) }
    LaunchedEffect(url) {
        if (url != null && !coverCache.containsKey(url)) {
            bitmap = withContext(Dispatchers.IO) {
                runCatching { URL(url).openStream().use(BitmapFactory::decodeStream) }.getOrNull()
            }.also { coverCache[url] = it }
        }
    }
    return bitmap
}

private class ClipPlayer {
    private var player: MediaPlayer? = null

    /** Clip-Key zu "lädt noch", null wenn nichts spielt */
    var state by mutableStateOf<Pair<String, Boolean>?>(null)
        private set

    var volume by mutableFloatStateOf(1f)
        private set

    fun changeVolume(value: Float) {
        volume = value
        runCatching { player?.setVolume(value, value) }
    }

    fun toggle(key: String, sourceUrl: String = Api.previewUrl(key)) {
        val wasPlaying = state?.first == key
        stop()
        if (wasPlaying) return
        play(key, sourceUrl)
    }

    fun play(key: String, sourceUrl: String) {
        stop()
        state = key to true
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(sourceUrl)
            setOnPreparedListener { it.setVolume(volume, volume); it.start(); state = key to false }
            setOnCompletionListener { stop() }
            setOnErrorListener { _, _, _ -> stop(); true }
            prepareAsync()
        }
    }

    fun seekTo(fraction: Float) {
        runCatching {
            player?.takeIf { it.duration > 0 }?.let { it.seekTo((it.duration * fraction).toInt()) }
        }
    }

    fun progress(): Float = runCatching {
        player?.takeIf { it.isPlaying && it.duration > 0 }
            ?.let { it.currentPosition.toFloat() / it.duration } ?: 0f
    }.getOrDefault(0f)

    fun stop() {
        player?.release()
        player = null
        state = null
    }
}

@Composable
private fun PauseGlyph(color: Color, size: androidx.compose.ui.unit.Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(size / 4)) {
        Box(Modifier.size(size / 3, size).background(color, RoundedCornerShape(1.dp)))
        Box(Modifier.size(size / 3, size).background(color, RoundedCornerShape(1.dp)))
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Scheme.onSurfaceVariant,
    )
}

private fun download(context: Context, url: String, filename: String, mime: String) {
    val safe = filename.replace(Regex("[<>:\"/\\\\|?*]"), "_")
    val request = android.app.DownloadManager.Request(Uri.parse(url))
        .setTitle(safe)
        .setMimeType(mime)
        .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, safe)
    context.getSystemService(android.app.DownloadManager::class.java).enqueue(request)
    android.widget.Toast.makeText(context, "Download gestartet…", android.widget.Toast.LENGTH_SHORT).show()
}

private fun searchYouTubeMusic(context: Context, song: Song) {
    val uri = Uri.parse("https://music.youtube.com/search?q=" + Uri.encode("${song.artist} ${song.name}"))
    val packages = listOf("app.revanced.android.apps.youtube.music", "com.google.android.apps.youtube.music", null)
    for (pkg in packages) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
}

class MainActivity : ComponentActivity() {

    private val clipboardSuggestion = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Api.load(this)
        setContent {
            MaterialTheme(colorScheme = Scheme) {
                var loggedIn by remember { mutableStateOf(Api.loggedIn) }
                if (!loggedIn) {
                    LoginScreen(onSuccess = {
                        loggedIn = true
                        publishShareShortcut()
                        scheduleWeeklyDigest()
                    })
                } else {
                    LaunchedEffect(Unit) {
                        publishShareShortcut()
                        scheduleWeeklyDigest()
                    }
                    AuroraScreen(
                        clipboardSuggestion = clipboardSuggestion.value,
                        onClipboardHandled = ::handleClipboard,
                        onLogout = { Api.logout(this@MainActivity); loggedIn = false },
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val clip = getSystemService(android.content.ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val prefs = getSharedPreferences("queue", MODE_PRIVATE)
        if (clip.contains("tiktok", ignoreCase = true) && clip != prefs.getString("last-clip", null)) {
            clipboardSuggestion.value = clip
        }
    }

    private fun handleClipboard(save: Boolean) {
        val clip = clipboardSuggestion.value ?: return
        clipboardSuggestion.value = null
        getSharedPreferences("queue", MODE_PRIVATE).edit().putString("last-clip", clip).apply()
        if (!save) return
        val app = applicationContext
        kotlin.concurrent.thread {
            runCatching { Api.add(clip) }
                .onSuccess { app.startForegroundService(Intent(app, RecognitionService::class.java)) }
        }
    }

    // ponytail: Registrierung bei jedem App-Start, nach einem Reboot greift sie erst beim nächsten Öffnen
    private fun scheduleWeeklyDigest() {
        // Aufräumen der abgeschafften Dauer-Benachrichtigung "Song speichern"
        getSystemService(android.app.NotificationManager::class.java).apply {
            cancel(4)
            deleteNotificationChannel("quick")
            deleteNotificationChannel("quick2")
        }
        val broadcast = PendingIntent.getBroadcast(this, 0, Intent(this, WeeklyDigestReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        var next = OffsetDateTime.now()
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            .withHour(18).withMinute(0).withSecond(0)
        if (next.isBefore(OffsetDateTime.now())) next = next.plusWeeks(1)
        getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.RTC, next.toInstant().toEpochMilli(), AlarmManager.INTERVAL_DAY * 7, broadcast)
    }

    private fun publishShareShortcut() {
        val shortcut = android.content.pm.ShortcutInfo.Builder(this, "save-song")
            .setShortLabel("Song speichern")
            .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setCategories(setOf("de.xsyntachs.tiktoksongs.SHARE"))
            .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
            .apply { if (Build.VERSION.SDK_INT >= 30) setLongLived(true) }
            .build()
        getSystemService(android.content.pm.ShortcutManager::class.java).addDynamicShortcuts(listOf(shortcut))
    }
}

@Composable
fun AuroraBackground(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val transition = rememberInfiniteTransition(label = "orbs")
    val drift by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .drawBehind {
                listOf(
                    Triple(Violet.copy(alpha = 0.32f),
                        Offset(size.width * (0.15f + drift * 0.25f), size.height * (0.05f + drift * 0.08f)),
                        size.width * 0.9f),
                    Triple(Pink.copy(alpha = 0.22f),
                        Offset(size.width * (0.95f - drift * 0.2f), size.height * (0.3f + drift * 0.12f)),
                        size.width * 0.8f),
                    Triple(Color(0xFF2564F4).copy(alpha = 0.20f),
                        Offset(size.width * (0.4f + drift * 0.2f), size.height * (0.95f - drift * 0.1f)),
                        size.width * 0.9f),
                ).forEach { (color, center, radius) ->
                    drawCircle(
                        Brush.radialGradient(listOf(color, Color.Transparent), center = center, radius = radius),
                        center = center, radius = radius,
                    )
                }
            }
    ) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AuroraScreen(clipboardSuggestion: String? = null, onClipboardHandled: (Boolean) -> Unit = {}, onLogout: () -> Unit = {}) {
    var feed by remember { mutableStateOf<Feed?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var sourceFilter by remember { mutableStateOf<Song.Source?>(null) }
    var view by remember { mutableStateOf("SONGS") }
    var playProgress by remember { mutableFloatStateOf(0f) }
    var similarFor by remember { mutableStateOf<Song?>(null) }
    var similarTracks by remember { mutableStateOf<List<SimilarTrack>?>(null) }
    var recs by remember { mutableStateOf<List<SimilarTrack>?>(null) }
    val savedRecs = remember { mutableStateListOf<String>() }
    var showStats by remember { mutableStateOf(false) }
    var updateAvailable by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    var latestVersion by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val hidden = remember { mutableStateListOf<String>() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipPlayer = remember { ClipPlayer() }
    val context = LocalContext.current

    DisposableEffect(Unit) {
        onDispose { clipPlayer.stop() }
    }
    LaunchedEffect(Unit) {
        clipPlayer.changeVolume(
            context.getSharedPreferences("queue", Context.MODE_PRIVATE).getFloat("volume", 1f)
        )
    }
    LaunchedEffect(tick) {
        try {
            val raw = withContext(Dispatchers.IO) { Api.songs() }
            Api.syncUser(context, JSONObject(raw).optString("user"))
            feed = parseFeed(raw)
            error = null
        } catch (e: Exception) {
            // Sitzung serverseitig beendet (Anmeldung auf einem anderen Gerät) -> zurück zum Login
            if (e.message == "Nicht angemeldet") {
                android.widget.Toast.makeText(context,
                    "Sitzung abgelaufen, bitte neu anmelden", android.widget.Toast.LENGTH_LONG).show()
                onLogout()
                return@LaunchedEffect
            }
            error = e.message ?: "Netzwerkfehler"
        }
    }
    LaunchedEffect(feed) {
        if ((feed?.pending ?: 0) > 0) {
            delay(5_000)
            tick++
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(12_000)
            tick++
        }
    }
    LaunchedEffect(clipPlayer.state) {
        playProgress = 0f
        while (clipPlayer.state?.second == false) {
            playProgress = clipPlayer.progress()
            delay(200)
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("queue", Context.MODE_PRIVATE)
            val queued = prefs.getStringSet("pending", emptySet())!!.toMutableSet()
            if (queued.isNotEmpty()) {
                val sent = queued.filter { runCatching { Api.add(it) }.isSuccess }
                queued.removeAll(sent.toSet())
                prefs.edit().putStringSet("pending", queued).apply()
                if (sent.isNotEmpty()) tick++
            }
        }
    }
    LaunchedEffect(Unit) {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        runCatching {
            val server = withContext(Dispatchers.IO) { Api.appVersion() }
            latestVersion = server.optString("versionName")
            updateAvailable = server.getInt("versionCode") > installed
        }
    }
    LaunchedEffect(similarFor) {
        similarTracks = null
        similarFor?.let { song ->
            similarTracks = runCatching {
                withContext(Dispatchers.IO) { parseSimilar(Api.similar(song.clip)) }
            }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(view) {
        if (view == "RECS" && recs == null) {
            recs = runCatching {
                withContext(Dispatchers.IO) { parseSimilar(Api.recommendations(), "recommendations") }
            }.getOrDefault(emptyList())
        }
    }

    val songs = feed?.songs.orEmpty().filter { it.savedAt !in hidden }
    // Spotify-Prinzip: Songs / Favoriten / Empfehlungen als Bereiche, der Filter regelt nur Quellen
    val shown = if (view == "RECS") emptyList() else songs.filter { song ->
        when {
            view == "FAV" -> song.favorite
            song.source == Song.Source.SIMILAR -> false
            sourceFilter != null -> song.source == sourceFilter
            else -> true
        } && (query.isBlank() || song.name.contains(query, true) || song.artist.contains(query, true))
    }

    fun deleteWithUndo(song: Song) {
        hidden.add(song.savedAt)
        scope.launch {
            // Mit actionLabel wäre die Default-Dauer Indefinite, dann läuft das Löschen nie
            val result = snackbar.showSnackbar("Song gelöscht", actionLabel = "Rückgängig",
                duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) {
                hidden.remove(song.savedAt)
            } else {
                withContext(Dispatchers.IO) { runCatching { Api.delete(song.savedAt) } }
                hidden.remove(song.savedAt)
                tick++
            }
        }
    }

    AuroraBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Syntracks", fontSize = 30.sp, fontWeight = FontWeight.Black,
                        color = Scheme.onBackground)
                    Api.user?.let { Text("@$it", fontSize = 12.sp, color = Scheme.onSurfaceVariant) }
                }
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Suche", tint = Scheme.onSurfaceVariant)
                }
                Box {
                    var accountMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { accountMenu = true }) {
                        Icon(Icons.Default.AccountCircle, "Konto", tint = Scheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Name ändern") },
                            onClick = { accountMenu = false; dialog = "rename" },
                        )
                        DropdownMenuItem(
                            text = { Text("Passwort ändern") },
                            onClick = { accountMenu = false; dialog = "password" },
                        )
                        if (feed?.admin == true) {
                            DropdownMenuItem(
                                text = { Text("Konten verwalten") },
                                onClick = { accountMenu = false; dialog = "admin" },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Abmelden") },
                            onClick = { accountMenu = false; onLogout() },
                        )
                        val installedVersion = remember {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        }
                        Text(
                            when {
                                latestVersion.isNullOrBlank() -> "Version $installedVersion"
                                updateAvailable -> "Version $installedVersion · Neueste $latestVersion"
                                else -> "Version $installedVersion (aktuell)"
                            },
                            fontSize = 11.sp, color = Scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFF0E7490), Violet)))
                        .clickable { showStats = true }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text("Dein Geschmack", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                if (view == "SONGS") {
                    Box {
                        var filterMenu by remember { mutableStateOf(false) }
                        SourceChip("Filter: ${sourceFilter?.filter ?: "Alle"}", sourceFilter != null) { filterMenu = true }
                        DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                            DropdownMenuItem(text = { Text("Alle") },
                                onClick = { filterMenu = false; sourceFilter = null })
                            Song.Source.entries.filter { it != Song.Source.SIMILAR }.forEach { source ->
                                DropdownMenuItem(text = { Text(source.filter) },
                                    onClick = { filterMenu = false; sourceFilter = source })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                listOf("SONGS" to "Song Verlauf", "FAV" to "Favoriten", "RECS" to "Empfehlungen").forEach { (key, label) ->
                    Column(
                        Modifier.clickable { view = key },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = if (view == key) Scheme.onBackground else Scheme.onSurfaceVariant)
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.height(2.dp).width(28.dp)
                            .background(if (view == key) Pink else Color.Transparent, RoundedCornerShape(1.dp)))
                    }
                }
            }
            AnimatedVisibility(updateAvailable, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.horizontalGradient(listOf(Pink.copy(alpha = .22f), Violet.copy(alpha = .22f))))
                        .border(1.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Neue Version verfügbar", fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp, color = Scheme.onBackground)
                        Text("Lädt die aktuelle App direkt vom Server", fontSize = 12.sp,
                            color = Scheme.onSurfaceVariant)
                    }
                    if (updating) CircularProgressIndicator(Modifier.size(18.dp), color = Cyan, strokeWidth = 2.dp)
                    else ActionPill("Aktualisieren", Cyan) {
                        updating = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { runCatching { downloadUpdate(context) }.isSuccess }
                            updating = false
                            if (!ok) snackbar.showSnackbar("Update-Download fehlgeschlagen")
                        }
                    }
                }
            }
            AnimatedVisibility(searching, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    placeholder = { Text("Song oder Artist suchen…") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cyan,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                    ),
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.28f)),
            ) {
                SongList(
                    clipboardSuggestion = clipboardSuggestion,
                    onClipboardHandled = { save -> onClipboardHandled(save); if (save) tick++ },
                    error = error,
                    feed = feed,
                    shown = shown,
                    recs = recs,
                    savedRecs = savedRecs,
                    showRecs = view == "RECS",
                    clipPlayer = clipPlayer,
                    playProgress = playProgress,
                    filtered = query.isNotBlank() || sourceFilter != null,
                    onSeek = { playProgress = it; clipPlayer.seekTo(it) },
                    onVolume = {
                        clipPlayer.changeVolume(it)
                        context.getSharedPreferences("queue", Context.MODE_PRIVATE)
                            .edit().putFloat("volume", it).apply()
                    },
                    onPlayFull = { song -> clipPlayer.play(song.clip, Api.fullUrl(song.clip)) },
                    onSimilar = { similarFor = it },
                    onFavorite = { song ->
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { Api.favorite(song.savedAt, !song.favorite) }.isSuccess
                            }
                            android.widget.Toast.makeText(context,
                                when {
                                    !ok -> "Speichern fehlgeschlagen"
                                    song.favorite -> "Aus Favoriten entfernt"
                                    else -> "In deine Favoriten gespeichert"
                                }, android.widget.Toast.LENGTH_SHORT).show()
                            tick++
                        }
                    },
                    onDelete = { deleteWithUndo(it) },
                    onSaveRec = { track ->
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching {
                                    Api.saveSimilar(JSONObject().apply {
                                        put("track", track.track)
                                        put("artist", track.artist)
                                        put("preview", track.preview)
                                        put("artwork", track.artwork)
                                        put("url", track.url)
                                    }.toString())
                                }.isSuccess
                            }
                            if (ok) {
                                savedRecs.add(track.track + track.artist)
                                tick++
                            }
                            android.widget.Toast.makeText(context,
                                if (ok) "In deine Favoriten gespeichert" else "Speichern fehlgeschlagen",
                                android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        similarFor?.let { seed ->
            SimilarOverlay(
                seed = seed,
                tracks = similarTracks,
                clipPlayer = clipPlayer,
                onSaved = { tick++ },
                onClose = { similarFor = null },
            )
        }
        if (showStats) {
            StatsOverlay(songs = songs, onClose = { showStats = false })
        }
        when (dialog) {
            "rename" -> RenameDialog(onClose = { dialog = null }, onDone = { tick++ })
            "password" -> PasswordDialog(onClose = { dialog = null })
            "admin" -> AdminOverlay(onClose = { dialog = null })
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
private fun SongList(
    clipboardSuggestion: String?,
    onClipboardHandled: (Boolean) -> Unit,
    error: String?,
    feed: Feed?,
    shown: List<Song>,
    recs: List<SimilarTrack>?,
    savedRecs: List<String>,
    showRecs: Boolean,
    clipPlayer: ClipPlayer,
    playProgress: Float,
    filtered: Boolean,
    onSeek: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onPlayFull: (Song) -> Unit,
    onSimilar: (Song) -> Unit,
    onFavorite: (Song) -> Unit,
    onDelete: (Song) -> Unit,
    onSaveRec: (SimilarTrack) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        clipboardSuggestion?.let {
            item {
                GlassBox {
                    Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("TikTok-Link in der Zwischenablage", Modifier.weight(1f), fontSize = 14.sp)
                        Surface(
                            color = Cyan.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(99.dp),
                            modifier = Modifier.clickable { onClipboardHandled(true) },
                        ) {
                            Text("Speichern", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                fontSize = 13.sp, color = Cyan)
                        }
                        IconButton(onClick = { onClipboardHandled(false) }) {
                            Icon(Icons.Default.Close, "Verwerfen", tint = Scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        error?.let {
            item {
                GlassBox { Text(it, Modifier.padding(16.dp), color = Pink) }
            }
        }
        if ((feed?.pending ?: 0) > 0) {
            item { PendingCard(feed!!.pending, feed!!.pendingStage) }
        }
        if (showRecs) {
            item {
                GlassBox {
                    Column(Modifier.padding(16.dp)) {
                        Text("Für dich", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Auf Basis deiner letzten Songs", fontSize = 12.sp, color = Scheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        when {
                            recs == null -> Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), color = Cyan, strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Empfehlungen werden gesucht…", color = Scheme.onSurfaceVariant, fontSize = 13.sp)
                            }
                            recs.isEmpty() -> Text("Gerade keine neuen Empfehlungen.",
                                color = Scheme.onSurfaceVariant, fontSize = 13.sp)
                            else -> recs.forEach { track ->
                                SimilarRow(
                                    track = track,
                                    playing = clipPlayer.state?.takeIf { it.first == track.preview }?.second,
                                    alreadySaved = track.track + track.artist in savedRecs,
                                    onPlay = { track.preview?.let { clipPlayer.toggle(it, it) } },
                                    onSave = { onSaveRec(track) },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (feed != null && shown.isEmpty() && feed.pending == 0 && !showRecs) {
            item { EmptyState(filtered) }
        }
        items(shown, key = { it.savedAt }) { song ->
            Box(Modifier.animateItem()) {
                SongCard(
                    song = song,
                    buffering = clipPlayer.state?.takeIf { it.first == song.clip }?.second,
                    progress = playProgress,
                    volume = clipPlayer.volume,
                    onVolume = onVolume,
                    onSeek = onSeek,
                    onPlay = { clipPlayer.toggle(song.clip) },
                    onPlayFull = { onPlayFull(song) },
                    onSimilar = { onSimilar(song) },
                    onFavorite = { onFavorite(song) },
                    onDelete = { onDelete(song) },
                )
            }
        }
    }
}

@Composable
private fun OverlayFrame(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)).clickable(onClick = onClose),
    ) {
        Card(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(16.dp)
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF17171E), contentColor = Scheme.onSurface),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, "Schließen", tint = Scheme.onSurfaceVariant)
                    }
                }
                content()
            }
        }
    }
}

@Composable
private fun SimilarOverlay(
    seed: Song,
    tracks: List<SimilarTrack>?,
    clipPlayer: ClipPlayer,
    onSaved: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val saved = remember { mutableStateListOf<String>() }
    val context = LocalContext.current
    OverlayFrame("Ähnlich zu ${seed.name}", onClose) {
        when {
            tracks == null -> Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Cyan, strokeWidth = 2.5.dp)
                Spacer(Modifier.width(12.dp))
                Text("Suche ähnliche Songs…", color = Scheme.onSurfaceVariant)
            }
            tracks.isEmpty() -> Text("Nichts gefunden.", Modifier.padding(vertical = 24.dp), color = Scheme.onSurfaceVariant)
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().height(420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(tracks, key = { it.preview ?: it.track + it.artist }) { track ->
                    SimilarRow(
                        track = track,
                        playing = clipPlayer.state?.takeIf { it.first == track.preview }?.second,
                        alreadySaved = track.track + track.artist in saved,
                        onPlay = { track.preview?.let { clipPlayer.toggle(it, it) } },
                        onSave = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        Api.saveSimilar(JSONObject().apply {
                                            put("track", track.track)
                                            put("artist", track.artist)
                                            put("preview", track.preview)
                                            put("artwork", track.artwork)
                                            put("url", track.url)
                                        }.toString())
                                    }
                                }
                                if (result.isSuccess) {
                                    saved.add(track.track + track.artist)
                                    android.widget.Toast.makeText(context, "In deine Favoriten gespeichert", android.widget.Toast.LENGTH_SHORT).show()
                                    onSaved()
                                } else {
                                    android.widget.Toast.makeText(context, "Speichern fehlgeschlagen", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SimilarRow(track: SimilarTrack, playing: Boolean?, alreadySaved: Boolean, onPlay: () -> Unit, onSave: () -> Unit) {
    val cover = rememberCover(track.artwork)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(11.dp))
                .background(Cyan.copy(alpha = 0.15f)).clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            cover?.let { Image(it.asImageBitmap(), "Cover", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (cover != null) 0.35f else 0f)))
            when (playing) {
                null -> Icon(Icons.Default.PlayArrow, "Anhören", tint = Color.White, modifier = Modifier.size(22.dp))
                true -> CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                false -> PauseGlyph(Color.White, 12.dp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(track.track, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, fontSize = 12.sp, color = Scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onSave, enabled = !alreadySaved) {
            Icon(
                if (alreadySaved) Icons.Default.Check else Icons.Default.Add,
                "Merken",
                tint = if (alreadySaved) Color(0xFF4CD964) else Cyan,
            )
        }
    }
}

@Composable
private fun DialogField(value: String, onChange: (String) -> Unit, label: String, password: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Cyan,
            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    )
}

@Composable
private fun DialogButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(top = 14.dp).height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(Brush.horizontalGradient(listOf(Pink, Cyan)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun RenameDialog(onClose: () -> Unit, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    OverlayFrame("Name ändern", onClose) {
        DialogField(name, { name = it.trim(); error = null }, "Neuer Benutzername")
        error?.let { Text(it, color = Pink, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        DialogButton("Speichern") {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { Api.rename(context, name) } }
                    .onSuccess {
                        android.widget.Toast.makeText(context, "Umbenannt in @$it", android.widget.Toast.LENGTH_SHORT).show()
                        onDone(); onClose()
                    }
                    .onFailure { error = it.message }
            }
        }
    }
}

@Composable
private fun PasswordDialog(onClose: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    OverlayFrame("Passwort ändern", onClose) {
        DialogField(old, { old = it; error = null }, "Aktuelles Passwort", password = true)
        DialogField(new, { new = it; error = null }, "Neues Passwort", password = true)
        DialogField(confirm, { confirm = it; error = null }, "Neues Passwort bestätigen", password = true)
        error?.let { Text(it, color = Pink, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        DialogButton("Ändern") {
            if (new != confirm) {
                error = "Passwörter stimmen nicht überein"
                return@DialogButton
            }
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { Api.changePassword(old, new) } }
                    .onSuccess {
                        android.widget.Toast.makeText(context, "Passwort geändert", android.widget.Toast.LENGTH_SHORT).show()
                        onClose()
                    }
                    .onFailure { error = it.message }
            }
        }
    }
}

@Composable
private fun AdminOverlay(onClose: () -> Unit) {
    var users by remember { mutableStateOf<List<Triple<String, Boolean, Int>>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var resetFor by remember { mutableStateOf<String?>(null) }
    var viewFor by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        users = runCatching {
            withContext(Dispatchers.IO) {
                val arr = JSONObject(Api.adminUsers()).getJSONArray("users")
                (0 until arr.length()).map {
                    val u = arr.getJSONObject(it)
                    Triple(u.getString("name"), u.optBoolean("admin"), u.optInt("songs"))
                }
            }
        }.getOrDefault(emptyList())
    }

    viewFor?.let { target ->
        UserLibraryOverlay(target, onClose = { viewFor = null })
        return
    }

    resetFor?.let { target ->
        var new by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        OverlayFrame("Passwort für @$target", onClose = { resetFor = null }) {
            DialogField(new, { new = it; error = null }, "Neues Passwort", password = true)
            DialogField(confirm, { confirm = it; error = null }, "Neues Passwort bestätigen", password = true)
            error?.let { Text(it, color = Pink, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            DialogButton("Zurücksetzen") {
                if (new != confirm) {
                    error = "Passwörter stimmen nicht überein"
                    return@DialogButton
                }
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { Api.adminResetPassword(target, new) } }
                    android.widget.Toast.makeText(context, "Zurückgesetzt", android.widget.Toast.LENGTH_SHORT).show()
                    resetFor = null
                }
            }
        }
        return
    }

    OverlayFrame("Konten verwalten", onClose) {
        when (val list = users) {
            null -> Row(Modifier.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Cyan, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Lädt…", color = Scheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxWidth().height(440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.first }) { (name, admin, count) ->
                    AdminRow(
                        avatar = { Text(name.first().uppercase(), fontWeight = FontWeight.Bold, color = Color.White) },
                        avatarBrush = Brush.linearGradient(listOf(Pink, Violet)),
                        title = if (admin) "@$name · Admin" else "@$name",
                        titleColor = if (admin) Cyan else Scheme.onSurface,
                        subtitle = "$count Songs",
                    ) {
                        ActionPill("Ansehen", Cyan) { viewFor = name }
                        ActionPill("Passwort", Scheme.onSurface) { resetFor = name }
                        if (name != Api.user) {
                            ActionPill("Löschen", Pink) {
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { Api.adminDeleteUser(name) } }
                                    reload++
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminRow(
    avatar: @Composable () -> Unit,
    avatarBrush: Brush,
    title: String,
    titleColor: Color,
    subtitle: String,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(avatarBrush), contentAlignment = Alignment.Center) {
                avatar()
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = titleColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 12.sp, color = Scheme.onSurfaceVariant)
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
        ) { actions() }
    }
}

@Composable
private fun ActionPill(label: String, color: Color, onClick: () -> Unit) {
    Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
private fun UserLibraryOverlay(user: String, onClose: () -> Unit) {
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var recs by remember { mutableStateOf<List<SimilarTrack>?>(null) }
    var tab by remember { mutableStateOf("VERLAUF") }

    LaunchedEffect(user) {
        songs = runCatching {
            withContext(Dispatchers.IO) { parseFeed(Api.adminUserSongs(user)).songs }
        }.getOrDefault(emptyList())
    }
    LaunchedEffect(tab) {
        if (tab == "RECS" && recs == null) {
            recs = runCatching {
                withContext(Dispatchers.IO) { parseSimilar(Api.adminUserRecommendations(user), "recommendations") }
            }.getOrDefault(emptyList())
        }
    }

    OverlayFrame("Sammlung von @$user", onClose) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            listOf("VERLAUF" to "Song Verlauf", "FAV" to "Favoriten", "RECS" to "Empfehlungen").forEach { (key, label) ->
                Text(label, fontSize = 13.sp,
                    fontWeight = if (tab == key) FontWeight.Bold else FontWeight.Medium,
                    color = if (tab == key) Scheme.onSurface else Scheme.onSurfaceVariant,
                    modifier = Modifier.clickable { tab = key })
            }
        }
        LazyColumn(Modifier.fillMaxWidth().height(440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tab == "RECS") {
                val list = recs.orEmpty()
                if (list.isEmpty()) item { Text("Keine Empfehlungen", color = Scheme.onSurfaceVariant, fontSize = 13.sp) }
                items(list) { t -> LibraryRow(t.artwork, t.track, t.artist, null, favorite = false) }
            } else {
                val list = songs
                val shown = if (tab == "FAV") list.orEmpty().filter { it.favorite } else list.orEmpty()
                if (list != null && shown.isEmpty()) item { Text("Nichts vorhanden", color = Scheme.onSurfaceVariant, fontSize = 13.sp) }
                items(shown, key = { it.savedAt }) { s -> LibraryRow(s.artwork, s.name, s.artist, s.source.label, s.favorite) }
            }
        }
    }
}

@Composable
private fun LibraryRow(artwork: String?, name: String, artist: String, badge: String?, favorite: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val cover = rememberCover(artwork)
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.07f))) {
            cover?.let { Image(it.asImageBitmap(), "Cover", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Scheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (favorite) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Star, "Favorit", tint = Gold, modifier = Modifier.size(14.dp))
                }
            }
            Text(listOfNotNull(artist.takeIf { it.isNotBlank() }, badge).joinToString(" · "),
                fontSize = 12.sp, color = Scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsOverlay(songs: List<Song>, onClose: () -> Unit) {
    OverlayFrame("Dein Geschmack", onClose) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)) {
            StatChip("${songs.size} Songs")
            StatChip("${thisWeek(songs)} diese Woche")
            StatChip("${songs.count { it.favorite }} Favoriten")
            topArtist(songs)?.let { top -> StatChip("Top $top") }
        }
        val genres = songs.mapNotNull { it.genre }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
        val artists = songs.groupingBy { it.artist }.eachCount()
            .entries.sortedByDescending { it.value }.take(3)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (genres.isEmpty()) {
                Text("Noch keine Genre-Daten. Genres kommen automatisch bei jedem erkannten Song dazu.",
                    color = Scheme.onSurfaceVariant, fontSize = 13.sp)
            } else {
                val max = genres.first().value
                genres.take(6).forEach { (genre, count) ->
                    Column {
                        Row {
                            Text(genre, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text("$count", fontSize = 13.sp, color = Scheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.08f))) {
                            Box(Modifier.fillMaxWidth(count.toFloat() / max).height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Brush.horizontalGradient(listOf(Pink, Violet))))
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Top-Artists", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            artists.forEach { (artist, count) ->
                Row {
                    Text(artist, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(if (count == 1) "1 Song" else "$count Songs", fontSize = 13.sp, color = Scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        shape = RoundedCornerShape(99.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = GlassCard,
            labelColor = Scheme.onSurfaceVariant,
            selectedContainerColor = Pink.copy(alpha = 0.35f),
            selectedLabelColor = Color.White,
        ),
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (selected) 0.3f else 0.12f)),
    )
}

@Composable
private fun StatChip(text: String) {
    Surface(
        color = GlassCard,
        shape = RoundedCornerShape(99.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 12.sp, color = Scheme.onSurfaceVariant)
    }
}

@Composable
private fun GlassBox(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = GlassCard, contentColor = Scheme.onSurface),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) { content() }
}

private val STAGE_PROGRESS = mapOf(
    "waiting" to 0.1f,
    "loading_video" to 0.4f,
    "identifying" to 0.75f,
    "saving" to 0.95f,
)

private val STAGE_LABEL = mapOf(
    "waiting" to "Wartet",
    "loading_video" to "Video wird geladen",
    "identifying" to "Song wird erkannt",
    "saving" to "Wird gespeichert",
)

@Composable
private fun PendingCard(count: Int, stage: String?) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.55f, 1f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    GlassBox {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.alpha(pulse), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Cyan, strokeWidth = 2.5.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        if (count == 1) "1 Song wird verarbeitet…" else "$count Songs werden verarbeitet…",
                        color = Cyan,
                        fontWeight = FontWeight.Medium,
                    )
                    stage?.let { key ->
                        Text(STAGE_LABEL[key] ?: key, fontSize = 12.sp, color = Scheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.1f)),
            ) {
                Box(
                    Modifier.fillMaxWidth(STAGE_PROGRESS[stage] ?: 0.1f).height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Cyan),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(filtered: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (filtered) "Nichts gefunden." else "Noch keine Songs.\nTeile ein TikTok-Video mit dieser App.",
            color = Scheme.onSurfaceVariant,
            fontSize = 14.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlimSlider(value: Float, onChange: (Float) -> Unit, thumbColor: Color, trackColor: Color) {
    Slider(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().height(22.dp),
        thumb = {
            Box(Modifier.size(12.dp).background(thumbColor, CircleShape))
        },
        track = { state ->
            Box(
                Modifier.fillMaxWidth().height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.12f)),
            ) {
                Box(
                    Modifier.fillMaxWidth(state.value.coerceIn(0f, 1f)).height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(trackColor),
                )
            }
        },
    )
}

@Composable
private fun CoverButton(song: Song, buffering: Boolean?, accent: Color, onPlay: () -> Unit) {
    val cover = rememberCover(song.artwork)
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(accent.copy(alpha = 0.2f))
            .clickable(onClick = onPlay),
        contentAlignment = Alignment.Center,
    ) {
        cover?.let {
            Image(it.asImageBitmap(), "Cover", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (cover != null) 0.35f else 0f)))
        Crossfade(buffering, label = "playState") { state ->
            when (state) {
                null -> Icon(Icons.Default.PlayArrow, "Anhören",
                    tint = if (cover != null) Color.White else accent, modifier = Modifier.size(26.dp))
                true -> CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                false -> PauseGlyph(Color.White, 15.dp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongCard(song: Song, buffering: Boolean?, progress: Float, volume: Float, onVolume: (Float) -> Unit, onSeek: (Float) -> Unit, onPlay: () -> Unit, onPlayFull: () -> Unit, onSimilar: () -> Unit, onFavorite: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val accent = when (song.source) {
        Song.Source.SHAZAM -> Violet
        Song.Source.CAPTION -> Gold
        Song.Source.SIMILAR -> Color(0xFF4CD964)
        Song.Source.ORIGINAL -> Scheme.onSurfaceVariant
        Song.Source.TIKTOK -> Pink
    }

    GlassBox {
        Column {
            Row(
                modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverButton(song, buffering, accent) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPlay()
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artist,
                        fontSize = 13.sp,
                        color = Scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = accent.copy(alpha = 0.28f), shape = RoundedCornerShape(99.dp)) {
                            Text(
                                song.source.label,
                                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.9f),
                            )
                        }
                        if (song.favorite) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.Star, "Favorit", tint = Gold, modifier = Modifier.size(13.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(relativeTime(song.savedAt), fontSize = 11.sp, color = Scheme.onSurfaceVariant)
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, "Aktionen", tint = Scheme.onSurfaceVariant)
                    }
                    // Menüpunkte nur wo sie Sinn ergeben: Empfehlungen haben kein TikTok-Video,
                    // unerkannte Original-Sounds haben keinen Songnamen und kein "ganzes Lied"
                    val isOriginal = song.source == Song.Source.ORIGINAL
                    val isSimilar = song.source == Song.Source.SIMILAR
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        MenuHeader("Abspielen")
                        if (!isOriginal) {
                            DropdownMenuItem(
                                text = { Text("Ganzen Song abspielen") },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                                onClick = { menuOpen = false; onPlayFull() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (isSimilar) "Auf Deezer öffnen" else "TikTok öffnen") },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                            onClick = {
                                menuOpen = false
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(song.url)))
                            },
                        )

                        MenuHeader("Sammlung")
                        DropdownMenuItem(
                            text = { Text(if (song.favorite) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen") },
                            leadingIcon = { Icon(Icons.Default.Star, null) },
                            onClick = { menuOpen = false; onFavorite() },
                        )

                        if (!isOriginal) {
                            MenuHeader("Entdecken")
                            DropdownMenuItem(
                                text = { Text("Ähnliche Songs") },
                                leadingIcon = { Icon(Icons.Default.Add, null) },
                                onClick = { menuOpen = false; onSimilar() },
                            )
                            DropdownMenuItem(
                                text = { Text("Auf Spotify suchen") },
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                onClick = {
                                    menuOpen = false
                                    val q = Uri.encode("${song.artist} ${song.name}")
                                    context.startActivity(Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://open.spotify.com/search/$q")))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Auf YouTube Music suchen") },
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                onClick = { menuOpen = false; searchYouTubeMusic(context, song) },
                            )
                        }

                        MenuHeader("Herunterladen")
                        DropdownMenuItem(
                            text = { Text(if (isOriginal) "Sound als MP3" else "Song als MP3") },
                            leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
                            onClick = {
                                menuOpen = false
                                download(context, Api.downloadMp3Url(song.clip),
                                    "${song.artist} - ${song.name}.mp3", "audio/mpeg")
                            },
                        )
                        if (!isSimilar) {
                            DropdownMenuItem(
                                text = { Text("TikTok-Video als MP4") },
                                leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
                                onClick = {
                                    menuOpen = false
                                    download(context, Api.downloadMp4Url(song.clip),
                                        "${song.artist} - ${song.name}.mp4", "video/mp4")
                                },
                            )
                        }

                        MenuHeader("Teilen")
                        DropdownMenuItem(
                            text = { Text("Namen kopieren") },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = {
                                menuOpen = false
                                clipboard.setText(AnnotatedString("${song.artist} - ${song.name}"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Song teilen") },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = {
                                menuOpen = false
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "${song.artist} - ${song.name}\n${song.url}")
                                }
                                context.startActivity(Intent.createChooser(send, "Song teilen"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Löschen") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
            AnimatedVisibility(buffering == false, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    SlimSlider(progress, onSeek, accent, accent)
                    Box(Modifier.fillMaxWidth(0.45f)) {
                        SlimSlider(volume, onVolume, Color.White, Color.White.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

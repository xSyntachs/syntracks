package de.xsyntachs.tiktoksongs

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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

fun parseSimilar(raw: String, key: String = "similar"): List<SimilarTrack> {
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

fun parseFeed(raw: String): Feed {
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

class ClipPlayer {
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                tick++
                delay(12_000)
            }
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
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brand)
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                Text("Syntracks", fontSize = 27.sp, fontWeight = FontWeight.Black,
                    color = Ink, modifier = Modifier.weight(1f))
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Suche", tint = Ink)
                }
                Box {
                    var accountMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { accountMenu = true }) {
                        Icon(Icons.Default.AccountCircle, "Konto", tint = Ink)
                    }
                    DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                        Text(
                            "@${Api.user ?: ""} · ${feed?.songs?.size ?: 0} Songs",
                            fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = Scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
                        )
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
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(SolidColor(Brand))
                        .clickable { showStats = true }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text("Dein Geschmack", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Ink)
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(horizontal = 14.dp),
            ) {
                listOf("SONGS" to "Song Verlauf", "FAV" to "Favoriten", "RECS" to "Empfehlungen").forEach { (key, label) ->
                    Column(
                        Modifier.clickable { view = key },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = if (view == key) Scheme.onBackground else Scheme.onSurfaceVariant)
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.height(2.dp).width(28.dp)
                            .background(if (view == key) Brand else Color.Transparent, RoundedCornerShape(1.dp)))
                    }
                }
            }
            AnimatedVisibility(updateAvailable, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(SolidColor(RowSurface))
                        .border(1.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(5.dp))
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
                    shape = RoundedCornerShape(5.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cyan,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                    ),
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp)) {
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

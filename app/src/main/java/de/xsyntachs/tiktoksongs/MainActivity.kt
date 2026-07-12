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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
    val source: Source,
) {
    enum class Source(val label: String, val filter: String) {
        TIKTOK("Offizieller Song", "Offiziell"),
        SHAZAM("Per Shazam erkannt", "Shazam"),
        CAPTION("Aus Video-Text gelesen", "Video-Text"),
        SIMILAR("Empfehlung", "Empfehlung"),
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

private fun parseSimilar(raw: String): List<SimilarTrack> {
    val tracks = JSONObject(raw).getJSONArray("similar")
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
    var playProgress by remember { mutableFloatStateOf(0f) }
    var similarFor by remember { mutableStateOf<Song?>(null) }
    var similarTracks by remember { mutableStateOf<List<SimilarTrack>?>(null) }
    var showStats by remember { mutableStateOf(false) }
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
    LaunchedEffect(similarFor) {
        similarTracks = null
        similarFor?.let { song ->
            similarTracks = runCatching {
                withContext(Dispatchers.IO) { parseSimilar(Api.similar(song.clip)) }
            }.getOrDefault(emptyList())
        }
    }

    val songs = feed?.songs.orEmpty().filter { it.savedAt !in hidden }
    val shown = songs.filter { song ->
        // "Alle" blendet Empfehlungen aus, die stehen nur unter ihrem eigenen Filter
        (if (sourceFilter == null) song.source != Song.Source.SIMILAR else song.source == sourceFilter) &&
            (query.isBlank() || song.name.contains(query, true) || song.artist.contains(query, true))
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Deine Sounds", fontSize = 30.sp, fontWeight = FontWeight.Black,
                            color = Scheme.onBackground)
                        Api.user?.let { Text("@$it", fontSize = 12.sp, color = Scheme.onSurfaceVariant) }
                    }
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Suche", tint = Scheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { tick++ }) {
                        Icon(Icons.Default.Refresh, "Aktualisieren", tint = Scheme.onSurfaceVariant)
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
                        }
                    }
                }
            }
            item {
                feed?.let {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatChip("${songs.size} Songs")
                        StatChip("${thisWeek(songs)} diese Woche")
                        topArtist(songs)?.let { top -> StatChip("Top $top") }
                        Surface(
                            color = Cyan.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(99.dp),
                            border = BorderStroke(1.dp, Cyan.copy(alpha = 0.4f)),
                            modifier = Modifier.clickable { showStats = true },
                        ) {
                            Text("Dein Geschmack", Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                fontSize = 12.sp, color = Cyan)
                        }
                    }
                } ?: Text("lädt…", color = Scheme.onSurfaceVariant, fontSize = 13.sp)
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceChip("Alle", sourceFilter == null) { sourceFilter = null }
                    Song.Source.entries.forEach { source ->
                        SourceChip(source.filter, sourceFilter == source) {
                            sourceFilter = if (sourceFilter == source) null else source
                        }
                    }
                }
            }
            item {
                AnimatedVisibility(searching, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        placeholder = { Text("Song oder Artist suchen…") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        ),
                    )
                }
            }
            clipboardSuggestion?.let {
                item {
                    GlassBox {
                        Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("TikTok-Link in der Zwischenablage", Modifier.weight(1f), fontSize = 14.sp)
                            Surface(
                                color = Cyan.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(99.dp),
                                modifier = Modifier.clickable {
                                    onClipboardHandled(true)
                                    tick++
                                },
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
            if (feed != null && shown.isEmpty() && feed!!.pending == 0) {
                item { EmptyState(query.isNotBlank() || sourceFilter != null) }
            }
            items(shown, key = { it.savedAt }) { song ->
                Box(Modifier.animateItem()) {
                    SongCard(
                        song = song,
                        buffering = clipPlayer.state?.takeIf { it.first == song.clip }?.second,
                        progress = playProgress,
                        volume = clipPlayer.volume,
                        onVolume = {
                            clipPlayer.changeVolume(it)
                            context.getSharedPreferences("queue", Context.MODE_PRIVATE)
                                .edit().putFloat("volume", it).apply()
                        },
                        onSeek = {
                            playProgress = it
                            clipPlayer.seekTo(it)
                        },
                        onPlay = { clipPlayer.toggle(song.clip) },
                        onPlayFull = { clipPlayer.play(song.clip, Api.fullUrl(song.clip)) },
                        onSimilar = { similarFor = song },
                        onDelete = { deleteWithUndo(song) },
                    )
                }
            }
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
                                    android.widget.Toast.makeText(context, "In deine Liste übernommen", android.widget.Toast.LENGTH_SHORT).show()
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
                false -> Box(Modifier.size(12.dp).background(Color.White, RoundedCornerShape(3.dp)))
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
            else -> LazyColumn(Modifier.fillMaxWidth().height(400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.first }) { (name, admin, count) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(if (admin) "@$name · Admin" else "@$name",
                                fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                color = if (admin) Cyan else Scheme.onSurface)
                            Text("$count Songs", fontSize = 12.sp, color = Scheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { resetFor = name }) { Text("Passwort", fontSize = 12.sp, color = Cyan) }
                        if (name != Api.user) {
                            IconButton(onClick = {
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { Api.adminDeleteUser(name) } }
                                    reload++
                                }
                            }) { Icon(Icons.Default.Delete, "Löschen", tint = Pink) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsOverlay(songs: List<Song>, onClose: () -> Unit) {
    OverlayFrame("Dein Geschmack", onClose) {
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
    "Wartet" to 0.1f,
    "Video wird geladen" to 0.4f,
    "Song wird erkannt" to 0.75f,
    "Wird gespeichert" to 0.95f,
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
                    stage?.let {
                        Text(it, fontSize = 12.sp, color = Scheme.onSurfaceVariant)
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
        Text("♪", fontSize = 48.sp, color = Scheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
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
                false -> Box(Modifier.size(15.dp).background(Color.White, RoundedCornerShape(3.dp)))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongCard(song: Song, buffering: Boolean?, progress: Float, volume: Float, onVolume: (Float) -> Unit, onSeek: (Float) -> Unit, onPlay: () -> Unit, onPlayFull: () -> Unit, onSimilar: () -> Unit, onDelete: () -> Unit) {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔊", fontSize = 10.sp)
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.weight(1f)) {
                            SlimSlider(volume, onVolume, Color.White, Color.White.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}

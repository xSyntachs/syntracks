package de.xsyntachs.tiktoksongs

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.withContext
import java.net.URL
import java.time.OffsetDateTime

private fun relativeTime(savedAt: String): String = runCatching {
    DateUtils.getRelativeTimeSpanString(OffsetDateTime.parse(savedAt).toInstant().toEpochMilli()).toString()
}.getOrDefault(savedAt)

private val coverCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

@Composable
fun rememberCover(url: String?): Bitmap? {
    var bitmap by remember(url) { mutableStateOf(url?.let(coverCache::get)) }
    LaunchedEffect(url) {
        if (url != null && bitmap == null) {
            bitmap = withContext(Dispatchers.IO) {
                runCatching { URL(url).openStream().use(BitmapFactory::decodeStream) }.getOrNull()
            }?.also { coverCache.put(url, it) }
        }
    }
    return bitmap
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

@Composable
fun SongList(
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
    var page by remember(shown.size, showRecs, filtered) { mutableStateOf(1) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val perPage = ((maxHeight - 60.dp) / 108.dp).toInt().coerceAtLeast(3)
        val pageCount = ((shown.size + perPage - 1) / perPage).coerceAtLeast(1)
        val current = page.coerceIn(1, pageCount)
        val pageItems = shown.drop((current - 1) * perPage).take(perPage)
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                if (feed != null && feed.pending > 0) {
                    item { PendingCard(feed.pending, feed.pendingStage) }
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
                items(pageItems, key = { it.savedAt }) { song ->
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
            if (!showRecs && pageCount > 1) Pager(current, pageCount) { page = it }
        }
    }
}

@Composable
private fun Pager(page: Int, pageCount: Int, onPick: (Int) -> Unit) {
    val wanted = buildSet {
        addAll(listOf(1, pageCount, page, page - 1, page + 1))
        if (page <= 3) addAll(listOf(2, 3, 4))
        if (page >= pageCount - 2) addAll(listOf(pageCount - 1, pageCount - 2, pageCount - 3))
    }
    val numbers = wanted.filter { it in 1..pageCount }.sorted()
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
    ) {
        PagerStep("‹", enabled = page > 1) { onPick(page - 1) }
        numbers.forEachIndexed { index, number ->
            if (index > 0 && number - numbers[index - 1] > 1) {
                Text("…", fontSize = 13.sp, color = Scheme.onSurfaceVariant)
            }
            PagerStep("$number", active = number == page) { onPick(number) }
        }
        PagerStep("›", enabled = page < pageCount) { onPick(page + 1) }
    }
}

@Composable
private fun PagerStep(label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (active) Brand else Color.Transparent)
            .border(2.dp, if (active) Brand else Line, RoundedCornerShape(5.dp))
            .clickable(enabled = enabled && !active) { onClick() },
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (active) Ink else if (enabled) Scheme.onSurface else Scheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SimilarRow(track: SimilarTrack, playing: Boolean?, alreadySaved: Boolean, onPlay: () -> Unit, onSave: () -> Unit) {
    val cover = rememberCover(track.artwork)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(5.dp))
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

@Composable
private fun CoverButton(song: Song, buffering: Boolean?, accent: Color, onPlay: () -> Unit) {
    val cover = rememberCover(song.artwork)
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(5.dp))
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
        Song.Source.TIKTOK -> Brand
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
                        Text(
                            song.source.label.uppercase(),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp,
                            color = Brand,
                        )
                        if (song.favorite) {
                            Spacer(Modifier.width(10.dp))
                            Icon(Icons.Default.Star, "Favorit", tint = Brand, modifier = Modifier.size(12.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(relativeTime(song.savedAt), fontSize = 10.sp, color = Scheme.onSurfaceVariant)
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, "Aktionen", tint = Scheme.onSurfaceVariant)
                    }
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

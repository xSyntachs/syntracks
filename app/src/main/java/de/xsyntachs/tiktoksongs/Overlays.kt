package de.xsyntachs.tiktoksongs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.OffsetDateTime

private fun thisWeek(songs: List<Song>): Int {
    val limit = OffsetDateTime.now().minusDays(7)
    return songs.count { runCatching { OffsetDateTime.parse(it.savedAt).isAfter(limit) }.getOrDefault(false) }
}

private fun topArtist(songs: List<Song>): String? =
    songs.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }?.key

@Composable
fun OverlayFrame(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)).clickable(onClick = onClose),
    ) {
        Card(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(16.dp)
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(5.dp),
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
fun DialogField(value: String, onChange: (String) -> Unit, label: String, password: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(5.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Cyan,
            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    )
}

@Composable
fun DialogButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(top = 14.dp).height(46.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(SolidColor(Brand))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
fun SimilarOverlay(
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
fun RenameDialog(onClose: () -> Unit, onDone: () -> Unit) {
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
fun PasswordDialog(onClose: () -> Unit) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsOverlay(songs: List<Song>, onClose: () -> Unit) {
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
                                .background(SolidColor(Brand)))
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

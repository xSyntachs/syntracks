package de.xsyntachs.tiktoksongs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun AdminOverlay(onClose: () -> Unit) {
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
                        avatarBrush = SolidColor(Brand),
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
            .clip(RoundedCornerShape(5.dp))
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
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.07f))) {
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

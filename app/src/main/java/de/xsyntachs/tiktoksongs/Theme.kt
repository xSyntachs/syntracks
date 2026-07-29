package de.xsyntachs.tiktoksongs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Brand = Color(0xFFE9E64A)
val Ink = Color(0xFF121212)
val RowSurface = Color(0xFF1E1E1B)
val Line = Color(0xFF2A2A26)
val Danger = Color(0xFFF0705F)

val Pink = Danger
val Cyan = Brand
val Violet = Brand
val Gold = Brand

val Scheme = darkColorScheme(
    primary = Brand,
    secondary = Brand,
    background = Ink,
    surface = RowSurface,
    onBackground = Color(0xFFF5F3E7),
    onSurface = Color(0xFFF5F3E7),
    onSurfaceVariant = Color(0xFF9B9890),
)

private val GlassCard = RowSurface

@Composable
fun AuroraBackground(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink)) { content() }
}

@Composable
fun GlassBox(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(5.dp),
        colors = CardDefaults.cardColors(containerColor = RowSurface, contentColor = Scheme.onSurface),
    ) { content() }
}

@Composable
fun SourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .border(2.dp, if (selected) Scheme.onSurface else Line, RoundedCornerShape(5.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) Scheme.onSurface else Scheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ActionPill(label: String, color: Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .border(2.dp, Line, RoundedCornerShape(5.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
fun StatChip(text: String) {
    Surface(
        color = GlassCard,
        shape = RoundedCornerShape(99.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 12.sp, color = Scheme.onSurfaceVariant)
    }
}

@Composable
fun MenuHeader(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Scheme.onSurfaceVariant,
    )
}

@Composable
fun PauseGlyph(color: Color, size: androidx.compose.ui.unit.Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(size / 4)) {
        Box(Modifier.size(size / 3, size).background(color, RoundedCornerShape(1.dp)))
        Box(Modifier.size(size / 3, size).background(color, RoundedCornerShape(1.dp)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlimSlider(value: Float, onChange: (Float) -> Unit, thumbColor: Color, trackColor: Color) {
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

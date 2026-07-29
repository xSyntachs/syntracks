package de.xsyntachs.tiktoksongs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LoginScreen(onSuccess: () -> Unit) {
    var register by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    AuroraBackground {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(R.drawable.logo_syntracks),
                contentDescription = "Syntracks",
                modifier = Modifier.padding(bottom = 12.dp).height(92.dp),
            )
            Text("Syntracks", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Scheme.onBackground)
            Text(if (register) "Konto erstellen" else "Anmelden",
                fontSize = 14.sp, color = Scheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp))

            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cyan,
                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.trim(); error = null },
                label = { Text("Benutzername") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { Text("Passwort") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions.Default,
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            error?.let {
                Text(it, color = Pink, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }
            val ready = !busy && name.isNotBlank() && password.isNotBlank()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .height(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        if (ready) Brush.horizontalGradient(listOf(Pink, Color(0xFF9C1B6B), Cyan))
                        else Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.08f)))
                    )
                    .clickable(enabled = ready) {
                        busy = true
                        error = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    if (register) Api.register(context, name, password)
                                    else Api.login(context, name, password)
                                }
                            }
                            busy = false
                            result.onSuccess { onSuccess() }
                                .onFailure { error = it.message ?: "Fehlgeschlagen" }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(Modifier.padding(2.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(
                    if (register) "Konto erstellen" else "Anmelden",
                    fontWeight = FontWeight.Bold,
                    color = if (ready) Color.White else Scheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { register = !register; error = null }) {
                Text(
                    if (register) "Schon ein Konto? Anmelden" else "Neu hier? Konto erstellen",
                    color = Cyan,
                )
            }
        }
    }
}

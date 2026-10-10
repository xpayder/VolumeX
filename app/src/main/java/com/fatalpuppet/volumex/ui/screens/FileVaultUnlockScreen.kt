package com.fatalpuppet.volumex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo
import com.fatalpuppet.volumex.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Unlock screen for a FileVault-encrypted APFS volume. The secret is only used to derive the volume key
 * in memory; it is never stored or sent anywhere.
 */
@Composable
fun FileVaultUnlockScreen(
    volume: VolumeInfo,
    onUnlock: suspend (secret: String) -> Boolean,
    onSuccess: () -> Unit,
    onEject: () -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    var secret by remember { mutableStateOf("") }
    var recoveryMode by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (secret.isBlank() || busy) return
        busy = true; error = null
        scope.launch {
            val ok = onUnlock(secret.trim().let { if (recoveryMode) it else secret })
            busy = false
            if (ok) onSuccess() else error = if (recoveryMode) "That recovery key didn't unlock this drive." else "Wrong password. Try again."
        }
    }

    run {
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        com.fatalpuppet.volumex.ui.components.GlassTopBar(title = "Unlock drive", onBack = onNavigateBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(28.dp))
            Box(
                Modifier.size(92.dp).clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Accent.copy(alpha = 0.30f), Accent.copy(alpha = 0.08f))))
                    .border(1.dp, GlassBorderFaint, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Lock, null, tint = Accent, modifier = Modifier.size(44.dp)) }
            Spacer(Modifier.height(22.dp))
            Text(volume.name.ifBlank { "Encrypted drive" }, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text("Encrypted with ${volume.encryption.ifEmpty { "FileVault" }}", color = TextTertiary, fontSize = 14.sp)
            Spacer(Modifier.height(30.dp))

            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it; error = null },
                label = { Text(if (recoveryMode) "Recovery key" else "Password") },
                singleLine = true,
                enabled = !busy,
                isError = error != null,
                visualTransformation = if (show || recoveryMode) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = if (recoveryMode) KeyboardType.Ascii else KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                trailingIcon = {
                    if (!recoveryMode) IconButton(onClick = { show = !show }) {
                        Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (show) "Hide password" else "Show password", tint = TextTertiary)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = GlassBorder, errorBorderColor = AccentRed,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent,
                    focusedLabelColor = Accent, unfocusedLabelColor = TextTertiary
                )
            )
            error?.let { Text(it, color = AccentRed, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp)) }
            Spacer(Modifier.height(18.dp))

            Button(
                onClick = { submit() }, enabled = secret.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = DeepNavy, disabledContainerColor = Accent.copy(alpha = 0.25f))
            ) {
                if (busy) {
                    CircularProgressIndicator(color = DeepNavy, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp)); Text("Unlocking…", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                } else {
                    Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text("UNLOCK DRIVE", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 1.sp)
                }
            }
            TextButton(onClick = { recoveryMode = !recoveryMode; secret = ""; error = null }, enabled = !busy) {
                Text(if (recoveryMode) "Use password instead" else "Use recovery key instead", color = Accent, fontSize = 14.sp)
            }

            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth().glassOrSolid(RoundedCornerShape(18.dp)).padding(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Default.Shield, null, tint = AccentGreen, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Your password never leaves this phone. It is used once to unlock the drive in memory and is not saved. The drive is opened read-only.",
                    color = TextTertiary, fontSize = 12.sp, lineHeight = 17.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onEject, enabled = !busy) {
                Icon(Icons.Default.Eject, null, tint = TextSecondary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("Eject", color = TextSecondary, fontSize = 14.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    }
    }
}

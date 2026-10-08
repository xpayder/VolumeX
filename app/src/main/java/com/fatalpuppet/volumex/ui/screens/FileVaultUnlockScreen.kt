package com.fatalpuppet.volumex.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.fatalpuppet.volumex.security.KeystorePasswordManager
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo
import com.fatalpuppet.volumex.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileVaultUnlockScreen(
    volume: VolumeInfo,
    onUnlock: (password: String, isRecoveryKey: Boolean) -> Boolean,
    onSuccess: () -> Unit,
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var isRecoveryKeyMode by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var savePassword by remember { mutableStateOf(false) }

    val pwManager = remember { KeystorePasswordManager(context) }

    // Check for saved password
    LaunchedEffect(volume.uuid) {
        val saved = pwManager.getPassword(volume.uuid)
        if (saved != null) password = saved
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DeepNavy, DarkNavy, NavyMid)))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GlassWhite8)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary)
                }
                Spacer(Modifier.width(8.dp))
                Text("FileVault Unlock", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Lock icon
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Brush.verticalGradient(listOf(AccentOrange.copy(0.3f), AccentRed.copy(0.2f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Lock, null, tint = AccentOrange, modifier = Modifier.size(36.dp))
                }

                Spacer(Modifier.height(20.dp))

                Text("Encrypted Volume", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = volume.name.ifEmpty { "Unknown Volume" },
                    color = TextTertiary,
                    fontSize = 14.sp
                )

                Spacer(Modifier.height(32.dp))

                // Mode toggle
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(GlassWhite8)
                        .padding(4.dp)
                ) {
                    ModeTab("Password", !isRecoveryKeyMode) { isRecoveryKeyMode = false; password = "" }
                    ModeTab("Recovery Key", isRecoveryKeyMode) { isRecoveryKeyMode = true; password = "" }
                }

                Spacer(Modifier.height(20.dp))

                // Input field
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    label = { Text(if (isRecoveryKeyMode) "Recovery Key (Base32)" else "Password") },
                    singleLine = !isRecoveryKeyMode,
                    visualTransformation = if (showPassword || isRecoveryKeyMode) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (isRecoveryKeyMode) KeyboardType.Text else KeyboardType.Password),
                    trailingIcon = {
                        if (!isRecoveryKeyMode) {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    null, tint = TextTertiary
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = GlassBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = AccentBlue,
                        focusedLabelColor = AccentBlue,
                        unfocusedLabelColor = TextTertiary
                    )
                )

                Spacer(Modifier.height(8.dp))

                // Save password toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = savePassword,
                        onCheckedChange = { savePassword = it },
                        colors = CheckboxDefaults.colors(checkedColor = AccentBlue, uncheckedColor = TextTertiary)
                    )
                    Text("Save password with biometrics", color = TextSecondary, fontSize = 13.sp)
                }

                Spacer(Modifier.height(8.dp))

                // Error message
                AnimatedVisibility(visible = errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = AccentRed,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                // Unlock button
                Button(
                    onClick = {
                        isLoading = true
                        errorMessage = null
                        val success = onUnlock(password, isRecoveryKeyMode)
                        isLoading = false
                        if (success) {
                            if (savePassword && volume.uuid.isNotEmpty()) {
                                pwManager.savePassword(volume.uuid, password)
                            }
                            onSuccess()
                        } else {
                            errorMessage = if (isRecoveryKeyMode) "Invalid recovery key" else "Wrong password"
                        }
                    },
                    enabled = password.isNotBlank() && !isLoading,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue,
                        disabledContainerColor = AccentBlueDim
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = TextPrimary,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Unlock Volume", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AccentBlue else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (selected) TextPrimary else TextTertiary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}


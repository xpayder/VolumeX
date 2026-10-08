package com.fatalpuppet.volumex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fatalpuppet.volumex.security.KeystorePasswordManager
import com.fatalpuppet.volumex.ui.theme.*

@Composable
fun SettingsScreen(
    showHiddenFiles: Boolean,
    onToggleHiddenFiles: () -> Unit,
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val passwordManager = remember { KeystorePasswordManager(context) }
    var savedUuids by remember { mutableStateOf(
        context.getSharedPreferences("drive_passwords", android.content.Context.MODE_PRIVATE)
            .all.keys
            .filter { it.endsWith(".enc") }
            .map { it.removeSuffix(".enc") }
    ) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DeepNavy, DarkNavy)))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GlassWhite8)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Settings",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    SettingsSection(title = "File Browser") {
                        SettingsToggleRow(
                            icon = Icons.Default.VisibilityOff,
                            title = "Show hidden files",
                            subtitle = "Show files and folders starting with '.'",
                            checked = showHiddenFiles,
                            onToggle = onToggleHiddenFiles
                        )
                    }
                }

                item {
                    SettingsSection(title = "Security") {
                        if (savedUuids.isEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.LockOpen,
                                    null,
                                    tint = TextTertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "No saved passwords",
                                    color = TextTertiary,
                                    fontSize = 14.sp
                                )
                            }
                        } else {
                            savedUuids.forEach { uuid ->
                                SavedPasswordRow(
                                    uuid = uuid,
                                    onDelete = { showDeleteConfirm = uuid }
                                )
                            }
                        }
                    }
                }

                item {
                    SettingsSection(title = "About") {
                        SettingsInfoRow(
                            icon = Icons.Default.Info,
                            title = "VolumeX",
                            subtitle = "Open-source APFS / HFS+ reader for Android"
                        )
                        SettingsInfoRow(
                            icon = Icons.Default.Storage,
                            title = "Supported filesystems",
                            subtitle = "APFS, HFS+, FAT32, exFAT, ext2/3/4"
                        )
                        SettingsInfoRow(
                            icon = Icons.Default.Security,
                            title = "Encryption",
                            subtitle = "FileVault AES-XTS decryption supported"
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete saved password?", color = TextPrimary) },
            text = {
                Text(
                    "The saved password for this volume will be removed from the secure keystore.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm?.let { uuid ->
                        passwordManager.deletePassword(uuid)
                        savedUuids = savedUuids.filter { it != uuid }
                    }
                    showDeleteConfirm = null
                }) {
                    Text("Delete", color = Color(0xFFFF453A))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("Cancel", color = AccentBlue)
                }
            },
            containerColor = Color(0xFF1C2135),
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            title.uppercase(),
            color = TextTertiary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(GlassWhite8, RoundedCornerShape(16.dp))
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = AccentBlue, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp)
            Text(subtitle, color = TextTertiary, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentBlue,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = GlassWhite12
            )
        )
    }
}

@Composable
private fun SettingsInfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = AccentBlue, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = TextPrimary, fontSize = 15.sp)
            Text(subtitle, color = TextTertiary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SavedPasswordRow(
    uuid: String,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Lock, null, tint = AccentPurple, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Saved password", color = TextPrimary, fontSize = 15.sp)
            Text(
                "Volume: ${uuid.take(8)}…",
                color = TextTertiary,
                fontSize = 12.sp
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF453A), modifier = Modifier.size(20.dp))
        }
    }
}

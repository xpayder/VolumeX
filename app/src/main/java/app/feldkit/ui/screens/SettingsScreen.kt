package app.feldkit.ui.screens

import app.feldkit.ui.components.GlassOutlinedButton
import app.feldkit.ui.components.GlassButton
import app.feldkit.ui.components.GlassDialog
import app.feldkit.ui.components.GlassSheet
import app.feldkit.ui.components.GlassMenu
import app.feldkit.ui.components.GlassMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feldkit.security.KeystorePasswordManager
import app.feldkit.ui.theme.*

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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            app.feldkit.ui.components.GlassTopBar(title = "Settings", onBack = onNavigateBack)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    val prefs = remember { context.getSharedPreferences("vx_prefs", android.content.Context.MODE_PRIVATE) }
                    var apfsWrite by remember { mutableStateOf(prefs.getBoolean("apfs_write", false)) }
                    var confirmApfs by remember { mutableStateOf(false) }
                    var ntfsWrite by remember { mutableStateOf(prefs.getBoolean("ntfs_write", false)) }
                    var confirmNtfs by remember { mutableStateOf(false) }
                    SettingsSection(title = "Experimental") {
                        SettingsToggleRow(
                            icon = Icons.Default.Warning,
                            title = "Write to APFS drives",
                            subtitle = "Edits the drive in place (not copy-on-write). Checked against macOS fsck_apfs, but a power loss or unplugging during a write can leave the drive needing repair on a Mac. Keep a backup. Applies the next time you connect a drive.",
                            checked = apfsWrite,
                            onToggle = { if (apfsWrite) { apfsWrite = false; prefs.edit().putBoolean("apfs_write", false).apply(); app.feldkit.storage.filesystem.FilesystemMounter.enableApfsWrite = false } else confirmApfs = true }
                        )
                        SettingsToggleRow(
                            icon = Icons.Default.Warning,
                            title = "Write to NTFS drives",
                            subtitle = "Creates, renames and deletes files and folders on Windows drives. Checked against ntfs-3g, not against Windows chkdsk. Only works on drives Windows shut down cleanly (turn off Fast Startup); it refuses otherwise. Keep a backup. Applies the next time you connect a drive.",
                            checked = ntfsWrite,
                            onToggle = { if (ntfsWrite) { ntfsWrite = false; prefs.edit().putBoolean("ntfs_write", false).apply(); app.feldkit.storage.filesystem.FilesystemMounter.enableNtfsWrite = false } else confirmNtfs = true }
                        )
                    }
                    if (confirmNtfs) {
                        GlassDialog(
                            onDismissRequest = { confirmNtfs = false },
                            title = { Text("Enable NTFS writing?", color = TextPrimary) },
                            text = { Text("This is experimental. Use it only on drives you have backed up, make sure Windows fully shut down (not hibernated), and never unplug the drive during a transfer.", color = TextSecondary) },
                            confirmButton = { TextButton(onClick = {
                                ntfsWrite = true; confirmNtfs = false
                                prefs.edit().putBoolean("ntfs_write", true).apply()
                                app.feldkit.storage.filesystem.FilesystemMounter.enableNtfsWrite = true
                            }) { Text("Enable", color = Accent) } },
                            dismissButton = { TextButton(onClick = { confirmNtfs = false }) { Text("Cancel", color = TextTertiary) } }
                        )
                    }
                    if (confirmApfs) {
                        GlassDialog(
                            onDismissRequest = { confirmApfs = false },
                            title = { Text("Enable APFS writing?", color = TextPrimary) },
                            text = { Text("This is experimental. Only use it on drives whose contents you have backed up, and never unplug the drive while a transfer is running.", color = TextSecondary) },
                            confirmButton = { TextButton(onClick = {
                                apfsWrite = true; confirmApfs = false
                                prefs.edit().putBoolean("apfs_write", true).apply()
                                app.feldkit.storage.filesystem.FilesystemMounter.enableApfsWrite = true
                            }) { Text("Enable", color = Accent) } },
                            dismissButton = { TextButton(onClick = { confirmApfs = false }) { Text("Cancel", color = TextTertiary) } }
                        )
                    }
                }
                item {
                    val tprefs = remember { context.getSharedPreferences("vx_prefs", android.content.Context.MODE_PRIVATE) }
                    var verify by remember { mutableStateOf(tprefs.getBoolean("verify_copies", true)) }
                    var manifest by remember { mutableStateOf(tprefs.getBoolean("write_manifest", false)) }
                    SettingsSection(title = "Transfers") {
                        SettingsToggleRow(
                            icon = Icons.Default.Check,
                            title = "Verify copies",
                            subtitle = "After each copy the file is read back and compared (SHA-256) with the original. Slower, but a damaged copy is caught and removed instead of silently kept.",
                            checked = verify,
                            onToggle = { verify = !verify; tprefs.edit().putBoolean("verify_copies", verify).apply() }
                        )
                        SettingsToggleRow(
                            icon = Icons.Default.Description,
                            title = "Write a checksum list",
                            subtitle = "Adds a FeldKit-<date>.sha256 file next to the copied files. On a Mac or Linux, `shasum -a 256 -c` against it re-checks everything later.",
                            checked = manifest,
                            onToggle = { manifest = !manifest; tprefs.edit().putBoolean("write_manifest", manifest).apply() }
                        )
                    }
                }
                item {
                    val sprefs = remember { context.getSharedPreferences("vx_prefs", android.content.Context.MODE_PRIVATE) }
                    var fast by remember { mutableStateOf(sprefs.getBoolean("fast_usb", false)) }
                    var speedText by remember { mutableStateOf<String?>(null) }
                    var testing by remember { mutableStateOf(false) }
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    SettingsSection(title = "Drive speed") {
                        SettingsToggleRow(
                            icon = Icons.Default.Speed,
                            title = "Fast USB reads (beta)",
                            subtitle = "Keeps several USB requests in flight instead of one at a time. If your drive misbehaves it switches itself off. Run the speed test to see whether it helps on your drive.",
                            checked = fast,
                            onToggle = { fast = !fast; sprefs.edit().putBoolean("fast_usb", fast).apply(); app.feldkit.storage.usb.UsbTuning.fastReads = fast }
                        )
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            GlassButton(
                                enabled = !testing, shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = DeepNavy),
                                onClick = {
                                    testing = true; speedText = "Reading 64 MB straight from the drive…"
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        val dev = app.feldkit.storage.ActiveDriveSession.device as? app.feldkit.storage.usb.UsbBlockDeviceReader
                                        val msg = if (dev == null) "Connect a USB drive first - the test reads directly from the drive." else {
                                            val saved = app.feldkit.storage.usb.UsbTuning.fastReads
                                            app.feldkit.storage.usb.UsbTuning.fastReads = false
                                            val a = dev.measureReadSpeed(64)
                                            app.feldkit.storage.usb.UsbTuning.fastReads = true
                                            val b = dev.measureReadSpeed(64)
                                            val fastWorked = app.feldkit.storage.usb.UsbTuning.fastReads
                                            app.feldkit.storage.usb.UsbTuning.fastReads = saved && fastWorked
                                            if (a == null) "The drive is too small or could not be read for a test."
                                            else "Standard: %.0f MB/s".format(a) + (if (b != null && fastWorked) "  ·  Fast: %.0f MB/s".format(b) else "  ·  Fast mode did not work on this drive (kept off)")
                                        }
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { speedText = msg; testing = false }
                                    }
                                }
                            ) { Text(if (testing) "Testing…" else "Run drive speed test", fontWeight = FontWeight.SemiBold) }
                            speedText?.let { Text(it, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                        }
                    }
                }
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
                            title = "FeldKit",
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
        GlassDialog(
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
                    Text("Cancel", color = Accent)
                }
            }
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
                .glassOrSolid(RoundedCornerShape(22.dp), GlassLevel.Card)
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
        Icon(icon, null, tint = if (icon == Icons.Default.Warning) AccentOrange else Accent, modifier = Modifier.size(22.dp))
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
                checkedTrackColor = Accent,
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
        Icon(icon, null, tint = Accent, modifier = Modifier.size(22.dp))
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
        Icon(Icons.Default.Lock, null, tint = AccentLight, modifier = Modifier.size(22.dp))
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

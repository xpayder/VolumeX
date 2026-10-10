package app.feldkit.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feldkit.hash.HashAlgo
import app.feldkit.ui.components.GlassDialog
import app.feldkit.ui.theme.*
import app.feldkit.ui.viewmodel.FileBrowserViewModel.DestSpec

private const val PREFS = "vx_prefs"

/** Places used before, so a second copy to the same SD card is one tap. */
private fun recentPlaces(ctx: Context): List<DestSpec.Tree> {
    val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("offload_recent", "") ?: ""
    return raw.split('\n').mapNotNull { l ->
        val i = l.indexOf('|'); if (i <= 0) return@mapNotNull null
        val uri = Uri.parse(l.substring(0, i))
        // only places whose permission is still granted
        if (ctx.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isWritePermission }) null else DestSpec.Tree(uri, l.substring(i + 1))
    }
}

private fun rememberPlace(ctx: Context, place: DestSpec.Tree) {
    val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val old = (p.getString("offload_recent", "") ?: "").split('\n').filter { it.isNotBlank() && !it.startsWith(place.uri.toString() + "|") }
    p.edit().putString("offload_recent", (listOf("${place.uri}|${place.label}") + old).take(6).joinToString("\n")).apply()
}

private fun treeLabel(ctx: Context, tree: Uri): String {
    var name = "Folder"
    try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
    } catch (_: Exception) {}
    return name
}

/**
 * "Save to phone". One tap on Save keeps the old behaviour (Downloads/FeldKit, copy verified by reading it back). The block
 * under "More options" is for people who need several copies and checksums.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaveToPhoneDialog(
    itemCount: Int,
    onDismiss: () -> Unit,
    onStart: (specs: List<DestSpec>, algos: List<HashAlgo>, verify: Boolean, sidecar: Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val places = remember { mutableStateListOf<DestSpec>(DestSpec.Downloads).also { l -> recentPlaces(ctx).forEach { l.add(it) } } }
    val chosen = remember { mutableStateListOf<DestSpec>(DestSpec.Downloads) }
    var verify by remember { mutableStateOf(prefs.getBoolean("verify_copies", true)) }
    var sidecar by remember { mutableStateOf(prefs.getBoolean("write_manifest", false)) }
    val algos = remember { mutableStateListOf<HashAlgo>().also { l -> (prefs.getString("offload_algos", "XXH64") ?: "XXH64").split(',').mapNotNull { n -> HashAlgo.values().firstOrNull { it.name == n } }.ifEmpty { listOf(HashAlgo.XXH64) }.forEach { l.add(it) } } }
    var more by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val place = DestSpec.Tree(uri, treeLabel(ctx, uri))
            if (places.none { it is DestSpec.Tree && it.uri == uri }) places.add(place)
            if (chosen.none { it is DestSpec.Tree && it.uri == uri }) chosen.add(place)
            rememberPlace(ctx, place)
            more = true
        }
    }

    fun label(p: DestSpec) = if (p is DestSpec.Tree) p.label else "Downloads / FeldKit"

    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save to phone", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                places.forEach { p ->
                    val on = chosen.contains(p)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { if (on) { if (chosen.size > 1) chosen.remove(p) } else chosen.add(p) }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (on) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null, tint = if (on) Accent else TextTertiary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Icon(if (p is DestSpec.Downloads) Icons.Default.Download else Icons.Default.SdStorage, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(label(p), color = TextPrimary, fontSize = 14.sp)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { picker.launch(null) }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, null, tint = Accent, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
                    Text(if (chosen.size > 1) "Add another place…" else "Choose another folder…", color = Accent, fontSize = 14.sp)
                }
                if (chosen.size == 1 && chosen[0] is DestSpec.Downloads) Text("Android's folder picker cannot pick Downloads or the storage root, so use the first option for those.", color = TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { more = !more }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("More options", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Icon(if (more) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = TextTertiary)
                }
                AnimatedVisibility(more) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleRow("Check every copy by reading it back", verify) { verify = it }
                        ToggleRow("Write a checksum list (xxhsum, shasum compatible)", sidecar) { sidecar = it }
                        Text("Checksums", color = TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            HashAlgo.values().forEach { a ->
                                val on = algos.contains(a)
                                Text(
                                    a.label, color = if (on) DeepNavy else TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Accent else Fill2).clickable { if (on) { if (algos.size > 1) algos.remove(a) } else algos.add(a) }.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                        Text("Each file is read from the drive once, hashed on the way, and written to every place at the same time.", color = TextTertiary, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.edit().putBoolean("verify_copies", verify).putBoolean("write_manifest", sidecar).putString("offload_algos", algos.joinToString(",") { it.name }).apply()
                onStart(chosen.toList(), algos.toList(), verify, sidecar)
            }) { Text("Save $itemCount item${if (itemCount != 1) "s" else ""}", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextTertiary) } }
    )
}

@Composable
private fun ToggleRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedThumbColor = DeepNavy, checkedTrackColor = Accent, uncheckedTrackColor = Fill2, uncheckedThumbColor = TextSecondary, uncheckedBorderColor = GlassBorder))
    }
}

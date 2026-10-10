package app.feldkit.ui.components

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.feldkit.R

@Composable
fun ConnectButton(
    onClick: () -> Unit
) {
    GlassButton(onClick = onClick) {
        Text(stringResource(R.string.connect_drive))
    }
}
package app.feldkit.ui.components

import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.feldkit.R

@Composable
fun AboutButton(
    onClick: () -> Unit = {}
) {

    TextButton(
        onClick = onClick
    ) {
        Text(stringResource(R.string.about))
    }

}
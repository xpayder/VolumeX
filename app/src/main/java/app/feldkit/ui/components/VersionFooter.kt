package app.feldkit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.feldkit.R
import app.feldkit.utils.AppConstants

@Composable
fun VersionFooter() {

    Text(
        text = "${stringResource(R.string.version)} ${AppConstants.VERSION}",
        style = MaterialTheme.typography.bodySmall
    )

}
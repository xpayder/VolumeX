package app.feldkit.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary           = Accent,
    onPrimary         = TextPrimary,
    primaryContainer  = AccentDim,
    onPrimaryContainer = TextPrimary,
    secondary         = AccentLight,
    onSecondary       = TextPrimary,
    secondaryContainer = AccentLightDim,
    onSecondaryContainer = TextPrimary,
    tertiary          = AccentGreen,
    onTertiary        = TextPrimary,
    background        = DeepNavy,
    onBackground      = TextPrimary,
    surface           = DarkSurface,
    onSurface         = TextPrimary,
    surfaceVariant    = DarkCard,
    onSurfaceVariant  = TextSecondary,
    outline           = GlassBorder,
    error             = AccentRed,
    onError           = TextPrimary
)

private val LightColorScheme = lightColorScheme(
    primary           = LightPrimary,
    onPrimary         = TextPrimary,
    background        = LightBackground,
    onBackground      = DeepNavy,
    surface           = LightSurface,
    onSurface         = DeepNavy
)

@Composable
fun FeldKitTheme(
    darkTheme: Boolean = true,  // Dark-first
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

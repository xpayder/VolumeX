package app.feldkit.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feldkit.ui.theme.Accent
import app.feldkit.ui.theme.DeepNavy
import app.feldkit.ui.theme.GlassLevel
import app.feldkit.ui.theme.TextPrimary
import app.feldkit.ui.theme.TextSecondary
import app.feldkit.ui.theme.glassOrSolid

private val ButtonShape = RoundedCornerShape(18.dp)

/** Shared press feedback: a small spring scale, the same on every tappable surface. */
@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.7f, stiffness = 500f), label = "press")
    return s
}

/**
 * The app's primary button: solid mint, dark label. Drop-in for Material's Button; Material-only arguments are accepted
 * and ignored so every button in the app looks the same.
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") shape: Shape? = null,
    @Suppress("UNUSED_PARAMETER") colors: Any? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier.scale(pressScale(source)).heightIn(min = 48.dp).clip(ButtonShape)
            .background(if (enabled) Accent else Accent.copy(alpha = 0.22f))
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
            .padding(contentPadding),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) DeepNavy else DeepNavy.copy(alpha = 0.5f),
            LocalTextStyle provides TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        ) { content() }
    }
}

/** Secondary button: frosted glass with a white label. Drop-in for Material's OutlinedButton. */
@Composable
fun GlassOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") shape: Shape? = null,
    @Suppress("UNUSED_PARAMETER") colors: Any? = null,
    @Suppress("UNUSED_PARAMETER") border: Any? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier.scale(pressScale(source)).heightIn(min = 48.dp)
            .glassOrSolid(ButtonShape, GlassLevel.Chip).clip(ButtonShape)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
            .padding(contentPadding),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) TextPrimary else TextPrimary.copy(alpha = 0.4f),
            LocalTextStyle provides TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        ) { content() }
    }
}

/** A glass container for content that sits on the page (text, archive listings, hex dumps, info blocks). */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
    level: GlassLevel = GlassLevel.Card,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glassOrSolid(shape, level).clip(shape), content = content)
}

/** Small status label (zoom level, page count, badges) in the same glass. */
@Composable
fun GlassPill(text: String, modifier: Modifier = Modifier, mono: Boolean = true) {
    Text(
        text, color = TextSecondary, fontSize = 11.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        modifier = modifier.glassOrSolid(RoundedCornerShape(50), GlassLevel.Chip).padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** Full-size glass sheet that holds a document viewer, inset from the screen edges like every other card. */
@Composable
fun ViewerPanel(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
        GlassPanel(Modifier.fillMaxSize(), content = content)
    }
}


/** Text-field look shared by every input in the app. */
val GlassFieldShape = RoundedCornerShape(16.dp)

@Composable
fun glassFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent, unfocusedBorderColor = app.feldkit.ui.theme.GlassBorder, errorBorderColor = app.feldkit.ui.theme.AccentRed,
    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent,
    focusedLabelColor = Accent, unfocusedLabelColor = app.feldkit.ui.theme.TextTertiary,
    focusedContainerColor = app.feldkit.ui.theme.Fill1, unfocusedContainerColor = app.feldkit.ui.theme.Fill1, errorContainerColor = app.feldkit.ui.theme.Fill1,
)

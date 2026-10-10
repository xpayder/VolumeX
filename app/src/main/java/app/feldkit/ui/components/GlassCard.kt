package app.feldkit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.feldkit.ui.theme.GlassBorder
import app.feldkit.ui.theme.GlassEffect
import app.feldkit.ui.theme.GlassWhite12
import app.feldkit.ui.theme.GlassWhite16
import app.feldkit.ui.theme.GlassWhite8
import app.feldkit.ui.theme.glassOrSolid
import app.feldkit.ui.theme.glassCardBackground

/**
 * Liquid Glass card container.
 *
 * Renders a frosted-glass surface with a subtle gradient and border.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    elevated: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier.glassOrSolid(shape, if (elevated) app.feldkit.ui.theme.GlassLevel.Bar else app.feldkit.ui.theme.GlassLevel.Card),
        content = content
    )
}

/**
 * A glass card with a colored accent left border.
 */
@Composable
fun AccentGlassCard(
    modifier: Modifier = Modifier,
    accentColor: Color = app.feldkit.ui.theme.Accent,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = GlassEffect.DefaultShape
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(GlassWhite16, GlassWhite8)
                )
            )
            .border(1.dp, GlassBorder, shape)
            .border(
                width = 2.dp,
                brush = Brush.verticalGradient(
                    listOf(accentColor, accentColor.copy(alpha = 0.3f))
                ),
                shape = shape
            ),
        content = content
    )
}

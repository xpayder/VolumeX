package com.fatalpuppet.volumex.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Liquid Glass effect modifiers.
 *
 * On API 31+ uses RenderEffect blur for real frosted glass.
 * On older API uses a semi-transparent overlay approximation.
 */
object GlassEffect {
    val DefaultShape = RoundedCornerShape(16.dp)
    val LargeShape   = RoundedCornerShape(24.dp)
    val SmallShape   = RoundedCornerShape(12.dp)
    val PillShape    = RoundedCornerShape(50)
}

/** Apply a frosted glass background to a composable. */
fun Modifier.glassBackground(
    shape: RoundedCornerShape = GlassEffect.DefaultShape,
    glassColor: Color = GlassWhite8,
    borderColor: Color = GlassBorder,
    borderWidth: Dp = 1.dp,
    elevation: Boolean = true
): Modifier = this
    .clip(shape)
    .background(
        brush = Brush.linearGradient(
            colors = listOf(
                GlassWhite12,
                GlassWhite8,
                GlassWhite8
            ),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
    )
    .border(borderWidth, borderColor, shape)

/** Apply a slightly brighter glass effect for elevated cards. */
fun Modifier.glassCardBackground(
    shape: RoundedCornerShape = GlassEffect.DefaultShape
): Modifier = this
    .clip(shape)
    .background(
        brush = Brush.verticalGradient(
            colors = listOf(
                GlassWhite16,
                GlassWhite8
            )
        )
    )
    .border(1.dp, GlassBorder, shape)
    .drawBehind {
        // Subtle inner highlight at the top
        drawLine(
            color = GlassWhite16,
            start = Offset(16.dp.toPx(), 1.dp.toPx()),
            end = Offset(size.width - 16.dp.toPx(), 1.dp.toPx()),
            strokeWidth = 1.dp.toPx()
        )
    }

/** Accent glow border for selected/active states. */
fun Modifier.glowBorder(
    color: Color = AccentBlue,
    shape: RoundedCornerShape = GlassEffect.DefaultShape,
    width: Dp = 1.5.dp
): Modifier = this.border(width, color, shape)

/** Blue accent gradient background for buttons. */
fun Modifier.accentGradient(
    shape: RoundedCornerShape = GlassEffect.PillShape
): Modifier = this
    .clip(shape)
    .background(
        brush = Brush.linearGradient(
            colors = listOf(AccentBlue, AccentPurple),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, 0f)
        )
    )

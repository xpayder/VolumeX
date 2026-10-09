package com.fatalpuppet.volumex.ui.theme

import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass

/**
 * Liquid-glass surface (Haze 2 Glass: refraction, depth blur, tint and a specular rim) over whatever is drawn
 * with `Modifier.hazeSource(state)` behind it. A hairline gradient border adds the lit-edge look.
 */
@OptIn(ExperimentalHazeApi::class)
fun Modifier.liquidGlass(state: HazeState, shape: RoundedCornerShape, tint: Color = Color(0xA60B1016)): Modifier = this
    .hazeGlass(input = HazeInput.Sources(state), style = GlassStyle.regular.then { tint(tint); shape(shape) })
    .border(1.dp, Brush.verticalGradient(listOf(Color(0x59FFFFFF), Color(0x14FFFFFF))), shape)


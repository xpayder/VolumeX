package com.fatalpuppet.volumex.ui.theme

import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import dev.chrisbanes.haze.hazeSource
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
fun Modifier.liquidGlass(state: HazeState, shape: RoundedCornerShape, tint: Color = Color(0xD90B1016), scrim: Float = 0.78f): Modifier = this
    .hazeGlass(input = HazeInput.Sources(state), style = GlassStyle.regular.then { tint(tint); shape(shape) })
    .background(Color(0xFF0B1016).copy(alpha = scrim), shape)
    .border(1.dp, Brush.verticalGradient(listOf(Color(0x59FFFFFF), Color(0x14FFFFFF))), shape)


/** The haze state of the nearest [AmbientBackdrop]; surfaces fall back to a solid card when it is absent. */
val LocalHaze = androidx.compose.runtime.compositionLocalOf<HazeState?> { null }

/** Glass when a backdrop is provided, otherwise the plain dark card. */
@androidx.compose.runtime.Composable
fun Modifier.glassOrSolid(shape: RoundedCornerShape, solid: Color = DarkSurface): Modifier {
    val s = LocalHaze.current
    return if (s != null) liquidGlass(s, shape, Color(0xCC0B1016))
    else this.clip(shape).background(solid).border(1.dp, GlassBorderFaint, shape)
}

/** Soft colour field (blue / violet / teal glows on the dark base) that gives glass something to refract. */
@androidx.compose.runtime.Composable
fun AmbientBackdrop(state: HazeState, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier.hazeSource(state).background(DeepNavy).drawBehind {
            fun glow(c: Color, x: Float, y: Float, r: Float) = drawCircle(
                Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(size.width * x, size.height * y), radius = r),
                radius = r, center = Offset(size.width * x, size.height * y)
            )
            glow(Color(0x663D7BFF), 0.15f, 0.12f, size.width * 0.9f)
            glow(Color(0x55A855F7), 0.95f, 0.38f, size.width * 0.8f)
            glow(Color(0x4430D5C8), 0.2f, 0.78f, size.width * 0.9f)
        }
    )
}

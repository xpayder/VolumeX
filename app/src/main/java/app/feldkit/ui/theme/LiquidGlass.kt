package app.feldkit.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource

/**
 * The one glass vocabulary of the app. Every floating surface (bars, cards, sheets, dialogs, chips) picks a level, so
 * blur, opacity, refraction and the lit edge stay consistent across screens.
 */
enum class GlassLevel(
    internal val scrim: Float,
    internal val blur: Float,
    internal val sheen: Float,
    internal val edgeTop: Float,
    internal val edgeBottom: Float,
) {
    /** Floating top bars, docks and toolbars. */
    Bar(0.46f, 28f, 0.10f, 0.42f, 0.07f),
    /** Content cards that live on the page. */
    Card(0.42f, 22f, 0.07f, 0.28f, 0.05f),
    /** Sheets and dialogs: densest, so text always reads. */
    Sheet(0.70f, 34f, 0.08f, 0.36f, 0.06f),
    /** Small tappable chips and buttons. */
    Chip(0.38f, 18f, 0.10f, 0.36f, 0.08f),
}

private val GlassBase = Color(0xFF0B1016)

/**
 * Frosted glass: Haze blur of everything registered on [state], a dark tint for contrast, a soft top sheen and a lit
 * hairline edge. (Haze's refracting `hazeGlass` renders no blur on the test device, so the effect is built from the
 * blur path that is verified there.)
 */
@OptIn(ExperimentalHazeApi::class)
fun Modifier.liquidGlass(state: HazeState, shape: RoundedCornerShape, level: GlassLevel = GlassLevel.Bar): Modifier = this
    .clip(shape)
    .hazeBlur(
        input = HazeInput.Sources(state),
        style = HazeBlurStyle {
            blurRadius(level.blur.dp)
            backgroundColor(GlassBase.copy(alpha = level.scrim))
            noiseFactor(0.025f)
        }
    )
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = level.sheen), Color.Transparent)), shape)
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = level.edgeTop), Color.White.copy(alpha = level.edgeBottom))), shape)

/** Same look as [liquidGlass] for surfaces over video, where nothing can be blurred: a denser tint, the same edge. */
fun Modifier.glassScrim(shape: RoundedCornerShape, level: GlassLevel = GlassLevel.Sheet): Modifier = this
    .background(GlassBase.copy(alpha = (level.scrim + 0.18f).coerceAtMost(0.92f)), shape)
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = level.sheen), Color.Transparent)), shape)
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = level.edgeTop), Color.White.copy(alpha = level.edgeBottom))), shape)

/** The app-wide haze state, provided once at the root by [AmbientBackdrop]. */
val LocalHaze = compositionLocalOf<HazeState?> { null }

/** Glass when a backdrop is provided, otherwise the plain dark card. */
@Composable
fun Modifier.glassOrSolid(shape: RoundedCornerShape, level: GlassLevel = GlassLevel.Card, solid: Color = DarkSurface): Modifier {
    val s = LocalHaze.current
    return if (s != null) liquidGlass(s, shape, level)
    else this.clip(shape).background(solid).border(1.dp, GlassBorderFaint, shape)
}

/**
 * Soft colour field that gives glass something to refract. Lives once at the root behind every screen, so moving
 * between screens never changes the world the glass is floating over.
 */
@Composable
fun AmbientBackdrop(state: HazeState, modifier: Modifier = Modifier) {
    Box(
        modifier.hazeSource(state).background(DeepNavy).drawBehind {
            fun glow(c: Color, x: Float, y: Float, r: Float) = drawCircle(
                Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(size.width * x, size.height * y), radius = r),
                radius = r, center = Offset(size.width * x, size.height * y)
            )
            // One hue family (mint) at three strengths, plus a faint warm counterweight: no single colour owns the screen.
            glow(Color(0x4038D9B8), 0.10f, 0.08f, size.width * 0.90f)
            glow(Color(0x1FE8B85C), 0.98f, 0.48f, size.width * 0.80f)
            glow(Color(0x2A2FA88C), 0.20f, 0.88f, size.width * 0.95f)
        }
    )
}

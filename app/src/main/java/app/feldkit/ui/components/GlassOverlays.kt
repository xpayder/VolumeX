package app.feldkit.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feldkit.ui.theme.GlassLevel
import app.feldkit.ui.theme.LocalHaze
import app.feldkit.ui.theme.TextPrimary
import app.feldkit.ui.theme.TextSecondary
import app.feldkit.ui.theme.liquidGlass
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Shared motion curve for every enter/exit in the app. */
val GlassEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
const val GlassMs = 380

/**
 * Overlay layers (dialogs, sheets, menus) are drawn here, inside the main window, so the same haze backdrop that
 * frosts bars also frosts them. A layer that leaves the composition plays its exit animation before it is dropped.
 */
class OverlayHost {
    internal class Layer {
        var content by mutableStateOf<@Composable (Boolean) -> Unit>({})
        var visible by mutableStateOf(false)
    }
    internal val layers = mutableStateListOf<Layer>()

    @Composable
    fun Render() {
        for (layer in layers.toList()) {
            androidx.compose.runtime.key(layer) {
                Box(Modifier.fillMaxSize()) { layer.content(layer.visible) }
                LaunchedEffect(layer.visible) {
                    if (!layer.visible) { delay(GlassMs + 40L); layers.remove(layer) }
                }
            }
        }
    }
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHost?> { null }

/** Hoists [content] into the root [OverlayHost]; [content] receives whether the layer is currently shown. */
@Composable
fun GlassPortal(content: @Composable (visible: Boolean) -> Unit) {
    val host = LocalOverlayHost.current
    if (host == null) { content(true); return }
    val layer = remember { OverlayHost.Layer() }
    SideEffect { layer.content = content }
    DisposableEffect(layer) {
        host.layers.add(layer)
        layer.visible = true
        onDispose { layer.visible = false }
    }
}

@Composable
private fun Scrim(visible: Boolean, onDismiss: () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(tween(260)), exit = fadeOut(tween(260))) {
        Box(
            Modifier.fillMaxSize().background(Color(0x99050810))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        )
    }
}

/** Drop-in replacement for AlertDialog, drawn as frosted glass. */
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit = {},
    dismissButton: (@Composable () -> Unit)? = null,
) = GlassPortal { visible ->
    val haze = LocalHaze.current
    BackHandler(enabled = visible, onBack = onDismissRequest)
    Box(Modifier.fillMaxSize()) {
        Scrim(visible, onDismissRequest)
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(GlassMs, easing = GlassEase)) + scaleIn(tween(GlassMs, easing = GlassEase), initialScale = 0.92f),
            exit = fadeOut(tween(220)) + scaleOut(tween(220), targetScale = 0.96f),
        ) {
            val shape = RoundedCornerShape(30.dp)
            Column(
                Modifier.safeDrawingPadding().padding(horizontal = 28.dp).widthIn(max = 380.dp).fillMaxWidth()
                    .let { if (haze != null) it.liquidGlass(haze, shape, GlassLevel.Sheet) else it.background(Color(0xF00B1016), shape) }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = 24.dp, vertical = 22.dp)
            ) {
                if (title != null) androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)) { title() }
                if (text != null) {
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides androidx.compose.ui.text.TextStyle(color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)) {
                        Box(Modifier.verticalScroll(rememberScrollState())) { text() }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    dismissButton?.let { it(); Spacer(Modifier.width(8.dp)) }
                    confirmButton()
                }
            }
        }
    }
}

/** Drop-in replacement for ModalBottomSheet: a glass sheet that slides up and can be dragged down to dismiss. */
@Composable
fun GlassSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) = GlassPortal { visible ->
    val haze = LocalHaze.current
    var drag by remember { mutableFloatStateOf(0f) }
    BackHandler(enabled = visible, onBack = onDismissRequest)
    Box(Modifier.fillMaxSize()) {
        Scrim(visible, onDismissRequest)
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(GlassMs + 60, easing = GlassEase)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(260)),
        ) {
            val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
            Column(
                Modifier.fillMaxWidth().heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.88f).offset { IntOffset(0, drag.roundToInt().coerceAtLeast(0)) }
                    .let { if (haze != null) it.liquidGlass(haze, shape, GlassLevel.Sheet) else it.background(Color(0xF00B1016), shape) }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { _, dy -> drag = (drag + dy).coerceAtLeast(0f) },
                            onDragEnd = { if (drag > 140f) onDismissRequest() else drag = 0f },
                            onDragCancel = { drag = 0f },
                        )
                    }
                    .navigationBarsPadding()
            ) {
                Box(Modifier.padding(top = 10.dp, bottom = 6.dp).align(Alignment.CenterHorizontally).width(38.dp).height(4.dp).background(Color(0x55FFFFFF), CircleShape))
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides TextPrimary) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** Small glass popover pinned to a corner of the screen; replaces DropdownMenu. */
@Composable
fun GlassMenu(
    onDismissRequest: () -> Unit,
    alignment: Alignment = Alignment.TopEnd,
    edgePadding: androidx.compose.ui.unit.Dp = 64.dp,
    content: @Composable ColumnScope.() -> Unit,
) = GlassPortal { visible ->
    val haze = LocalHaze.current
    BackHandler(enabled = visible, onBack = onDismissRequest)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismissRequest)
        )
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(alignment),
            enter = fadeIn(tween(240, easing = GlassEase)) + scaleIn(tween(300, easing = GlassEase), initialScale = 0.9f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.95f),
        ) {
            val shape = RoundedCornerShape(22.dp)
            Column(
                Modifier.safeDrawingPadding().padding(horizontal = 12.dp, vertical = edgePadding).width(236.dp)
                    .let { if (haze != null) it.liquidGlass(haze, shape, GlassLevel.Sheet) else it.background(Color(0xF00B1016), shape) }
                    .padding(vertical = 6.dp),
                content = content
            )
        }
    }
}

/** A menu row, same typography as everywhere else. */
@Composable
fun GlassMenuItem(label: String, tint: Color = TextPrimary, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { icon(); Spacer(Modifier.width(12.dp)) }
        Text(label, color = tint, fontSize = 15.sp)
    }
}

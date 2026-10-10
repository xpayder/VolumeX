package app.feldkit.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch

/**
 * Pinch-to-zoom, drag-to-pan and double-tap-to-zoom around [content]. A one-finger drag at 1x is left alone so a
 * parent pager / list can still scroll; while zoomed in the gesture belongs to the content.
 */
@Composable
fun Zoomable(
    modifier: Modifier = Modifier,
    maxScale: Float = 6f,
    onTap: (() -> Unit)? = null,
    onZoomedChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val zoomed = scale > 1.01f
    LaunchedEffect(zoomed) { onZoomedChange(zoomed) }

    fun clamp(o: Offset, s: Float): Offset {
        val mx = (s - 1f) * size.width / 2f; val my = (s - 1f) * size.height / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap?.invoke() },
                    onDoubleTap = { pos ->
                        val fromS = scale; val fromO = offset
                        val toS = if (fromS > 1.01f) 1f else 2.5f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val toO = if (toS == 1f) Offset.Zero else clamp((center - pos) * (toS - 1f), toS)
                        scope.launch {
                            animate(0f, 1f) { t, _ -> scale = fromS + (toS - fromS) * t; offset = fromO + (toO - fromO) * t }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        val multi = ev.changes.count { it.pressed } > 1
                        if (multi || scale > 1.01f) {
                            val zoom = ev.calculateZoom(); val pan = ev.calculatePan()
                            val ns = (scale * zoom).coerceIn(1f, maxScale)
                            offset = if (ns <= 1.01f) Offset.Zero else clamp(offset + pan, ns)
                            scale = ns
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
    ) { content() }
}

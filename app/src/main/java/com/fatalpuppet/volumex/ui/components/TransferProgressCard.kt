package com.fatalpuppet.volumex.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fatalpuppet.volumex.storage.TransferProgress
import com.fatalpuppet.volumex.ui.theme.*

@Composable
fun TransferProgressCard(
    progress: TransferProgress,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit = {}
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        elevated = true
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Animated icon
                val infiniteTransition = rememberInfiniteTransition(label = "transfer")
                val angle by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "rotation"
                )

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentBlue.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (progress.isComplete) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = AccentGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    } else if (progress.error != null) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = AccentRed,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = AccentBlue,
                            strokeWidth = 2.dp
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = progress.fileName,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            progress.error != null -> progress.error
                            progress.isComplete && progress.verified == true -> "Verified · SHA-256 ${progress.checksum?.take(12) ?: ""}…"
                            progress.isComplete -> "Complete"
                            progress.phase.isNotEmpty() -> "${progress.phase}… ${progress.progressPercent}%"
                            else -> progress.formattedProgress
                        },
                        color = when {
                            progress.error != null -> AccentRed
                            progress.isComplete -> AccentGreen
                            else -> TextTertiary
                        },
                        fontSize = 12.sp
                    )
                }

                if (!progress.isComplete && progress.error == null) {
                    IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = TextTertiary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (!progress.isComplete && progress.error == null && progress.totalBytes > 0) {
                Spacer(Modifier.height(10.dp))

                // Progress bar with animated shimmer
                val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
                val shimmerOffset by shimmerTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "shimmer"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(GlassWhite8)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.progressFraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(AccentBlue, AccentPurple)
                                )
                            )
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${progress.progressPercent}%",
                    color = TextTertiary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

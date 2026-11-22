package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.BrandBlue
import com.example.ui.theme.BrandCyan
import com.example.ui.theme.BrandIndigo
import com.example.ui.theme.SpeakerPalette
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.StatusRose
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

@Composable
fun SectionHeader(
    title: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    onInfoClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (onInfoClick != null) {
                IconButton(onClick = onInfoClick, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = "Info $title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        if (actionLabel != null && onActionClick != null) {
            TextButton(onClick = onActionClick) {
                Text(actionLabel, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun SparklineStatCard(
    title: String,
    numericValue: Float,
    isDecimal: Boolean = false,
    unitSuffix: String = "",
    deltaPercent: Int? = null,
    sparklinePoints: List<Float>,
    icon: ImageVector,
    tint: Color,
    reduceMotion: Boolean = false,
    modifier: Modifier = Modifier
) {
    val anim = remember(numericValue, reduceMotion) {
        Animatable(if (reduceMotion) numericValue else 0f)
    }
    LaunchedEffect(numericValue, reduceMotion) {
        if (reduceMotion) {
            anim.snapTo(numericValue)
        } else {
            anim.animateTo(numericValue, animationSpec = tween(550, easing = FastOutSlowInEasing))
        }
    }
    val displayVal = if (isDecimal) {
        String.format(Locale.US, "%.1f", anim.value)
    } else {
        anim.value.toInt().toString()
    } + unitSuffix

    NotulenCard(modifier = modifier.width(164.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(tint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = title, tint = tint, modifier = Modifier.size(18.dp))
                }
                if (deltaPercent != null) {
                    val isPositive = deltaPercent >= 0
                    val trendColor = if (isPositive) StatusEmerald else StatusRose
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = trendColor.copy(alpha = 0.14f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isPositive) Icons.AutoMirrored.Rounded.TrendingUp else Icons.AutoMirrored.Rounded.TrendingDown,
                                contentDescription = null,
                                tint = trendColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "${if (isPositive) "+" else ""}$deltaPercent%",
                                style = MaterialTheme.typography.labelSmall,
                                color = trendColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = displayVal,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            MiniSparklineCanvas(
                points = sparklinePoints,
                lineColor = tint,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
            )
        }
    }
}

@Composable
fun MiniSparklineCanvas(
    points: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val safe = if (points.size >= 2) points else listOf(0f, 0f)
        val maxV = max(1f, safe.maxOrNull() ?: 1f)
        val minV = safe.minOrNull() ?: 0f
        val range = (maxV - minV).coerceAtLeast(1f)
        val stepX = size.width / (safe.size - 1).coerceAtLeast(1)

        val linePath = Path()
        val fillPath = Path()
        safe.forEachIndexed { idx, v ->
            val x = idx * stepX
            val norm = ((v - minV) / range).coerceIn(0f, 1f)
            val y = size.height - (norm * (size.height - 6f)) - 3f
            if (idx == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(size.width, size.height)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                listOf(lineColor.copy(alpha = 0.25f), lineColor.copy(alpha = 0.0f))
            )
        )
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun ProgressRing(
    progress: Float,
    centerLabel: String,
    sizeDp: Int = 84,
    ringColor: Color = BrandBlue,
    reduceMotion: Boolean = false,
    modifier: Modifier = Modifier
) {
    val target = progress.coerceIn(0f, 1f)
    val anim = remember(target, reduceMotion) { Animatable(if (reduceMotion) target else 0f) }
    LaunchedEffect(target, reduceMotion) {
        if (reduceMotion) anim.snapTo(target)
        else anim.animateTo(target, animationSpec = tween(650, easing = FastOutSlowInEasing))
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier.size(sizeDp.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeW = 9.dp.toPx()
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(ringColor, BrandCyan, BrandIndigo, ringColor)),
                startAngle = -90f,
                sweepAngle = 360f * anim.value,
                useCenter = false,
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )
        }
        Text(
            text = centerLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun AvatarStack(
    names: List<String>,
    modifier: Modifier = Modifier
) {
    val unique = names.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    if (unique.isEmpty()) return
    val visible = unique.take(3)
    val extra = (unique.size - visible.size).coerceAtLeast(0)
    val borderColor = MaterialTheme.colorScheme.surface

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        visible.forEachIndexed { idx, name ->
            val initials = name.split(" ").take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
                .ifBlank { name.take(2).uppercase() }
            val bg = SpeakerPalette[idx % SpeakerPalette.size]
            Box(
                modifier = Modifier
                    .offset(x = (-6 * idx).dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .border(1.5.dp, borderColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials.take(2),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (extra > 0) {
            Box(
                modifier = Modifier
                    .offset(x = (-6 * visible.size).dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.5.dp, borderColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+$extra",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun TopicChip(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun WaveformMini(
    seed: String,
    barCount: Int = 14,
    barColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    val heights = remember(seed, barCount) {
        val h = abs(seed.hashCode())
        List(barCount) { idx ->
            val v = ((h shr (idx % 16)) xor (idx * 37)) and 0xF
            (0.25f + (v / 15f) * 0.75f).coerceIn(0.2f, 1f)
        }
    }
    Row(
        modifier = modifier.height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        heights.forEach { frac ->
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height((16 * frac).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor.copy(alpha = 0.65f))
            )
        }
    }
}

@Composable
fun HeroGreetingBanner(
    greeting: String,
    dateText: String,
    tenantName: String,
    displayName: String,
    photoUrl: String?,
    isAdminOrOwner: Boolean,
    connectionHealthy: Boolean?,
    onAiBadgeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
        shadowElevation = 2.dp
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Decorative ambient shapes
            Canvas(modifier = Modifier.matchParentSize()) {
                drawRect(
                    brush = Brush.linearGradient(
                        listOf(
                            BrandBlue.copy(alpha = 0.12f),
                            BrandIndigo.copy(alpha = 0.08f),
                            BrandCyan.copy(alpha = 0.10f)
                        )
                    )
                )
                drawCircle(
                    color = BrandBlue.copy(alpha = 0.10f),
                    radius = size.height * 0.65f,
                    center = Offset(size.width * 0.92f, size.height * 0.15f)
                )
                drawCircle(
                    color = BrandCyan.copy(alpha = 0.10f),
                    radius = size.height * 0.45f,
                    center = Offset(size.width * 0.72f, size.height * 0.95f)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UserAvatar(photoUrl = photoUrl, displayName = displayName, sizeDp = 48)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = greeting,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$dateText • $tenantName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isAdminOrOwner) {
                    val isOk = connectionHealthy != false
                    Surface(
                        onClick = onAiBadgeClick,
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, (if (isOk) StatusEmerald else StatusRose).copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isOk) StatusEmerald else StatusRose)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isOk) "AI Siap" else "Perlu Cek",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WeeklyActivityBarChart(
    dailyData: List<Triple<String, Int, Float>>, // dayLabel, meetingCount, hours
    modifier: Modifier = Modifier
) {
    var modeHours by remember { mutableStateOf(false) }
    val values = dailyData.map { if (modeHours) it.third else it.second.toFloat() }
    val maxVal = max(1f, values.maxOrNull() ?: 1f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val normalBrush = Brush.verticalGradient(listOf(BrandBlue.copy(alpha = 0.7f), BrandIndigo.copy(alpha = 0.55f)))
    val todayBrush = Brush.verticalGradient(listOf(BrandCyan, BrandBlue))

    NotulenCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Aktivitas Minggu Ini", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = !modeHours,
                        onClick = { modeHours = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) { Text("Rapat", style = MaterialTheme.typography.labelSmall) }
                    SegmentedButton(
                        selected = modeHours,
                        onClick = { modeHours = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text("Jam", style = MaterialTheme.typography.labelSmall) }
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                val count = dailyData.size.coerceAtLeast(1)
                val slotWidth = size.width / count
                val barWidth = (slotWidth * 0.48f).coerceAtMost(26.dp.toPx())
                values.forEachIndexed { idx, v ->
                    val x = idx * slotWidth + (slotWidth - barWidth) / 2f
                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(x, 0f),
                        size = Size(barWidth, size.height),
                        cornerRadius = CornerRadius(14f, 14f)
                    )
                    val barH = if (v <= 0f) 6f else (v / maxVal) * size.height
                    val isToday = idx == values.lastIndex
                    drawRoundRect(
                        brush = if (isToday) todayBrush else normalBrush,
                        topLeft = Offset(x, size.height - barH),
                        size = Size(barWidth, barH),
                        cornerRadius = CornerRadius(14f, 14f)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                dailyData.forEachIndexed { idx, (label, cnt, hrs) ->
                    val isToday = idx == dailyData.lastIndex
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (modeHours) String.format(Locale.US, "%.1f", hrs) else cnt.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableMeetingRow(
    isFavorite: Boolean,
    onSwipeFavorite: () -> Unit,
    onSwipeDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onSwipeFavorite()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onSwipeDelete()
                    false
                }
                else -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            val dir = dismissState.dismissDirection
            val bg = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> StatusAmber.copy(alpha = 0.22f)
                SwipeToDismissBoxValue.EndToStart -> StatusRose.copy(alpha = 0.22f)
                else -> Color.Transparent
            }
            val align = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                else -> Alignment.Center
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(bg)
                    .padding(horizontal = 20.dp),
                contentAlignment = align
            ) {
                if (dir == SwipeToDismissBoxValue.StartToEnd) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Star, contentDescription = "Favorit", tint = StatusAmber)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isFavorite) "Batal Favorit" else "Favorit", color = StatusAmber, fontWeight = FontWeight.Bold)
                    }
                } else if (dir == SwipeToDismissBoxValue.EndToStart) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Hapus", color = StatusRose, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Rounded.Delete, contentDescription = "Hapus", tint = StatusRose)
                    }
                }
            }
        }
    ) {
        content()
    }
}

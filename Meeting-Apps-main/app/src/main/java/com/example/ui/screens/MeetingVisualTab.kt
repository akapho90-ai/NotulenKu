package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.AppConstants
import com.example.data.model.Meeting
import com.example.data.model.VisualData
import com.example.export.ExportUtils
import com.example.export.VisualStyle
import com.example.export.VisualTheme
import com.example.ui.components.EmptyStateCard

@Composable
fun MeetingVisualTab(
    meeting: Meeting,
    watermarkEnabled: Boolean,
    aiBusy: Boolean,
    defaultStyle: String = "CARDS",
    onGenerateVisual: () -> Unit,
    onNotify: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedStyle by remember(defaultStyle) {
        mutableStateOf(
            runCatching { VisualStyle.valueOf(defaultStyle) }.getOrDefault(VisualStyle.CARDS)
        )
    }
    var selectedTheme by remember { mutableStateOf(VisualTheme.BIRU) }
    var exporting by remember { mutableStateOf(false) }

    val visualData: VisualData? = meeting.visualData ?: if (meeting.ringkasan.isNotBlank()) {
        VisualData(
            judul = meeting.judul,
            poinUtama = meeting.poinUtama.take(4),
            keputusan = meeting.keputusan.take(4),
            angkaKunci = meeting.angkaKunci.take(4).map { "${it.label}: ${it.nilai}" },
            risiko = meeting.risiko.take(3),
            tindakan = meeting.actionItems.take(4).map { "${it.siapa} - ${it.tugas}" }
        )
    } else null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (visualData == null) {
            EmptyStateCard(
                icon = Icons.Rounded.AutoAwesome,
                title = "Data visual belum tersedia",
                subtitle = "Buat infografis visual otomatis dari notulen rapat ini.",
                actionLabel = if (aiBusy) "Menyusun Visual..." else "Buat Visual dengan AI",
                onAction = { if (!aiBusy) onGenerateVisual() }
            )
            return
        }

        // Style selector
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            VisualStyle.entries.forEachIndexed { idx, style ->
                SegmentedButton(
                    selected = selectedStyle == style,
                    onClick = { selectedStyle = style },
                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = VisualStyle.entries.size)
                ) {
                    Text(style.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // Color theme chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VisualTheme.entries.forEach { theme ->
                FilterChip(
                    selected = selectedTheme == theme,
                    onClick = { selectedTheme = theme },
                    label = { Text(theme.label) }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onGenerateVisual, enabled = !aiBusy) {
                Text(if (aiBusy) "Memperbarui..." else "Segarkan AI")
            }
        }

        // 4:5 Canvas Preview
        val bgBrush = Brush.verticalGradient(
            listOf(Color(selectedTheme.bgTop), Color(selectedTheme.bgBottom))
        )
        val cardColor = Color(selectedTheme.cardBg)
        val accentColor = Color(selectedTheme.accent)
        val textPrim = Color(selectedTheme.textPrimary)
        val textSec = Color(selectedTheme.textSecondary)

        Surface(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 5f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgBrush)
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        color = accentColor,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = selectedStyle.label.uppercase(),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = visualData.judul,
                        color = textPrim,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = ExportUtils.formatDate(meeting.createdAt?.toDate()),
                        color = textSec,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (selectedStyle) {
                            VisualStyle.CARDS -> VisualCardsPreview(visualData, cardColor, accentColor, textPrim)
                            VisualStyle.MIND_MAP -> VisualMindMapPreview(visualData, cardColor, accentColor, textPrim)
                            VisualStyle.TIMELINE -> VisualTimelinePreview(visualData, cardColor, accentColor, textPrim)
                        }
                    }

                    if (watermarkEnabled) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Dibuat dengan ${AppConstants.APP_NAME}",
                            color = textSec,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        // Export Actions
        if (exporting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Menyiapkan gambar...", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    exporting = true
                    ExportUtils.renderAndShareVisualImage(
                        context, meeting, visualData, selectedStyle, selectedTheme, watermarkEnabled, asJpg = false
                    ).onFailure { onNotify("Gagal mengekspor gambar PNG.") }
                    exporting = false
                },
                modifier = Modifier.weight(1f).testTag("export_png_button")
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("PNG")
            }
            OutlinedButton(
                onClick = {
                    exporting = true
                    ExportUtils.renderAndShareVisualImage(
                        context, meeting, visualData, selectedStyle, selectedTheme, watermarkEnabled, asJpg = true
                    ).onFailure { onNotify("Gagal mengekspor gambar JPG.") }
                    exporting = false
                },
                modifier = Modifier.weight(1f).testTag("export_jpg_button")
            ) {
                Text("JPG")
            }
            OutlinedButton(
                onClick = {
                    exporting = true
                    ExportUtils.renderAndShareVisualImage(
                        context, meeting, visualData, selectedStyle, selectedTheme, watermarkEnabled, asJpg = false
                    ).onFailure { onNotify("Gagal membagikan visual.") }
                    exporting = false
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Bagikan")
            }
        }
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Gaya Sketsa AI — Segera Hadir")
        }
    }
}

@Composable
private fun VisualCardsPreview(
    visual: VisualData,
    cardColor: Color,
    accent: Color,
    textPrim: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (visual.angkaKunci.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                visual.angkaKunci.take(2).forEach { metric ->
                    Surface(
                        color = cardColor,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("ANGKA KUNCI", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(metric, color = textPrim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        PreviewSectionCard("KEPUTUSAN", visual.keputusan.take(3), cardColor, accent, textPrim)
        PreviewSectionCard("TINDAKAN", visual.tindakan.take(3), cardColor, accent, textPrim)
        if (visual.risiko.isNotEmpty()) {
            PreviewSectionCard("RISIKO", visual.risiko.take(2), cardColor, accent, textPrim)
        }
    }
}

@Composable
private fun VisualMindMapPreview(
    visual: VisualData,
    cardColor: Color,
    accent: Color,
    textPrim: Color
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val targets = listOf(
                Offset(size.width * 0.25f, size.height * 0.22f),
                Offset(size.width * 0.75f, size.height * 0.22f),
                Offset(size.width * 0.25f, size.height * 0.78f),
                Offset(size.width * 0.75f, size.height * 0.78f)
            )
            targets.forEach { t ->
                drawLine(color = accent, start = Offset(cx, cy), end = t, strokeWidth = 3.dp.toPx())
            }
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PreviewSectionCard("POIN", visual.poinUtama.take(2), cardColor, accent, textPrim, Modifier.weight(1f))
                PreviewSectionCard("KEPUTUSAN", visual.keputusan.take(2), cardColor, accent, textPrim, Modifier.weight(1f))
            }
            Surface(
                color = accent,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = visual.judul.take(30),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PreviewSectionCard("ANGKA", visual.angkaKunci.take(2), cardColor, accent, textPrim, Modifier.weight(1f))
                PreviewSectionCard("TINDAKAN", visual.tindakan.take(2), cardColor, accent, textPrim, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun VisualTimelinePreview(
    visual: VisualData,
    cardColor: Color,
    accent: Color,
    textPrim: Color
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PreviewSectionCard("1. POIN PEMBAHASAN", visual.poinUtama.take(2), cardColor, accent, textPrim)
        Icon(Icons.Rounded.ArrowDownward, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        PreviewSectionCard("2. KEPUTUSAN", visual.keputusan.take(2), cardColor, accent, textPrim)
        Icon(Icons.Rounded.ArrowDownward, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        PreviewSectionCard("3. TINDAKAN LANJUT", visual.tindakan.take(2), cardColor, accent, textPrim)
    }
}

@Composable
private fun PreviewSectionCard(
    title: String,
    items: List<String>,
    cardColor: Color,
    accent: Color,
    textPrim: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = cardColor,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(title, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(3.dp))
            if (items.isEmpty()) {
                Text("-", color = textPrim, fontSize = 11.sp)
            } else {
                items.forEach { line ->
                    Text(
                        text = "• $line",
                        color = textPrim,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

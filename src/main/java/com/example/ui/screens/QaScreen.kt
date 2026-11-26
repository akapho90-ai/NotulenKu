package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.Meeting
import com.example.data.model.QaMessage
import com.example.ui.components.EmptyStateCard

@Composable
fun QaScreen(
    meetings: List<Meeting>,
    messages: List<QaMessage>,
    isLoading: Boolean,
    onAskQuestion: (question: String, scope: String, meetingId: String?) -> Unit,
    onOpenMeeting: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    var scope by remember { mutableStateOf("all") } // all | meeting
    var selectedMeetingId by remember(meetings) { mutableStateOf(meetings.firstOrNull()?.id) }
    var showMeetingPicker by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    val quickPrompts = listOf(
        "Apa keputusan terakhir?",
        "Apa saja tugas saya?",
        "Rangkum rapat minggu ini"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                    listOf("all" to "Semua Rapat", "meeting" to "Rapat Tertentu").forEachIndexed { idx, (key, label) ->
                        SegmentedButton(
                            selected = scope == key,
                            onClick = { scope = key },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = 2)
                        ) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (messages.isNotEmpty()) {
                    IconButton(onClick = onClearHistory) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Bersihkan riwayat tanya jawab")
                    }
                }
            }

            if (scope == "meeting" && meetings.isNotEmpty()) {
                val selectedTitle = meetings.firstOrNull { it.id == selectedMeetingId }?.judul ?: "Pilih rapat"
                Box(modifier = Modifier.padding(top = 8.dp)) {
                    Surface(
                        onClick = { showMeetingPicker = true },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Rapat: $selectedTitle",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMeetingPicker,
                        onDismissRequest = { showMeetingPicker = false }
                    ) {
                        meetings.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.judul) },
                                onClick = {
                                    selectedMeetingId = m.id
                                    showMeetingPicker = false
                                }
                            )
                        }
                    }
                }
            }

            // Quick prompt chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickPrompts.forEach { q ->
                    AssistChip(
                        onClick = { onAskQuestion(q, scope, if (scope == "meeting") selectedMeetingId else null) },
                        label = { Text(q) }
                    )
                }
            }

            // Messages list
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = Icons.Rounded.AutoAwesome,
                            title = "Tanya Jawab Lintas Rapat",
                            subtitle = "Ajukan pertanyaan apa pun tentang keputusan, action items, atau detail pembahasan. Jawaban selalu menyertakan sumber rapat yang dapat diketuk."
                        )
                    }
                } else {
                    items(messages, key = { it.id }) { msg ->
                        val isUser = msg.role == "user"
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                border = if (isUser) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier.widthIn(max = 520.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = msg.isi,
                                        color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (!isUser && msg.sumber.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Sumber:",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        msg.sumber.forEach { src ->
                                            Text(
                                                text = "↗ ${src.judulRapat} (${src.waktu})",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .clickable { onOpenMeeting(src.meetingId) }
                                                    .padding(vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (isLoading) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Mencari di catatan rapat...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Tanya tentang rapat Anda...") },
                    maxLines = 3,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("qa_input")
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val q = input.trim()
                        if (q.isNotBlank()) {
                            onAskQuestion(q, scope, if (scope == "meeting") selectedMeetingId else null)
                            input = ""
                        }
                    },
                    enabled = input.isNotBlank() && !isLoading,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("qa_send_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Kirim pertanyaan")
                }
            }
        }
    }
}

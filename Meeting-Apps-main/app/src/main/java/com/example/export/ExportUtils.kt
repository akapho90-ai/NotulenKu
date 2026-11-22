package com.example.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.example.core.AppConstants
import com.example.data.model.AuditLog
import com.example.data.model.Meeting
import com.example.data.model.UsageLog
import com.example.data.model.VisualData
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class VisualStyle(val label: String) {
    CARDS("Kartu Poin"),
    MIND_MAP("Mind Map"),
    TIMELINE("Alur Keputusan")
}

enum class VisualTheme(
    val label: String,
    val bgTop: Int,
    val bgBottom: Int,
    val cardBg: Int,
    val accent: Int,
    val textPrimary: Int,
    val textSecondary: Int
) {
    BIRU("Biru", 0xFFEFF6FF.toInt(), 0xFFDBEAFE.toInt(), 0xFFFFFFFF.toInt(), 0xFF2563EB.toInt(), 0xFF0F172A.toInt(), 0xFF475569.toInt()),
    HIJAU("Hijau", 0xFFECFDF5.toInt(), 0xFFD1FAE5.toInt(), 0xFFFFFFFF.toInt(), 0xFF059669.toInt(), 0xFF064E3B.toInt(), 0xFF047857.toInt()),
    ORANYE("Oranye", 0xFFFFF7ED.toInt(), 0xFFFFEDD5.toInt(), 0xFFFFFFFF.toInt(), 0xFFEA580C.toInt(), 0xFF431407.toInt(), 0xFF9A3412.toInt()),
    GELAP("Gelap", 0xFF0F172A.toInt(), 0xFF1E293B.toInt(), 0xFF1E293B.toInt(), 0xFF60A5FA.toInt(), 0xFFF8FAFC.toInt(), 0xFFCBD5E1.toInt())
}

object ExportUtils {

    fun copyToClipboard(context: Context, label: String, text: String) {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%02d:%02d".format(m, s)
    }

    fun formatDate(date: Date?): String {
        val d = date ?: Date()
        return SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID")).format(d)
    }

    fun buildTranscriptTxt(meeting: Meeting): String = buildString {
        appendLine(meeting.judul)
        appendLine("Tanggal: ${formatDate(meeting.createdAt?.toDate())}")
        appendLine("Durasi: ${formatDuration(meeting.durasiDetik)}")
        appendLine("=".repeat(40))
        appendLine()
        meeting.segments.forEach { seg ->
            appendLine("[${formatDuration(seg.mulaiDetik)}] ${seg.pembicara}: ${seg.teks}")
        }
    }

    fun buildTranscriptSrt(meeting: Meeting): String = buildString {
        meeting.segments.forEachIndexed { idx, seg ->
            appendLine(idx + 1)
            appendLine("${toSrtTime(seg.mulaiDetik)} --> ${toSrtTime(seg.selesaiDetik.coerceAtLeast(seg.mulaiDetik + 2))}")
            appendLine("${seg.pembicara}: ${seg.teks}")
            appendLine()
        }
    }

    private fun toSrtTime(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return "%02d:%02d:%02d,000".format(h, m, s)
    }

    fun buildMinutesText(meeting: Meeting): String = buildString {
        appendLine("NOTULEN RAPAT: ${meeting.judul}")
        appendLine("Tanggal: ${formatDate(meeting.createdAt?.toDate())} | Durasi: ${formatDuration(meeting.durasiDetik)}")
        appendLine()
        if (meeting.ringkasan.isNotBlank()) {
            appendLine("RINGKASAN:")
            appendLine(meeting.ringkasan)
            appendLine()
        }
        if (meeting.poinUtama.isNotEmpty()) {
            appendLine("POIN UTAMA:")
            meeting.poinUtama.forEach { appendLine("• $it") }
            appendLine()
        }
        if (meeting.keputusan.isNotEmpty()) {
            appendLine("KEPUTUSAN:")
            meeting.keputusan.forEach { appendLine("✓ $it") }
            appendLine()
        }
        if (meeting.actionItems.isNotEmpty()) {
            appendLine("ACTION ITEMS:")
            meeting.actionItems.forEach { item ->
                val status = if (item.selesai) "[Selesai]" else "[Belum]"
                val dl = if (item.deadline.isNotBlank()) " (Deadline: ${item.deadline})" else ""
                appendLine("$status ${item.siapa}: ${item.tugas}$dl")
            }
            appendLine()
        }
        if (meeting.risiko.isNotEmpty()) {
            appendLine("RISIKO:")
            meeting.risiko.forEach { appendLine("! $it") }
            appendLine()
        }
        appendLine("-- ${AppConstants.APP_NAME}")
    }

    fun shareTextFile(context: Context, fileName: String, content: String, mimeType: String = "text/plain") {
        val file = saveToCacheFile(context, fileName, content.toByteArray())
        shareUri(context, file, mimeType, "Bagikan $fileName")
    }

    fun exportAndSharePdf(context: Context, meeting: Meeting) {
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        var page = doc.startPage(pageInfo)
        var canvas = page.canvas
        var y = 48f

        val titlePaint = TextPaint().apply {
            color = Color.parseColor("#0F172A")
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val metaPaint = TextPaint().apply {
            color = Color.parseColor("#64748B")
            textSize = 10f
            isAntiAlias = true
        }
        val headingPaint = TextPaint().apply {
            color = Color.parseColor("#2563EB")
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val bodyPaint = TextPaint().apply {
            color = Color.parseColor("#1E293B")
            textSize = 11f
            isAntiAlias = true
        }

        fun ensureSpace(needed: Float) {
            if (y + needed > 790f) {
                doc.finishPage(page)
                page = doc.startPage(pageInfo)
                canvas = page.canvas
                y = 48f
            }
        }

        fun drawWrapped(text: String, paint: TextPaint, left: Float = 44f, width: Int = 507) {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(4f, 1f)
                .build()
            ensureSpace(layout.height + 10f)
            canvas.save()
            canvas.translate(left, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + 8f
        }

        drawWrapped(meeting.judul, titlePaint)
        drawWrapped("${formatDate(meeting.createdAt?.toDate())}  •  Durasi ${formatDuration(meeting.durasiDetik)}", metaPaint)
        y += 8f

        if (meeting.ringkasan.isNotBlank()) {
            drawWrapped("RINGKASAN EKSEKUTIF", headingPaint)
            drawWrapped(meeting.ringkasan, bodyPaint)
            y += 6f
        }
        if (meeting.keputusan.isNotEmpty()) {
            drawWrapped("KEPUTUSAN", headingPaint)
            meeting.keputusan.forEach { drawWrapped("• $it", bodyPaint) }
            y += 6f
        }
        if (meeting.actionItems.isNotEmpty()) {
            drawWrapped("ACTION ITEMS", headingPaint)
            meeting.actionItems.forEach {
                val mark = if (it.selesai) "[✓]" else "[ ]"
                val dl = if (it.deadline.isNotBlank()) " — Tenggat: ${it.deadline}" else ""
                drawWrapped("$mark ${it.siapa}: ${it.tugas}$dl", bodyPaint)
            }
            y += 6f
        }
        if (meeting.risiko.isNotEmpty()) {
            drawWrapped("RISIKO & HAMBATAN", headingPaint)
            meeting.risiko.forEach { drawWrapped("• $it", bodyPaint) }
        }

        doc.finishPage(page)
        val safeTitle = meeting.judul.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "notulen" }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val pdfFile = File(dir, "$safeTitle-notulen.pdf")
        FileOutputStream(pdfFile).use { doc.writeTo(it) }
        doc.close()

        shareUri(context, pdfFile, "application/pdf", "Bagikan Notulen PDF")
    }

    fun renderAndShareVisualImage(
        context: Context,
        meeting: Meeting,
        visual: VisualData,
        style: VisualStyle,
        theme: VisualTheme,
        watermarkEnabled: Boolean,
        asJpg: Boolean
    ): Result<File> {
        return try {
            val width = 1080
            val height = 1350
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val bgPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    theme.bgTop, theme.bgBottom,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.accent }
            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.cardBg }
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.textPrimary
                textSize = 44f
                typeface = Typeface.DEFAULT_BOLD
            }
            val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.textSecondary
                textSize = 24f
            }
            val headPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.accent
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
            }
            val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.textPrimary
                textSize = 25f
            }

            // Top badge
            canvas.drawRoundRect(RectF(64f, 60f, 340f, 108f), 24f, 24f, accentPaint)
            val badgeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(style.label.uppercase(), 92f, 92f, badgeText)

            // Title
            val titleLayout = StaticLayout.Builder.obtain(visual.judul, 0, visual.judul.length, titlePaint, width - 128)
                .build()
            canvas.save()
            canvas.translate(64f, 132f)
            titleLayout.draw(canvas)
            canvas.restore()

            val dateStr = formatDate(meeting.createdAt?.toDate())
            canvas.drawText(dateStr, 64f, 148f + titleLayout.height + 24f, subPaint)

            var curY = 148f + titleLayout.height + 64f

            fun drawSectionCard(title: String, items: List<String>) {
                if (items.isEmpty() || curY > height - 150f) return
                val textContent = items.take(4).joinToString("\n") { "• $it" }
                val layout = StaticLayout.Builder.obtain(textContent, 0, textContent.length, bodyPaint, width - 192)
                    .setLineSpacing(8f, 1f)
                    .build()
                val cardHeight = layout.height + 92f
                canvas.drawRoundRect(
                    RectF(64f, curY, width - 64f, (curY + cardHeight).coerceAtMost(height - 96f)),
                    28f, 28f, cardPaint
                )
                canvas.drawText(title, 96f, curY + 48f, headPaint)
                canvas.save()
                canvas.translate(96f, curY + 68f)
                layout.draw(canvas)
                canvas.restore()
                curY += cardHeight + 24f
            }

            when (style) {
                VisualStyle.CARDS -> {
                    drawSectionCard("POIN UTAMA", visual.poinUtama)
                    drawSectionCard("KEPUTUSAN", visual.keputusan)
                    drawSectionCard("ANGKA KUNCI", visual.angkaKunci)
                    drawSectionCard("TINDAKAN", visual.tindakan)
                    drawSectionCard("RISIKO", visual.risiko)
                }
                VisualStyle.MIND_MAP -> {
                    // Central node + radial branches
                    val cx = width / 2f
                    val cy = (curY + height - 120f) / 2f
                    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = theme.accent
                        strokeWidth = 5f
                    }
                    val branches = listOf(
                        "Poin Utama" to visual.poinUtama.take(2),
                        "Keputusan" to visual.keputusan.take(2),
                        "Angka Kunci" to visual.angkaKunci.take(2),
                        "Tindakan" to visual.tindakan.take(2)
                    )
                    val positions = listOf(
                        280f to curY + 130f,
                        width - 280f to curY + 130f,
                        280f to height - 240f,
                        width - 280f to height - 240f
                    )
                    positions.forEachIndexed { idx, (bx, by) ->
                        canvas.drawLine(cx, cy, bx, by, linePaint)
                        val boxRect = RectF(bx - 210f, by - 110f, bx + 210f, by + 110f)
                        canvas.drawRoundRect(boxRect, 24f, 24f, cardPaint)
                        val (bTitle, bItems) = branches[idx]
                        canvas.drawText(bTitle, bx - 180f, by - 65f, headPaint)
                        val txt = bItems.joinToString("\n") { "• $it" }.ifBlank { "-" }
                        val l = StaticLayout.Builder.obtain(txt, 0, txt.length, bodyPaint, 360).build()
                        canvas.save()
                        canvas.translate(bx - 180f, by - 45f)
                        l.draw(canvas)
                        canvas.restore()
                    }
                    canvas.drawRoundRect(RectF(cx - 170f, cy - 64f, cx + 170f, cy + 64f), 32f, 32f, accentPaint)
                    val centerTxt = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        textSize = 26f
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    val centerL = StaticLayout.Builder.obtain(visual.judul.take(40), 0, visual.judul.take(40).length, centerTxt, 300)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .build()
                    canvas.save()
                    canvas.translate(cx - 150f, cy - centerL.height / 2f)
                    centerL.draw(canvas)
                    canvas.restore()
                }
                VisualStyle.TIMELINE -> {
                    val steps = listOf(
                        "1. POIN PEMBAHASAN" to visual.poinUtama,
                        "2. KEPUTUSAN RAPAT" to visual.keputusan,
                        "3. TINDAKAN LANJUT" to visual.tindakan
                    )
                    steps.forEach { (stepTitle, stepItems) ->
                        drawSectionCard(stepTitle, stepItems.ifEmpty { listOf("Belum ada data") })
                    }
                }
            }

            if (watermarkEnabled) {
                canvas.drawText("Dibuat dengan ${AppConstants.APP_NAME}", 64f, height - 42f, subPaint)
            }

            val ext = if (asJpg) "jpg" else "png"
            val safeTitle = meeting.judul.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "rapat" }
            val dateSlug = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val outFile = File(dir, "$safeTitle-$dateSlug.$ext")
            FileOutputStream(outFile).use { fos ->
                bitmap.compress(
                    if (asJpg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,
                    95,
                    fos
                )
            }
            bitmap.recycle()
            shareUri(context, outFile, if (asJpg) "image/jpeg" else "image/png", "Bagikan Infografis")
            Result.success(outFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun exportUsageCsv(context: Context, logs: List<UsageLog>) {
        val csv = buildString {
            appendLine("Tanggal,Tugas,Jenis,Provider,Model,Status,InputTokens,OutputTokens,AudioDetik,LatensiMs,BiayaUSD,Playground")
            logs.forEach { l ->
                val dt = formatDate(l.createdAt?.toDate()).replace(",", "")
                appendLine("$dt,${l.task},${l.kind},${l.provider},${l.modelId},${l.status},${l.inputTokens},${l.outputTokens},${l.audioSeconds},${l.latencyMs},${l.estimatedCostUsd},${l.playgroundMode}")
            }
        }
        shareTextFile(context, "pemakaian-ai-notulen.csv", csv, "text/csv")
    }

    fun exportAuditCsv(context: Context, logs: List<AuditLog>) {
        val csv = buildString {
            appendLine("Tanggal,Email,Aksi,Target,Detail")
            logs.forEach { l ->
                val dt = formatDate(l.createdAt?.toDate()).replace(",", "")
                val det = l.detail.replace(",", ";")
                appendLine("$dt,${l.actorEmail},${l.aksi},${l.target},$det")
            }
        }
        shareTextFile(context, "audit-log-notulen.csv", csv, "text/csv")
    }

    fun exportBulkMeetingsTxt(context: Context, meetings: List<Meeting>) {
        val text = buildString {
            appendLine("=== EKSPOR GABUNGAN (${meetings.size} RAPAT) ===")
            appendLine()
            meetings.forEach { m ->
                appendLine(buildMinutesText(m))
                appendLine("-".repeat(48))
                appendLine()
            }
        }
        shareTextFile(context, "kumpulan-notulen-rapat.txt", text, "text/plain")
    }

    fun exportAllUserData(context: Context, displayName: String, email: String, tenantName: String, meetings: List<Meeting>) {
        val content = buildString {
            appendLine("EKSPOR DATA PENGGUNA - ${AppConstants.APP_NAME}")
            appendLine("Tanggal Ekspor: ${formatDate(Date())}")
            appendLine("Nama: $displayName | Email: $email | Organisasi: $tenantName")
            appendLine("Total Rapat: ${meetings.size}")
            appendLine("=".repeat(56))
            appendLine()
            meetings.forEach { m ->
                appendLine(buildMinutesText(m))
                if (m.segments.isNotEmpty()) {
                    appendLine("TRANSKRIP:")
                    m.segments.forEach { s -> appendLine("[${formatDuration(s.mulaiDetik)}] ${s.pembicara}: ${s.teks}") }
                }
                appendLine("=".repeat(56))
                appendLine()
            }
        }
        shareTextFile(context, "ekspor-data-saya.txt", content, "text/plain")
    }

    private fun saveToCacheFile(context: Context, fileName: String, bytes: ByteArray): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    private fun shareUri(context: Context, file: File, mimeType: String, chooserTitle: String) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }
}

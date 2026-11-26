package com.example.audio

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordingFileIdentity {
    private val lock = Any()
    private val pattern = Regex("^Rec(\\d+)(\\d{6})-(\\d{2})\\.acc$")

    fun masterDirectory(): File = File(Environment.getExternalStorageDirectory(), "Android/media/NotulenKu/Meet").apply { mkdirs() }

    fun allocate(context: Context, createdAt: Long = System.currentTimeMillis()): File = synchronized(lock) {
        val dir = masterDirectory()
        val date = SimpleDateFormat("ddMMyy", Locale.US).format(Date(createdAt))
        val prefs = context.getSharedPreferences("recording_file_identity", Context.MODE_PRIVATE)
        val files = dir.listFiles().orEmpty()
        val maxGlobalOnDisk = files.mapNotNull { pattern.matchEntire(it.name)?.groupValues?.get(1)?.toLongOrNull() }.maxOrNull() ?: 0L
        val global = maxOf(prefs.getLong("global_counter", 0L), maxGlobalOnDisk) + 1L
        val daily = files.mapNotNull { f -> pattern.matchEntire(f.name)?.takeIf { it.groupValues[2] == date }?.groupValues?.get(3)?.toIntOrNull() }.maxOrNull() ?: 0
        val nextDaily = daily + 1
        var file = File(dir, "RecGLOBALDATE-DAILY.acc")
        file = File(dir, "Rec" + global.toString().padStart(4, '0') + date + "-" + nextDaily.toString().padStart(2, '0') + ".acc")
        var candidateGlobal = global
        var candidateDaily = nextDaily
        while (file.exists()) {
            candidateGlobal++
            candidateDaily++
            file = File(dir, "Rec" + candidateGlobal.toString().padStart(4, '0') + date + "-" + candidateDaily.toString().padStart(2, '0') + ".acc")
        }
        prefs.edit().putLong("global_counter", candidateGlobal).apply()
        file
    }
}

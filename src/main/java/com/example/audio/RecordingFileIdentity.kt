package com.example.audio

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Persistent filename identity and permanent master-audio location. */
object RecordingFileIdentity {
    private const val PREFS = "recording_file_identity"
    private const val GLOBAL_KEY = "global_counter"
    private const val DAILY_KEY = "daily_counter"
    private const val DATE_KEY = "daily_date"
    data class Identity(val global: Int, val date: String, val daily: Int, val fileName: String)
    @Synchronized fun next(context: Context, now: Date = Date()): Identity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val date = SimpleDateFormat("ddMMyy", Locale.US).format(now)
        val global = prefs.getInt(GLOBAL_KEY, 0) + 1
        val daily = if (prefs.getString(DATE_KEY, "") == date) prefs.getInt(DAILY_KEY, 0) + 1 else 1
        val fileName = "Rec%04d%s-%02d.m4a".format(Locale.US, global, date, daily)
        check(prefs.edit().putInt(GLOBAL_KEY, global).putInt(DAILY_KEY, daily).putString(DATE_KEY, date).commit()) { "Gagal menyimpan counter rekaman" }
        return Identity(global, date, daily, fileName)
    }
    @Suppress("DEPRECATION") // Use Android's app-owned, user-visible media directory.
    internal fun mediaFolder(context: Context, name: String): File? =
        runCatching { context.externalMediaDirs.firstOrNull() }.getOrNull()
            ?.let { File(it, "NotulenKu/$name") }

    internal fun saveMaster(context: Context, segments: List<File>, fileName: String): Result<File> {
        val media = mediaFolder(context, "Meet")
        val privateDir = File(context.filesDir, "recordings/masters")
        var lastError: Throwable? = null
        for (dir in listOfNotNull(media, privateDir)) {
            val requested = File(dir, fileName)
            val destination = if (requested.exists()) {
                File(dir, "${requested.nameWithoutExtension}-${UUID.randomUUID()}.m4a")
            } else requested
            val result = runCatching {
                check(dir.isDirectory || dir.mkdirs()) { "Tidak dapat membuat folder rekaman utama" }
                AudioSegmenter.mergeM4aSegments(segments, destination).getOrThrow()
            }
            if (result.isSuccess) return result
            lastError = result.exceptionOrNull()
            destination.delete()
        }
        return Result.failure(lastError ?: IllegalStateException("Tidak dapat menyimpan rekaman utama"))
    }
}

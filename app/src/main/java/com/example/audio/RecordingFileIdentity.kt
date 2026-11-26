package com.example.audio

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val fileName = "Rec%04d%s-%02d.acc".format(Locale.US, global, date, daily)
        check(prefs.edit().putInt(GLOBAL_KEY, global).putInt(DAILY_KEY, daily).putString(DATE_KEY, date).commit()) { "Gagal menyimpan counter rekaman" }
        return Identity(global, date, daily, fileName)
    }
    fun masterDirectory(context: Context): File = File(Environment.getExternalStorageDirectory(), "Android/media/NotulenKu/Meet").apply { check(exists() || mkdirs()) { "Tidak dapat membuat folder backup rekaman" } }
}

package com.example.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

data class PcmWavConversionResult(
    val wavFile: File,
    val sourceBytes: Long,
    val durationMs: Long,
    val sha256: String
)

/** Converts validated PCM16 mono raw audio to a standard 44-byte-header PCM WAV.
 * Experimental recovery utility only; it does not modify the source.
 */
class ExperimentalPcmWavConverter(private val recoveryDirectory: File) {
    fun convert(source: File, sampleRateHz: Int, confirmed: Boolean): PcmWavConversionResult {
        if (!confirmed) throw IOException("Konversi membutuhkan konfirmasi pengguna.")
        val root = recoveryDirectory.canonicalFile
        if (!source.isFile || source.canonicalFile.parentFile != root) throw IOException("Sumber PCM tidak aman atau tidak ditemukan.")
        if (sampleRateHz !in 8_000..192_000) throw IOException("Sample rate di luar rentang yang didukung.")
        val bytes = source.length()
        if (bytes <= 0 || bytes % 2L != 0L) throw IOException("Sumber PCM kosong atau tidak sejajar PCM16.")
        if (bytes > 0xFFFF_FFFFL - 36L) throw IOException("PCM terlalu besar untuk WAV RIFF klasik.")
        val output = File(root, source.nameWithoutExtension + ".wav")
        val temp = File(root, output.name + ".tmp")
        if (output.exists() || temp.exists()) throw IOException("File WAV atau temporary sudah ada; tidak akan ditimpa.")
        val byteRate = sampleRateHz.toLong() * 2L
        if (byteRate > 0xFFFF_FFFFL) throw IOException("Byte rate tidak valid.")
        try {
            RandomAccessFile(temp, "rw").use { wav ->
                wav.writeBytes("RIFF"); writeLE32(wav, bytes + 36L); wav.writeBytes("WAVE")
                wav.writeBytes("fmt "); writeLE32(wav, 16L); writeLE16(wav, 1)
                writeLE16(wav, 1); writeLE32(wav, sampleRateHz.toLong()); writeLE32(wav, byteRate)
                writeLE16(wav, 2); writeLE16(wav, 16); wav.writeBytes("data"); writeLE32(wav, bytes)
                source.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; wav.write(buffer, 0, n) }
                }
                wav.fd.sync()
            }
            validate(temp, bytes, sampleRateHz)
            val hash = sha256(temp)
            if (!temp.renameTo(output)) throw IOException("WAV tidak dapat dipindahkan ke nama final.")
            return PcmWavConversionResult(output, bytes, bytes / 2L * 1000L / sampleRateHz, hash)
        } catch (e: Exception) { temp.delete(); throw e }
    }

    private fun validate(file: File, expectedDataBytes: Long, sampleRate: Int) {
        RandomAccessFile(file, "r").use { f ->
            if (f.length() != expectedDataBytes + 44L) throw IOException("Ukuran WAV tidak valid.")
            val riff = ByteArray(4); f.readFully(riff); if (String(riff, Charsets.US_ASCII) != "RIFF") throw IOException("Header RIFF invalid.")
            if (readLE32(f) != expectedDataBytes + 36L) throw IOException("Ukuran RIFF tidak cocok.")
            val wave = ByteArray(4); f.readFully(wave); if (String(wave, Charsets.US_ASCII) != "WAVE") throw IOException("Header WAVE invalid.")
            val fmt = ByteArray(4); f.readFully(fmt); if (String(fmt, Charsets.US_ASCII) != "fmt ") throw IOException("Chunk format invalid.")
            if (readLE32(f) != 16L || readLE16(f) != 1 || readLE16(f) != 1 ||
                readLE32(f) != sampleRate.toLong() || readLE32(f) != sampleRate.toLong() * 2L ||
                readLE16(f) != 2 || readLE16(f) != 16) throw IOException("Format WAV tidak cocok dengan PCM sumber.")
            val data = ByteArray(4); f.readFully(data); if (String(data, Charsets.US_ASCII) != "data") throw IOException("Chunk data invalid.")
            if (readLE32(f) != expectedDataBytes) throw IOException("Ukuran data WAV tidak cocok.")
        }
    }
    private fun writeLE16(f: RandomAccessFile, v: Int) { f.write(v and 255); f.write((v ushr 8) and 255) }
    private fun writeLE32(f: RandomAccessFile, v: Long) { for (i in 0..3) f.write((v ushr (8*i)).toInt() and 255) }
    private fun readLE16(f: RandomAccessFile): Int = f.read() or (f.read() shl 8)
    private fun readLE32(f: RandomAccessFile): Long { var v=0L; for(i in 0..3) v=v or (f.read().toLong() shl (8*i)); return v }
    private fun sha256(file: File): String { val d=MessageDigest.getInstance("SHA-256"); file.inputStream().use { i -> val b=ByteArray(8192); while(true){val n=i.read(b);if(n<0)break;d.update(b,0,n)} }; return d.digest().joinToString("") { "%02x".format(it) } }
}

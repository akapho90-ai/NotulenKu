package com.example.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ExperimentalPcmWavConverterTest {
    @Test fun requiresConfirmation() {
        val root=createTempDir()
        try { val src=File(root,"audio.recovered.pcm");src.writeBytes(byteArrayOf(1,2));try{ExperimentalPcmWavConverter(root).convert(src,16000,false);fail()}catch(_:Exception){} }
        finally { root.deleteRecursively() }
    }
    @Test fun convertsAndValidatesWavWithoutChangingSource() {
        val root=createTempDir()
        try {
            val src=File(root,"audio.recovered.pcm");val bytes=byteArrayOf(1,2,3,4,5,6,7,8);src.writeBytes(bytes)
            val result=ExperimentalPcmWavConverter(root).convert(src,16000,true)
            assertEquals(52L,result.wavFile.length());assertEquals(0L,result.durationMs)
            val header=result.wavFile.readBytes().take(44).toByteArray()
            assertEquals("RIFF",String(header.sliceArray(0..3)));assertEquals("WAVE",String(header.sliceArray(8..11)))
            assertEquals("data",String(header.sliceArray(36..39)));assertEquals(8,header[40].toInt())
            assertArrayEquals(bytes,src.readBytes());assertTrue(result.sha256.length==64)
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectsOddOrEmptyPcm() {
        val root=createTempDir()
        try { val src=File(root,"bad.pcm");src.writeBytes(byteArrayOf(1));try{ExperimentalPcmWavConverter(root).convert(src,16000,true);fail()}catch(_:Exception){} }
        finally { root.deleteRecursively() }
    }
    @Test fun refusesOutputCollision() {
        val root=createTempDir()
        try { val src=File(root,"audio.recovered.pcm");src.writeBytes(byteArrayOf(1,2));val out=File(root,"audio.recovered.wav");out.writeBytes(byteArrayOf(9));try{ExperimentalPcmWavConverter(root).convert(src,16000,true);fail()}catch(_:Exception){};assertArrayEquals(byteArrayOf(9),out.readBytes()) }
        finally { root.deleteRecursively() }
    }
}

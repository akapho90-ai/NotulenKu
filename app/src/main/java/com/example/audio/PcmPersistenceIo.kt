package com.example.audio

import java.io.File
import java.io.FileDescriptor
import java.io.OutputStream

/** Operations shared by the PCM writer and its metadata files. */
internal interface PcmPersistenceIo {
    fun write(file: File, output: OutputStream, bytes: ByteArray) = output.write(bytes)
    fun flush(file: File, output: OutputStream) = output.flush()
    fun sync(file: File, descriptor: FileDescriptor) = descriptor.sync()
    fun rename(source: File, target: File): Boolean = source.renameTo(target)
}

internal object DefaultPcmPersistenceIo : PcmPersistenceIo

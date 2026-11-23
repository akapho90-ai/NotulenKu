package com.example.audio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class M4aPromotionTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun failedRetryPreservesTheOnlyValidBackupAndCanBeRetried() {
        val staged = audio("final.m4a.staged", "new")
        val backup = audio("final.m4a.backup", "old")
        val destination = File(folder.root, "final.m4a")

        assertThrows(IllegalStateException::class.java) {
            AudioSegmenter.promoteValidatedAudio(staged, destination, ::readable) { _, _ -> false }
        }

        assertEquals("old", backup.readText())
        assertEquals("new", staged.readText())
        assertFalse(destination.exists())

        AudioSegmenter.promoteValidatedAudio(staged, destination, ::readable)
        assertEquals("new", destination.readText())
        assertFalse(backup.exists())
    }

    @Test
    fun retryKeepsBackupIfThePromotedFileFailsValidation() {
        val staged = audio("final.m4a.merging", "new")
        val backup = audio("final.m4a.backup", "old")
        val destination = audio("final.m4a", "invalid")

        assertThrows(IllegalStateException::class.java) {
            AudioSegmenter.promoteValidatedAudio(staged, destination, ::readable) { from, to ->
                val moved = from.renameTo(to)
                if (moved && to == destination) to.writeText("invalid")
                moved
            }
        }

        assertEquals("old", backup.readText())
        assertFalse(destination.exists())
    }

    @Test
    fun failedReplacementRestoresThePreviouslyValidDestination() {
        val staged = audio("final.m4a.staged", "new")
        val destination = audio("final.m4a", "old")
        val backup = File(folder.root, "final.m4a.backup")

        assertThrows(IllegalStateException::class.java) {
            AudioSegmenter.promoteValidatedAudio(staged, destination, ::readable) { from, to ->
                from != staged && from.renameTo(to)
            }
        }

        assertEquals("old", destination.readText())
        assertEquals("new", staged.readText())
        assertFalse(backup.exists())
    }

    private fun audio(name: String, contents: String) = File(folder.root, name).apply { writeText(contents) }

    private fun readable(file: File): Boolean = file.isFile && file.readText() in setOf("old", "new")
}

package com.androiduse.log

import com.androiduse.agent.Observation
import com.androiduse.agent.Step
import com.androiduse.agent.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/** Transcript 落盘：每个任务一个目录，transcript.jsonl 每步一行，截图单独成文件。 */
class TranscriptStoreTest {

    @Test
    fun writesHeaderThenOneLinePerStepAndScreenshotFiles() {
        val root = Files.createTempDirectory("aud").toFile()
        val store = TranscriptStore(root)
        val t = Transcript("task-1", "看型号", "glm", 0L, 1080, 2376)
        store.start(t)

        val jpg = Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3))
        val path = store.saveScreenshot(t, 1, jpg)
        val step = Step(1, Observation(jpg, path, emptyList(), "", null))
        store.step(t, step)
        store.step(t, Step(2, Observation(null, null, emptyList(), "", "daemon unreachable")))

        val dir = File(root, "task-1")
        val lines = File(dir, "transcript.jsonl").readLines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("\"type\":\"task\""))
        assertTrue(lines[1].contains("\"index\":1"))
        assertTrue(lines[2].contains("daemon unreachable"))

        val shot = File(dir, "step-1.jpg")
        assertTrue(shot.exists())
        assertEquals(5, shot.length())
        assertEquals(shot.absolutePath, path)
        assertTrue(lines[1].contains("step-1.jpg"))
    }
}

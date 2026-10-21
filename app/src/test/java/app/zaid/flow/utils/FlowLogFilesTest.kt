package app.zaid.flow.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FlowLogFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun logs(maxBytes: Long = 10_000L) = FlowLogFiles(folder.newFolder(), maxBytes).also { it.open() }

    @Test
    fun `every level is kept in order`() {
        val log = logs()
        val lines = listOf("V a: verbose", "D a: debug", "I a: info", "W a: warn", "E a: error", "F a: fatal")
        lines.forEach(log::append)
        assertEquals(lines.joinToString("\n") + "\n", log.readTail(10_000))
    }

    @Test
    fun `a tail read starts on a whole line`() {
        val log = logs()
        (1..50).forEach { log.append("line %03d".format(it)) }
        val tail = log.readTail(45)
        assertTrue(tail.lines().filter { it.isNotEmpty() }.all { it.matches(Regex("line \\d{3}")) })
        assertTrue(tail.trimEnd().endsWith("line 050"))
        assertFalse(tail.contains("line 001"))
    }

    @Test
    fun `passing the size limit rolls the file and keeps both segments`() {
        val log = logs(maxBytes = 100)
        (1..40).forEach { log.append("line %03d".format(it)) }
        val all = log.readTail(10_000)
        assertTrue(all.contains("line 040"))
        assertEquals(all.lines().filter { it.isNotEmpty() }.sorted(), all.lines().filter { it.isNotEmpty() })
    }

    @Test
    fun `a new session keeps the last run as previous`() {
        val dir = folder.newFolder()
        FlowLogFiles(dir).also {
            it.open()
            it.append("first run")
            it.flush()
        }
        val second = FlowLogFiles(dir).also { it.open() }
        second.append("second run")
        assertEquals("second run\n", second.readTail(10_000))
        assertEquals("first run\n", File(dir, FlowLogFiles.PREVIOUS).readText())
    }

    @Test
    fun `clearing empties this session and keeps the previous run`() {
        val dir = folder.newFolder()
        FlowLogFiles(dir).also {
            it.open()
            it.append("old")
            it.flush()
        }
        val log = FlowLogFiles(dir).also { it.open() }
        log.append("now")
        log.clear()
        assertEquals("", log.readTail(10_000))
        assertTrue(File(dir, FlowLogFiles.PREVIOUS).exists())
        log.append("after")
        assertEquals("after\n", log.readTail(10_000))
    }

    @Test
    fun `export puts the previous run before this one`() {
        val dir = folder.newFolder()
        FlowLogFiles(dir).also {
            it.open()
            it.append("last run")
            it.flush()
        }
        val log = FlowLogFiles(dir).also { it.open() }
        log.append("this run")
        val target = File(folder.newFolder(), "out.txt")
        log.exportTo(target)
        val text = target.readText()
        assertTrue(text.indexOf("last run") < text.indexOf("this run"))
    }
}

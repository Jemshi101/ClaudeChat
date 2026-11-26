package `in`.bleuphoenix.claudechat.cli

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeCliTest {

    @Test
    fun `configured executable is used`() {
        assertEquals("/bin/sh", ClaudeCli.findExecutable("/bin/sh"))
    }

    @Test
    fun `the PATH is searched folder by folder, skipping ones without it`() {
        val path = listOf("/no/such/dir", "", "/bin", "/usr/bin").joinToString(File.pathSeparator)
        assertEquals("/bin/sh", ClaudeCli.findInPath("sh", path))
        assertNull(ClaudeCli.findInPath("no-such-program", path))
        assertNull(ClaudeCli.findInPath("sh", null))
    }

    @Test
    fun `invalid configured path is reported, not replaced by another install`() {
        assertNull(ClaudeCli.findExecutable("/no/such/claude"))
    }

    @Test
    fun `on Windows the installers' locations are looked up, not the macOS ones`() {
        assertEquals(
            listOf("C:\\Users\\dev\\.local\\bin\\claude.exe", "C:\\Users\\dev\\AppData\\Roaming\\npm\\claude.cmd"),
            ClaudeCli.defaultLocations(windows = true, home = "C:\\Users\\dev", appData = "C:\\Users\\dev\\AppData\\Roaming"),
        )
        assertEquals(
            listOf("/Users/dev/.local/bin/claude", "/opt/homebrew/bin/claude", "/usr/local/bin/claude"),
            ClaudeCli.defaultLocations(windows = false, home = "/Users/dev", appData = null),
        )
    }

    @Test
    fun `the desktop app's own Claude Code is found at its newest version`() {
        val local = java.nio.file.Files.createTempDirectory("localappdata").toFile()
        try {
            assertNull(ClaudeCli.desktopAppCli(local.path))
            fun install(version: String, build: String) =
                File(local, "Claude-Data/claude_pzs8sxrjxfjjc/claude-code/$version/$build/claude.exe").apply { parentFile.mkdirs(); writeText("") }
            install("2.1.99", "aaa")
            val newest = install("2.1.308", "c17a819afb40")
            install("2.1.31", "bbb")
            File(local, "Claude-Data/claude_pzs8sxrjxfjjc/claude-code/2.1.400/ccc").mkdirs() // an update still unpacking
            assertEquals(newest.path, ClaudeCli.desktopAppCli(local.path))
        } finally {
            local.deleteRecursively()
        }
    }

    @Test
    fun `command line carries resume, mode and model`() {
        val args = ClaudeCli.commandLine("/bin/claude", "/tmp", "abc", "plan", "sonnet", ClaudeCli.PLAN_DIAGRAM_PROMPT).parametersList.list
        assertTrue(args.containsAll(listOf("--permission-prompt-tool", "stdio")))
        assertEquals("abc", args[args.indexOf("--resume") + 1])
        assertEquals("plan", args[args.indexOf("--permission-mode") + 1])
        assertEquals("sonnet", args[args.indexOf("--model") + 1])
        assertTrue(args[args.indexOf("--append-system-prompt") + 1].contains("mermaid"))
    }

    @Test
    fun `effort is passed only when chosen`() {
        assertEquals("xhigh", ClaudeCli.commandLine("/bin/claude", "/tmp", null, null, null, effort = "xhigh").parametersList.list.let { it[it.indexOf("--effort") + 1] })
        assertFalse("--effort" in ClaudeCli.commandLine("/bin/claude", "/tmp", null, null, null).parametersList.list)
    }

    @Test
    fun `a turn limit is passed only when set`() {
        val args = ClaudeCli.commandLine("/bin/claude", "/tmp", null, null, null, maxTurns = 25).parametersList.list
        assertEquals("25", args[args.indexOf("--max-turns") + 1])
        assertFalse("--max-turns" in ClaudeCli.commandLine("/bin/claude", "/tmp", null, null, null, maxTurns = 0).parametersList.list)
    }

    @Test
    fun `an auto-compact window is passed only when set`() {
        val args = ClaudeCli.commandLine("/bin/claude", "/tmp", null, null, null, autoCompactWindow = 300_000).parametersList.list
        assertEquals("300000", args[args.indexOf("--autocompact") + 1])
        assertFalse("--autocompact" in ClaudeCli.commandLine("/bin/claude", "/tmp", null, null, null, autoCompactWindow = 0).parametersList.list)
    }

    @Test
    fun `forking resumes a copy that ends at a message`() {
        val args = ClaudeCli.commandLine("/bin/claude", "/work", "session-1", null, null, forkAtMessage = "uuid-7").parametersList.list
        val at = args.indexOf("--resume-session-at")
        assertEquals("uuid-7", args[at + 1])
        assertTrue("--fork-session" in args)
        assertEquals("session-1", args[args.indexOf("--resume") + 1])

        val fresh = ClaudeCli.commandLine("/bin/claude", "/work", null, null, null, forkAtMessage = "uuid-7").parametersList.list
        assertTrue("no session to fork", "--fork-session" !in fresh)
    }
}

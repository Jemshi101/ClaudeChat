package `in`.bleuphoenix.claudechat.cli

import com.intellij.execution.configurations.GeneralCommandLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstructionFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** outside/agent.md, repo/.git, repo/agent.md, repo/spec.md, repo/app/ (the project), repo/app/notes.md */
    private fun layout(): File {
        val outside = temp.newFolder("outside")
        File(outside, "agent.md").writeText("outside the repo")
        val repo = File(outside, "repo").apply { mkdirs() }
        File(repo, ".git").mkdirs()
        File(repo, "agent.md").writeText("Agent guide: AGENT-MANGO")
        File(repo, "spec.md").writeText("Spec: SPEC-KIWI")
        val app = File(repo, "app").apply { mkdirs() }
        File(app, "notes.md").writeText("project notes")
        return app
    }

    @Test
    fun `names come from a comma or line separated setting`() {
        assertEquals(listOf("agent.md", "spec.md", "docs/x.md"), InstructionFiles.names(" agent.md, spec.md\ndocs/x.md,agent.md ,"))
        assertEquals(emptyList<String>(), InstructionFiles.names(null))
    }

    @Test
    fun `files are found in the project or its parents, but not above the repository root`() {
        val app = layout()
        val found = InstructionFiles.resolve(app.path, listOf("notes.md", "agent.md", "spec.md", "missing.md"))
        assertEquals(listOf("app/notes.md", "repo/agent.md", "repo/spec.md"), found.map { "${it.parentFile.name}/${it.name}" })
        assertTrue("outside/agent.md is above the repo", found.none { it.readText() == "outside the repo" })
    }

    @Test
    fun `found files turn off CLAUDE md and go into the system prompt after the plugin's own prompt`() {
        val app = layout()
        val commandLine = GeneralCommandLine("claude", "-p")
        val prompts = temp.newFolder("prompts")
        val files = InstructionFiles.apply(commandLine, app.path, "agent.md, spec.md", "Draw a diagram.", prompts)
        assertEquals(2, files.size)
        assertEquals("1", commandLine.environment[InstructionFiles.DISABLE_CLAUDE_MDS])
        val args = commandLine.parametersList.list
        assertFalse("--append-system-prompt" in args)
        val prompt = File(args[args.indexOf("--append-system-prompt-file") + 1]).readText()
        assertTrue(prompt.startsWith("Draw a diagram.\n\n# Project instructions"))
        assertTrue(prompt.contains("## agent.md (") && prompt.contains("AGENT-MANGO"))
        assertTrue(prompt.indexOf("AGENT-MANGO") < prompt.indexOf("SPEC-KIWI"))
    }

    @Test
    fun `with nothing found CLAUDE md stays on`() {
        val app = layout()
        val commandLine = GeneralCommandLine("claude", "-p")
        assertTrue(InstructionFiles.apply(commandLine, app.path, "missing.md", null, temp.newFolder("prompts")).isEmpty())
        assertNull(commandLine.environment[InstructionFiles.DISABLE_CLAUDE_MDS])
        assertFalse("--append-system-prompt-file" in commandLine.parametersList.list)
    }

    @Test
    fun `long guides stay off the command line, one file per text`() {
        val dir = temp.newFolder("prompts")
        val long = "x".repeat(100_000)
        val file = InstructionFiles.promptFile(long, dir)
        assertEquals(long, file.readText())
        assertEquals(file, InstructionFiles.promptFile(long, dir))
        assertEquals(1, dir.listFiles()!!.size)
    }
}

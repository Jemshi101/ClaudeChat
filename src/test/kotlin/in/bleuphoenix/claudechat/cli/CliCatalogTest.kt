package `in`.bleuphoenix.claudechat.cli

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CliCatalogTest {

    private val lines = javaClass.getResource("/protocol/initialize.ndjson")!!.readText().lines().filter { it.isNotBlank() }
    private val response = CliCatalog.parseInitialize(lines[0])!!
    private val models = CliCatalog.models(response)
    private val commands = CliCatalog.commands(response.getAsJsonArray("commands"))

    @Test
    fun `the initialize answer lists models and commands, without internal commands`() {
        assertNull(CliCatalog.parseInitialize(lines[1]))
        assertEquals(listOf("default", "opus", "sonnet", "fable", "haiku"), models.take(5).map { it.value })
        assertEquals("Sonnet 5.5", models.first { it.value == "sonnet" }.displayName)
        assertEquals(setOf("clear", "compact", "context", "model", "code-review", "simplify"), commands.map { it.name }.toSet())
        assertEquals(listOf("reset", "new"), commands.first { it.name == "clear" }.aliases)
        // commands_changed carries the same shape.
        val changed = JsonParser.parseString(lines[1]).asJsonObject.getAsJsonArray("commands")
        assertEquals(commands.map { it.name }.toSet(), CliCatalog.commands(changed).map { it.name }.toSet())
    }

    @Test
    fun `models carry the effort levels they support`() {
        assertEquals(Effort.LEVELS, models.first { it.value == "opus" }.effortLevels)
        assertEquals(listOf("low", "medium", "high", "max"), models.first { it.value == "claude-opus-4-6" }.effortLevels)
        assertEquals(emptyList<String>(), models.first { it.value == "claude-haiku-4-5-20251001" }.effortLevels)

        assertEquals(Effort.LEVELS, Effort.levelsFor(models, null))
        assertEquals(emptyList<String>(), Effort.levelsFor(models, "claude-haiku-4-5-20251001"))
        assertEquals("a model the CLI didn't list gets every level", Effort.LEVELS, Effort.levelsFor(models, "claude-custom-1"))
        assertEquals("nothing before the models are known", emptyList<String>(), Effort.levelsFor(emptyList(), null))
        assertEquals("Extra high", Effort.label("xhigh"))
        assertEquals("Default", Effort.label(null))
    }

    @Test
    fun `typed names match by prefix first, then anywhere`() {
        assertEquals(listOf("code-review", "compact", "context"), CliCatalog.match(commands, "co").map { it.name }.sorted())
        assertEquals("context", CliCatalog.match(commands, "cont").single().name)
        assertEquals("clear", CliCatalog.match(commands, "new").first().name)
        assertEquals("code-review", CliCatalog.match(commands, "review").single().name)
        assertEquals(commands, CliCatalog.match(commands, ""))
        val skill = SlashCommand("anthropic-skills:pdf", "", "")
        assertEquals(listOf(skill), CliCatalog.match(listOf(skill), "pd"))
    }

    @Test
    fun `the picker names Default after the model it stands for`() {
        assertEquals("Opus 5.5", CliCatalog.label(models, null))
        assertEquals("Opus 5.5", CliCatalog.label(models, "default"))
        assertEquals("Haiku 5.5", CliCatalog.label(models, "haiku"))
        assertEquals("my-custom-model", CliCatalog.label(models, "my-custom-model"))
        assertEquals("Default", CliCatalog.label(emptyList(), null))
    }

    @Test
    fun `a model id reported by the CLI maps back to its alias`() {
        assertEquals("sonnet", CliCatalog.optionFor(models, "claude-sonnet-5-5")?.value)
        assertEquals("opus", CliCatalog.optionFor(models, "claude-opus-5-5")?.value)
        assertNotNull(CliCatalog.optionFor(models, "claude-opus-4-8"))
        assertNull(CliCatalog.optionFor(models, "something-else"))
    }
}

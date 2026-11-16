package `in`.bleuphoenix.claudechat.plan

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlanFilesTest {

    @get:Rule
    val dir = TemporaryFolder()

    @Test
    fun `plan titles become readable file names`() {
        assertEquals("add-name-command-line-option-to-main-kt", PlanFiles.slug("Plan: Add `--name` command-line option to Main.kt"))
        assertEquals("set-clock-navigation-to-flutter", PlanFiles.slug("Implementation Plan — Set Clock navigation to Flutter"))
        assertEquals("splash-screen", PlanFiles.slug("Splash screen"))
        assertEquals("plan", PlanFiles.slug("Plan:"))
        assertEquals("plan", PlanFiles.slug("✨"))
    }

    @Test
    fun `long titles stop at a word within the limit`() {
        val slug = PlanFiles.slug("Migrate the legacy service request screens from Java activities to Kotlin fragments with view binding")
        assertEquals("migrate-the-legacy-service-request-screens-from-java", slug)
    }

    @Test
    fun `a plan's file is reused, not numbered`() {
        val folder = dir.root
        assertEquals("splash-screen.md", PlanFiles.fileFor("Splash screen", folder).name)
        folder.resolve("splash-screen.md").writeText("x")
        assertEquals("splash-screen.md", PlanFiles.fileFor("Plan: Splash screen", folder).name)
    }
}

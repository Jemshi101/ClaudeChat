package `in`.bleuphoenix.claudechat.ui

import `in`.bleuphoenix.claudechat.ui.compose.PlanOutline
import org.junit.Assert.assertEquals
import org.junit.Test

class PlanOutlineTest {

    @Test
    fun `summary is the first paragraph and sections are the second-level headings`() {
        val outline = PlanOutline.of(
            """
            # Add `--name` option

            ## Context
            `Main.kt` takes no **arguments**.
            Goal: accept a [name](https://x.dev).

            ## Changes (Main.kt only)
            1. Parse args

            ## Verification
            """.trimIndent(),
        )
        assertEquals("Main.kt takes no arguments. Goal: accept a name.", outline.summary)
        assertEquals(listOf("Context", "Changes (Main.kt only)", "Verification"), outline.sections)
    }
}

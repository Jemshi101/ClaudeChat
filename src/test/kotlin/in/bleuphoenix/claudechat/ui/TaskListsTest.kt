package `in`.bleuphoenix.claudechat.ui

import `in`.bleuphoenix.claudechat.ui.compose.MarkdownSegment
import `in`.bleuphoenix.claudechat.ui.compose.TaskLists
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskListsTest {

    private val plan = """
        # Plan

        ```md
        - [ ] not a task, it's in a code block
        ```

        ## To-dos

        - [ ] Add tools:node=remove to the manifest
        - [x] Rebuild demoDebug
        - [ ] Run QA on build A and B

        Done.
    """.trimIndent()

    @Test
    fun `a task list becomes a to-do card, taking the To-dos heading with it`() {
        val segments = MarkdownSegment.split(plan)
        val todos = segments.filterIsInstance<MarkdownSegment.Todos>().single()
        assertEquals(listOf("Add tools:node=remove to the manifest", "Rebuild demoDebug", "Run QA on build A and B"), todos.items.map { it.text })
        assertEquals(listOf(false, true, false), todos.items.map { it.done })
        assertEquals(0, todos.first)
        val text = segments.filterIsInstance<MarkdownSegment.Text>().joinToString("\n") { it.markdown }
        assertEquals("the heading is drawn by the card", false, "## To-dos" in text)
    }

    @Test
    fun `ticking a task changes only its line, skipping code blocks`() {
        val ticked = TaskLists.toggle(plan, 0)
        assertEquals(plan.replace("- [ ] Add tools", "- [x] Add tools"), ticked)
        assertEquals(plan.replace("- [x] Rebuild", "- [ ] Rebuild"), TaskLists.toggle(plan, 1))
        assertEquals("an unknown task changes nothing", plan, TaskLists.toggle(plan, 9))
    }

    @Test
    fun `a task Claude completed ticks the to-do it stands for`() {
        val tasks = TaskLists.tasks(plan)
        assertEquals(listOf("Add tools:node=remove to the manifest", "Rebuild demoDebug", "Run QA on build A and B"), tasks.map { it.text })
        assertEquals(0, TaskLists.match(tasks, "Add tools:node=remove to the manifest"))
        assertEquals("close wording", 2, TaskLists.match(tasks, "Run QA on builds A and B"))
        assertEquals("already done", null, TaskLists.match(tasks, "Rebuild demoDebug"))
        assertEquals("unrelated", null, TaskLists.match(tasks, "Update the README"))
        assertEquals(plan.replace("- [ ] Run QA", "- [x] Run QA"), TaskLists.complete(plan, 2))
        assertEquals("completing a done task leaves it done", plan, TaskLists.complete(plan, 1))
    }

    @Test
    fun `a new task goes after the last one, or under a new To-dos heading`() {
        assertEquals(plan.replace("- [ ] Run QA on build A and B", "- [ ] Run QA on build A and B\n- [ ] Raise the MR"), TaskLists.add(plan, "Raise the MR"))
        assertEquals("# Plan\n\nText.\n\n## To-dos\n\n- [ ] First step\n", TaskLists.add("# Plan\n\nText.\n", "First step"))
    }
}

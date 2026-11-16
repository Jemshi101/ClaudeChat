package `in`.bleuphoenix.claudechat.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import `in`.bleuphoenix.claudechat.review.FileReview
import `in`.bleuphoenix.claudechat.review.ReviewState
import `in`.bleuphoenix.claudechat.ui.compose.ActivityStrip
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** Clicks on the changes strip: opening it, then a file's Reject / Accept, Accept all and opening a file. */
class ActivityStripClickTest {

    private val calls = mutableListOf<String>()
    private val actions = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(`in`.bleuphoenix.claudechat.ui.compose.ChatActions::class.java)) { _, method, args ->
        val files = (args?.firstOrNull() as? List<*>)?.size
        calls += if (files != null) "${method.name}($files)" else method.name
        if (method.returnType == Boolean::class.javaPrimitiveType) false else null
    } as `in`.bleuphoenix.claudechat.ui.compose.ChatActions

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the strip opens and its buttons reach the actions`() {
        val state = ChatState()
        state.review = ReviewState().apply {
            files += FileReview("/work/a/Main.kt", "val a = 1\n", created = false).apply { update("val a = 2\n") }
            files += FileReview("/work/a/Util.kt", "fun u() = 1\n", created = false).apply { update("fun u() = 2\nfun v() = 3\n") }
        }
        state.editedFiles += listOf("/work/a/Main.kt", "/work/a/Util.kt")
        state.background += BackgroundTask("b1", "local_bash", "./gradlew test").apply { outputFile = "/tmp/b1.output" }
        state.items += ToolItem("c1", "Bash", "git status", "{}", null, null, false)
        val scene = ImageComposeScene(440, 420, Density(1f)) { IntUiTheme(isDark = true) { ActivityStrip(state, actions) } }
        var time = 0L
        fun frames() = repeat(6) { scene.render(time); time += 50_000_000L }
        fun click(x: Int, y: Int) {
            val at = Offset(x.toFloat(), y.toFloat())
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
            frames()
        }
        try {
            frames()
            click(60, 14)
            assertTrue("clicking the summary opens the strip", state.activityExpanded)
            val hits = linkedSetOf<String>()
            for (y in 30 until 420 step 3) for (x in 20 until 440 step 5) {
                calls.clear()
                click(x, y)
                hits += calls
            }
            assertTrue(hits.toString(), "openChangedFile" in hits)
            assertTrue(hits.toString(), "rejectChanges(1)" in hits)
            assertTrue(hits.toString(), "acceptChanges(1)" in hits)
            assertTrue(hits.toString(), "acceptChanges(2)" in hits)
            assertTrue(hits.toString(), "rejectChanges(2)" in hits)
            assertEquals(2, state.changedFiles.size)
            assertTrue(hits.toString(), "openBackgroundOutput" in hits)
            assertTrue(hits.toString(), "stopBackground" in hits)
            assertTrue(hits.toString(), "showCommand" in hits)
        } finally {
            scene.close()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a section heading folds and unfolds its rows`() {
        val state = ChatState()
        state.tasks += TaskEntry("1", "Write the parser", "Writing the parser")
        state.items += ToolItem("c1", "Bash", "git status", "{}", null, null, false)
        state.activityExpanded = true
        val scene = ImageComposeScene(440, 300, Density(1f)) { IntUiTheme(isDark = true) { ActivityStrip(state, actions) } }
        var time = 0L
        fun frames() = repeat(6) { scene.render(time); time += 50_000_000L }
        fun click(y: Int) {
            val at = Offset(120f, y.toFloat())
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
            frames()
        }
        /** Clicks down the strip: where the Commands heading is, and whether a command row answered. Undoes each fold. */
        fun scan(): Pair<Int?, Boolean> {
            var heading: Int? = null
            var row = false
            for (y in 30 until 300 step 3) {
                val before = state.collapsedSections.toSet()
                calls.clear()
                click(y)
                if ("showCommand" in calls) row = true
                val changed = (state.collapsedSections.toSet() - before) + (before - state.collapsedSections.toSet())
                if (changed.isNotEmpty()) {
                    if ("Commands" in changed && heading == null) heading = y
                    changed.forEach { state.toggleSection(it) }
                    frames()
                }
            }
            return heading to row
        }
        try {
            frames()
            val (heading, row) = scan()
            assertTrue("the Commands heading folds its section", heading != null)
            assertTrue("rows show while the section is open", row)
            click(heading!!)
            assertTrue("Commands" in state.collapsedSections)
            assertTrue("a folded section's rows are hidden", !scan().second)
            click(heading)
            assertTrue("clicking again unfolds it", "Commands" !in state.collapsedSections)
        } finally {
            scene.close()
        }
    }
}

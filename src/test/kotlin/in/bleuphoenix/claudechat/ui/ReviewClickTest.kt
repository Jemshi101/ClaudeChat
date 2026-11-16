package `in`.bleuphoenix.claudechat.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import `in`.bleuphoenix.claudechat.review.FileReview
import `in`.bleuphoenix.claudechat.review.Hunk
import `in`.bleuphoenix.claudechat.review.ReviewActions
import `in`.bleuphoenix.claudechat.review.ReviewState
import `in`.bleuphoenix.claudechat.ui.compose.ReviewDocumentView
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.junit.Assert.assertTrue
import org.junit.Test

/** Clicks on the review tab's buttons reach the review actions. */
class ReviewClickTest {

    private val calls = mutableListOf<String>()
    private val actions = object : ReviewActions {
        override fun acceptHunk(file: FileReview, hunk: Hunk) { calls += "acceptHunk" }
        override fun rejectHunk(file: FileReview, hunk: Hunk) { calls += "rejectHunk" }
        override fun acceptFiles(files: List<FileReview>) { calls += "acceptFiles" }
        override fun rejectFiles(files: List<FileReview>) { calls += "rejectFiles" }
        override fun openAt(file: FileReview, line: Int) { calls += "openAt" }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `clicking along the top of a change finds its Reject and Accept`() {
        val review = ReviewState()
        review.files += FileReview("/work/Main.kt", "val a = 1\nval b = 2\nval c = 3\n", created = false).apply {
            update("val a = 1\nval b = 20\nval c = 3\n")
        }
        val scene = ImageComposeScene(820, 600, Density(1f)) { IntUiTheme(isDark = true) { ReviewDocumentView(review, actions) } }
        var time = 0L
        fun frames() = repeat(5) { scene.render(time); time += 50_000_000L }
        try {
            frames()
            val hits = linkedMapOf<String, Offset>()
            for (y in 0 until 600 step 3) for (x in 400 until 820 step 4) {
                val at = Offset(x.toFloat(), y.toFloat())
                calls.clear()
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
                frames()
                calls.forEach { hits.putIfAbsent(it, at) }
                if ("acceptHunk" in hits && "rejectHunk" in hits) break
            }
            println("HITS $hits")
            assertTrue("Reject reached at ${hits["rejectHunk"]}", "rejectHunk" in hits)
            assertTrue("Accept reached at ${hits["acceptHunk"]}", "acceptHunk" in hits)
        } finally {
            scene.close()
        }
    }
}

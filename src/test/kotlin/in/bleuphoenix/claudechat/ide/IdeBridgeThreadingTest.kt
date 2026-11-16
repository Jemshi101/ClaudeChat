package `in`.bleuphoenix.claudechat.ide

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.Document
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndGet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * The chat calls IdeBridge from Swing listeners (Send button, Enter key), which run on the EDT without the
 * write-intent lock. These tests post plain Swing events to reproduce that context.
 */
class IdeBridgeThreadingTest : BasePlatformTestCase() {

    override fun runInDispatchThread() = false

    fun `test saving from a Swing listener saves documents before continuing`() {
        val document = modifiedDocument()
        val continued = CountDownLatch(1)
        var unsavedWhenContinuing: Boolean? = null

        val error = onSwingListenerThread {
            IdeBridge(project).saveAllDocumentsThen {
                unsavedWhenContinuing = FileDocumentManager.getInstance().isDocumentUnsaved(document)
                continued.countDown()
            }
        }

        assertNull("saveAllDocumentsThen threw: $error", error)
        assertTrue("continuation never ran", continued.await(10, TimeUnit.SECONDS))
        assertEquals("document should be saved before the message is sent", false, unsavedWhenContinuing)
    }

    // Control: proves the test reproduces the bug from 0.3.0 (saving without taking the lock).
    fun `test saving without the lock fails from a Swing listener`() {
        modifiedDocument()

        val error = onSwingListenerThread { FileDocumentManager.getInstance().saveAllDocuments() }

        assertNotNull("expected the unguarded save to be rejected", error)
    }

    private fun modifiedDocument(): Document = runInEdtAndGet {
        val file = myFixture.configureByText("Sample.kt", "fun a() = 1\n").virtualFile
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "// edited\n") }
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
        document
    }

    private fun onSwingListenerThread(action: () -> Unit): Throwable? {
        var error: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                action()
            } catch (t: Throwable) {
                error = t
            }
        }
        return error
    }
}

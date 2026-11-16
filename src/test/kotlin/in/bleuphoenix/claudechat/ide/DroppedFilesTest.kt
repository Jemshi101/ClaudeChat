package `in`.bleuphoenix.claudechat.ide

import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.FileFlavorProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.lang.reflect.Proxy

/** The files a drop on the chat carries, from the OS and from drags inside the IDE. */
class DroppedFilesTest {

    private val files = listOf(File("/project/app/build.gradle.kts"), File("/project/app/src"))

    @Test
    fun `files dragged from Finder or Explorer`() {
        assertEquals(files, ImageAttachments.filesIn(transferable(DataFlavor.javaFileListFlavor, files)))
    }

    @Test
    fun `files dragged from the project tree or an editor tab`() {
        // The IDE's drag hands over its own drag event, holding the dragged files, rather than a file list.
        val provider = object : FileFlavorProvider {
            override fun asFileList() = files
        }
        val event = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DnDEvent::class.java)) { _, method, _ ->
            if (method.name == "getAttachedObject") provider else null
        } as DnDEvent
        val flavor = DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=" + DnDEvent::class.java.name, "IDE drag", DnDEvent::class.java.classLoader)
        assertEquals(files, ImageAttachments.filesIn(transferable(flavor, event)))
    }

    @Test
    fun `text carries no files`() {
        val text = StringSelection("hello")
        assertTrue(ImageAttachments.filesIn(text).isEmpty())
        assertFalse(ImageAttachments.hasImage(text))
    }

    @Test
    fun `dragged image files are images`() {
        val drag = transferable(DataFlavor.javaFileListFlavor, listOf(File("/tmp/screenshot.png")))
        assertTrue(ImageAttachments.hasImage(drag))
    }

    private fun transferable(flavor: DataFlavor, data: Any) = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(flavor)
        override fun isDataFlavorSupported(candidate: DataFlavor) = candidate == flavor
        override fun getTransferData(candidate: DataFlavor): Any = if (candidate == flavor) data else throw UnsupportedFlavorException(candidate)
    }
}

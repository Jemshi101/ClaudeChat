package `in`.bleuphoenix.claudechat.ide

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.FileCopyPasteUtil
import `in`.bleuphoenix.claudechat.ui.ImageAttachment
import org.jetbrains.skia.Image
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/** Turns clipboard images and image files into [ImageAttachment]s sized for Claude. */
object ImageAttachments {

    /** Claude downsizes anything larger; sending less keeps messages small. */
    private const val MAX_EDGE = 1568

    /** The API's per-image limit is 5 MB of base64. */
    private const val MAX_BASE64 = 5 * 1024 * 1024

    private val EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    fun isImageFile(file: File): Boolean = file.extension.lowercase() in EXTENSIONS

    /** The images in [transferable]: a copied image, or copied/dragged image files. */
    fun fromTransferable(transferable: Transferable, newKey: () -> String): List<ImageAttachment> {
        if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            val image = runCatching { transferable.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image }.getOrNull()
            image?.let { fromImage(it, "Pasted image", newKey())?.let { attachment -> return listOf(attachment) } }
        }
        return filesIn(transferable).filter(::isImageFile).mapNotNull { fromFile(it, newKey()) }
    }

    fun hasImage(transferable: Transferable): Boolean =
        transferable.isDataFlavorSupported(DataFlavor.imageFlavor) || filesIn(transferable).any(::isImageFile)

    /** Files in a copy or drag (Finder, Explorer, the project tree, editor tabs), if it carries any. */
    fun filesIn(transferable: Transferable): List<File> {
        runCatching { FileCopyPasteUtil.getFileList(transferable) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        // A drag inside the IDE carries the IDE's own drag event, which holds the dragged files.
        val ideDrag = transferable.transferDataFlavors.firstOrNull { DnDEvent::class.java.isAssignableFrom(it.representationClass) }
        val event = ideDrag?.let { runCatching { transferable.getTransferData(it) }.getOrNull() } as? DnDEvent
        return event?.let { runCatching { FileCopyPasteUtil.getFileListFromAttachedObject(it.attachedObject) }.getOrNull() }.orEmpty()
    }

    fun fromFile(file: File, key: String): ImageAttachment? {
        val image = runCatching { ImageIO.read(file) }.getOrNull() ?: return null
        return fromImage(image, file.name, key)
    }

    fun fromImage(source: java.awt.Image, name: String, key: String): ImageAttachment? {
        val image = scaled(source) ?: return null
        var mediaType = "image/png"
        var bytes = encode(image, "png")
        if (base64Length(bytes.size) > MAX_BASE64) {
            // Photos compress far better as JPEG; drop transparency first.
            val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
            rgb.createGraphics().apply { drawImage(image, 0, 0, java.awt.Color.WHITE, null); dispose() }
            mediaType = "image/jpeg"
            bytes = encode(rgb, "jpg")
        }
        if (base64Length(bytes.size) > MAX_BASE64) return null
        val thumbnail = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
        return ImageAttachment(key, name, mediaType, Base64.getEncoder().encodeToString(bytes), thumbnail)
    }

    private fun scaled(source: java.awt.Image): BufferedImage? {
        val width = source.getWidth(null)
        val height = source.getHeight(null)
        if (width <= 0 || height <= 0) return null
        val scale = minOf(1.0, MAX_EDGE.toDouble() / maxOf(width, height))
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(source, 0, 0, w, h, null)
            dispose()
        }
        return image
    }

    private fun encode(image: BufferedImage, format: String): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()

    private fun base64Length(bytes: Int) = (bytes + 2) / 3 * 4
}

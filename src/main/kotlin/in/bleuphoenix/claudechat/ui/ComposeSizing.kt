package `in`.bleuphoenix.claudechat.ui

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JComponent

/**
 * Gives a Compose panel fixed sizes for Swing's layout. Asked for its size, a Compose panel measures its content,
 * which throws ("layout state is not idle before measure starts") when Swing asks while Compose is laying out, as
 * when the tool window opens on Windows; the IDE then fails on every layout and freezes. The panels fill whatever
 * space their parent gives them, so a size of their own isn't needed.
 */
fun <T : JComponent> T.withSwingSizes(preferred: Dimension = JBUI.size(200, 200)): T = apply {
    preferredSize = preferred
    minimumSize = JBUI.size(0, 0)
}

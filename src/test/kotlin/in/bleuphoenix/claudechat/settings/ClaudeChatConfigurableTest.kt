package `in`.bleuphoenix.claudechat.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JLabel
import com.intellij.util.ui.UIUtil
import com.intellij.ui.components.JBTextField
import `in`.bleuphoenix.claudechat.cli.ClaudeCli

/** The settings page builds headless, shows the shortcuts, and saves a model typed into the model box. */
class ClaudeChatConfigurableTest : BasePlatformTestCase() {

    fun `test a typed model is saved and Default clears it`() {
        val saved = ClaudeChatSettings.options.model
        val configurable = ClaudeChatConfigurable()
        try {
            ClaudeChatSettings.options.model = null
            val component = configurable.createComponent()!!
            configurable.reset()
            @Suppress("UNCHECKED_CAST")
            val model = UIUtil.findComponentsOfType(component, ComboBox::class.java)
                .first { combo -> (0 until combo.itemCount).any { combo.getItemAt(it) == "default" } } as ComboBox<String>
            assertEquals("default", model.selectedItem)

            model.selectedItem = "claude-opus-4-8"
            assertTrue(configurable.isModified)
            configurable.apply()
            assertEquals("claude-opus-4-8", ClaudeChatSettings.options.model)

            model.selectedItem = "default"
            configurable.apply()
            assertNull(ClaudeChatSettings.options.model)

            // The empty path box shows the claude that would be used (or where this OS's installer puts it).
            val pathHint = UIUtil.findComponentsOfType(component, JBTextField::class.java).map { it.emptyText.text }
            val expected = ClaudeCli.findExecutable(null) ?: ClaudeCli.defaultLocations().first()
            assertTrue(pathHint.toString(), expected in pathHint)

            val labels = UIUtil.findComponentsOfType(component, JLabel::class.java).map { it.text }
            assertTrue(labels.toString(), labels.any { it.contains("Focus Claude Chat") })
        } finally {
            configurable.disposeUIResources()
            ClaudeChatSettings.options.model = saved
        }
    }
}

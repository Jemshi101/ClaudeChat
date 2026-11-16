package `in`.bleuphoenix.claudechat.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import `in`.bleuphoenix.claudechat.ui.McpStatus
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/**
 * The chat's MCP servers under the message box: green when all connected, red when one failed, amber when one needs
 * sign-in or approval. Hover lists them. Clicking offers to sign in to servers that need it and reconnect failed
 * ones, or opens the MCP Servers settings when all is well.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun McpIndicator(servers: List<McpStatus>, actions: ChatActions) {
    val colors = chatColors()
    val dot = when {
        servers.any { it.failed } -> colors.error
        servers.all { it.connected } -> colors.success
        else -> colors.brand
    }
    val fixable = servers.filter { it.needsAuth || it.failed }
    var open by remember { mutableStateOf(false) }
    Box {
    Tooltip(tooltip = {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            servers.forEach { Text("${it.name}: ${describe(it.status)}") }
            Text(if (fixable.isEmpty()) "Click to manage MCP servers" else "Click to sign in or reconnect", color = colors.muted)
        }
    }) {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { if (fixable.isEmpty()) actions.openMcpSettings() else open = !open }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            val label = servers.count { it.connected }.let { up -> if (up == servers.size) "MCP $up" else "MCP $up/${servers.size}" }
            Text(label, style = smallStyle(), color = if (dot == colors.success) colors.muted else dot)
        }
    }
    if (open) {
        PopupMenu(
            onDismissRequest = {
                open = false
                true
            },
            horizontalAlignment = Alignment.End,
        ) {
            fixable.forEach { server ->
                selectableItem(
                    selected = false,
                    onClick = {
                        open = false
                        if (server.needsAuth) actions.signInToMcp(server.name) else actions.reconnectMcp(server.name)
                    },
                ) { Text(if (server.needsAuth) "Sign in to ${server.name}" else "Reconnect ${server.name}") }
            }
            selectableItem(
                selected = false,
                onClick = {
                    open = false
                    actions.openMcpSettings()
                },
            ) { Text("Manage MCP Servers…") }
        }
    }
    }
}

private fun describe(status: String) = when (status) {
    "connected" -> "connected"
    "failed" -> "failed to connect"
    "needs-auth" -> "needs sign-in"
    "pending" -> "connecting…"
    else -> status
}

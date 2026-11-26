import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "in.bleuphoenix.claudechat"
version = "2.53.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

/** Jewel markdown modules bundled with the IDE; also declared in plugin.xml. */
val JEWEL_MARKDOWN_MODULES = listOf(
    "intellij.platform.jewel.markdown.core",
    "intellij.platform.jewel.markdown.ideLafBridgeStyling",
    "intellij.platform.jewel.markdown.extensions.gfmStrikethrough",
    "intellij.platform.jewel.markdown.extensions.autolink",
)

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("androidStudioPath"))
        composeUI()
        JEWEL_MARKDOWN_MODULES.forEach { bundledModule(it) }
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    // Jewel's standalone theme, used by offscreen render tests (the IDE bridge theme needs a running IDE).
    // The IDE's lib folder: Contents/lib in the macOS app, lib in a Windows or Linux install.
    testImplementation(files(providers.gradleProperty("androidStudioPath").map { studio ->
        listOf("Contents/lib", "lib").map { file("$studio/$it/testFramework.jar") }.firstOrNull { it.exists() }
            ?: file("$studio/Contents/lib/testFramework.jar")
    }))
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    buildSearchableOptions = false
    pluginVerification {
        ides {
            local(file(providers.gradleProperty("androidStudioPath").get()))
            // Not Android-specific: also checked against IntelliJ IDEA, so it's listed for all JetBrains IDEs.
            listOf("2026.2.4", "2026.3").forEach { create(IntelliJPlatformType.IntellijIdea, it) }
        }
    }
    pluginConfiguration {
        ideaVersion {
            // 2026.2.3: older 2026.2 builds lack Jewel functions the chat calls (VerticallyScrollableContainer, Markdown).
            sinceBuild = "262.10968"
            // No upper bound, so Android Studio updates don't disable the plugin. Recheck after major updates.
            untilBuild = provider { null }
        }
        changeNotes = recentNotes("""
            <ul>
              <li>2.53.0: Voice input, commands, rules and memory. A mic next to the send button: local Whisper records
                  and transcribes on your computer (brew install whisper-cpp, then Download a model in Settings), or
                  the system's dictation. New Commands page to add, edit and delete your / commands, and Rules &amp;
                  Memory to read and change the CLAUDE.md files and the memory Claude loads, or add a rule. Both are
                  in the Claude tool window's ⚙ menu.</li>
              <li>2.52.0: Long chats. When Claude Code compacts a chat (replaces it with a summary), a line shows where,
                  with the size before and after, also in chats reopened from history. Click the context ring for
                  Compact now, or Compact keeping something in focus. From 200k tokens a bar offers Compact now or a
                  new chat. New setting: Compact the conversation at, Auto or a fixed 200k to 500k tokens.</li>
              <li>2.51.1: A new screenshot in the description: Debug mode with its reproduction steps, beside Fix with
                  Claude and Apply on a code block.</li>
              <li>2.51.0: The same failure several times in a row shows once with a count: a command Claude retries
                  that keeps failing with the same input, or a repeated error, appears as its last try marked 3×.</li>
              <li>2.50.0: New settings: Add to system prompt (your own instructions for every chat), Turn limit (the
                  most steps Claude takes for one message; reaching it is a note, not an error) and Chat text size
                  (90% to 150%, applied to open chats at once).</li>
              <li>2.49.0: MCP sign-in. Sign In and Sign Out on the MCP Servers page; in the chat, click MCP under the
                  message box to sign in to a server that needs it (in the browser) or reconnect one that failed. The
                  server dialog gains OAuth settings: client ID and secret, scopes, callback port and metadata URL.</li>
              <li>2.48.0: Settings | Tools | Claude Chat Suite | Claude Code Settings edits the permission rules (Allow,
                  Ask, Deny) and environment variables in Claude Code's settings files, for this project (only you, or
                  shared) or all projects. Other settings in the files are kept; Open in Editor shows the whole file.</li>
              <li>2.47.0: An effort picker beside the model picker sets how much Claude thinks before answering, from
                  Low to Max, with the levels the model supports. It switches the open chat from its next reply and sticks
                  for new chats; a default is in Settings | Tools | Claude Chat Suite.</li>
              <li>2.46.0: Apply on code blocks in Claude's replies: Claude merges the block into the file it names (or
                  the file open in the editor), and the change shows in the editor to accept or reject. A file that
                  doesn't exist yet can be created from the block. Code block headers show the file they're for.</li>
              <li>2.45.0: Alt+Enter → Fix with Claude on a line with an error or warning sends the IDE's messages to
                  the chat with those lines attached. A Claude button on the commit message box writes the message from
                  the changes ticked for commit, following your draft and the repository's recent commit style.</li>
              <li>2.44.0: Right-click → Claude in the editor and project tree: Explain, Find and Fix Bugs, Refactor,
                  Write Tests and New Chat About This, for the selection, a file or a folder. In the Run/Debug console
                  and Logcat, Explain and Fix or Add to Chat sends the output to Claude. Long pastes become a chip in
                  the message box (double-click to expand).</li>
              <li>2.43.0: Resting the pointer on Accept or Reject under a change in the editor shows its keyboard
                  shortcut (Cmd+Y / Cmd+N, or Ctrl+Y / Ctrl+N), as set in your keymap.</li>
              <li>2.42.0: A cleaner Plan ready bar on the plan tab: the buttons sit on the right with Build last and
                  showing its shortcut, a shorter hint, and a neutral divider. Narrow tabs put the buttons under the title.</li>
              <li>2.41.0: Renamed to Claude Chat Suite. Same plugin ID, so it updates in place; settings, MCP keys,
                  shortcuts and chats are kept.</li>
              <li>2.40.0: Renamed to Claude Chat Companion for JetBrains Marketplace (the name Claude Chat is taken
                  there). Same plugin ID, so it updates in place; settings, MCP keys, shortcuts and chats are kept.</li>
              <li>2.39.0: Ready for JetBrains Marketplace: GPL-3.0 license (forks credit the original author), BleuPhoenix
                  as vendor, a description with setup steps and screenshots, and listed for all JetBrains IDEs from
                  2026.2.3 (build 262.10968), not only Android Studio. "Use Cursor's
                  commands" is now off for new installs; existing settings are kept.</li>
              <li>2.38.0: The plugin's ID is now in.bleuphoenix.claudechat (was dev.claudechat), and its code moved to
                  that package. Uninstall the old Claude Chat before installing this one, or both load; settings, MCP
                  keys, shortcuts and chats carry over.</li>
              <li>2.37.0: The activity strip's Tasks, Background, Changed files and Commands sections fold and unfold
                  from their headings, which show a count; folded sections stay folded in every chat, also after a
                  restart.</li>
              <li>2.36.0: Accept (Cmd+Y, Ctrl+Y on Windows) or reject (Cmd+N, Ctrl+N) the change at the caret, as in
                  Cursor, then go on to the next one. Only while the caret is on a pending change; elsewhere the keys
                  keep their usual actions (Quick Definition, Generate, Go to Class, Delete Line). Rebind under
                  Settings | Keymap: Accept/Reject Claude Change.</li>
              <li>2.35.0: F7 and Shift+F7 move between Claude's pending changes in the editor, as in the merge window:
                  next or previous change, then on to the next or previous edited file, with "Claude change 2 of 5"
                  shown at the caret. Only in files with changes waiting, so elsewhere F7 still steps into code while
                  debugging. Rebind them under Settings | Keymap: Next/Previous Claude Change.</li>
              <li>2.34.0: Copies of project files into the temp folder run without asking (cp file /tmp/x,
                  git show HEAD:file &gt; /tmp/x), and reading them back does too, in commands and in Claude's Read,
                  Glob and Grep tools (a design unzipped into /tmp, say). Writing through a symlink planted in /tmp still
                  asks.</li>
              <li>2.33.1: More "verify" commands run without asking: sed substitutions and other edits of what sed prints
                  (still asking for -i, w, r and e), and grep patterns built from an earlier $(…), as in
                  c=$(grep … | sed -E "s/…/\1/"); grep -c "Key.${"$"}c" file.</li>
              <li>2.33.0: Fixes chats not starting on Windows with "CreateProcess error=206, The filename or extension is
                  too long": instruction files (agent.md, spec.md) now reach Claude as a file instead of on the command
                  line, which Windows limits to 32,767 characters. More read-only commands run without asking: Python
                  scripts that only read (no writing, subprocess or network), echo with $(…) and $?, git -C in for
                  loops, timeout, dart and flutter analyze, dart format --output=none, heredocs, and Claude's own plans
                  and saved tool output (~/.claude/plans, …/tool-results). Git Bash drive paths (/d/…) count on
                  Windows.</li>
              <li>2.32.0: Shell commands that only read files inside the project run without asking, as Claude's own
                  Read and Grep do: grep, rg, sed -n, cat, head, ls, find, wc, awk, jq and read-only git, with cd, pipes,
                  &amp;&amp;, for loops and variables. Reading outside the project (.., ~, absolute paths, symlinks
                  leading out), writing (redirects, sed -i, find -delete…) or running anything else still asks. Turn it
                  off in Settings → Tools → Claude Chat.</li>
              <li>2.31.0: Notifications, as in Cursor: when Claude finishes, asks for permission, asks a question or has a
                  plan ready, and you aren't looking at that chat (Android Studio in the background, the Claude window
                  closed, or another tab open). In the background they appear in the system's notification center;
                  Open Chat brings the chat forward at the request. Requests also bounce the Dock icon or flash the
                  taskbar, and are taken back once answered. Quick turns (under 10 seconds) don't notify. Choose what
                  notifies, and a sound, in Settings → Tools → Claude Chat → Notifications.</li>
              <li>2.30.0: Cursor's commands work here too: commands in ~/.cursor/commands are copied to Claude Code's
                  (~/.claude/commands) when a chat starts, so they're under / in the chat and in claude, and to
                  Antigravity's global workflows when it's installed. New and edited commands are copied; a name Claude
                  Code already uses gets -flow (simplify becomes simplify-flow). Turn it off in Settings → Tools →
                  Claude Chat.</li>
              <li>2.29.0: MCP servers, as in Cursor: Settings → Tools → Claude Chat → MCP Servers lists the servers Claude
                  Code is configured with (all projects, this project, the project's .mcp.json) with their status, and
                  adds, edits and removes them. An API key is kept in the system keychain; the
                  config holds ${"$"}{CLAUDE_CHAT_MCP_&lt;NAME&gt;} in its place, which chats fill in. A key already written
                  in the config is offered for moving to the keychain. Under the message box, MCP shows how this
                  chat's servers are doing; click it to manage them.</li>
              <li>2.28.1: Fixes Android Studio freezing when the Claude Chat window opens (seen on Windows, with "Unable to
                  actualize size of Compose content"). Swing no longer asks the chat's Compose views for their size,
                  which made Compose measure in the middle of its own layout and then fail on every layout after.</li>
              <li>2.28.0: Drag files, folders and images onto the chat, as in Cursor: from the project tree, editor tabs,
                  Finder or Explorer. Drops used to go nowhere, because the chat's Compose view takes them before the
                  IDE's drop handling sees them. While dragging, the chat shows where to drop.</li>
              <li>2.27.3: On Windows, when there's no Claude Code install of its own, the copy the Claude desktop app
                  keeps (%LOCALAPPDATA%\\Claude-Data\\...\\claude-code\\&lt;version&gt;\\...\\claude.exe) is found, at its
                  newest version, so it keeps working after the app updates it.</li>
              <li>2.27.2: On Windows, claude is found where its installers put it (%USERPROFILE%\\.local\\bin\\claude.exe,
                  npm's %APPDATA%\\npm\\claude.cmd) or on the PATH, and the settings page names those places. An empty
                  CLI path box shows the claude that will be used.</li>
              <li>2.27.1: Reject and Accept under a change in the editor work again when the IDE also paints the change
                  elsewhere, such as in sticky lines, and when the mouse moves a little while clicking.</li>
              <li>2.27.0: Debug mode, as in Cursor (red): Claude lists what could cause the bug, adds logging tagged
                  ClaudeDebug and gives reproduction steps. Reproduce the bug and press Proceed (or Enter) for Claude to
                  read the logs and fix the cause; Mark as fixed has it remove the logging.</li>
              <li>2.26.0: Ask mode, as in Cursor (green): Claude reads and searches the code to answer, and the chat
                  declines any edit or command that would need approval, without asking you.</li>
              <li>2.25.0: Each mode has its own color, on the mode chip, in its menu and on the message box's border:
                  Agent blue, Agent · auto-edit teal, Plan amber, Auto violet.</li>
              <li>2.24.1: If a plan's tab can't be opened, the chat says why, and the IDE log records each step.</li>
              <li>2.24.0: Build is always offered on the latest plan, as in Cursor: after Keep planning, or when Claude
                  revised the plan without presenting it again, Build leaves Plan mode and asks Claude to implement the
                  plan file. Cmd/Ctrl+Enter in the Plan tab builds.</li>
              <li>2.23.0: Signing in from the chat. When Claude Code isn't signed in (e.g. a fresh install), or you type
                  /login, a Sign in card runs the CLI's sign-in: it opens the browser, offers the sign-in page and takes
                  the code the page shows, then sends your message again. A Claude subscription or an Anthropic
                  Console account. Errors like "Not logged in" no longer show twice.</li>
              <li>2.22.0: The activity strip gathers everything: Claude's tasks (moved from their own panel), background
                  work, changed files, and this chat's recent commands with status and duration (click one to jump to
                  its card). The summary reads e.g. "Tasks 2/5 · 1 running · 3 Files +42 −7". Toggle Claude Chat
                  Activity (Cmd+Alt+J on macOS, Ctrl+Alt+' elsewhere) opens or closes it.</li>
              <li>2.21.0: Background work in the strip above the message box: shell commands and agents Claude runs in the
                  background, with their time, activity, Output and Stop; a notice when one finishes, and Claude's
                  follow-up shows as working. While Claude works, the round button sends what's typed and turns back
                  into Stop when the box is empty. Fix: a background shell command's card no longer showed agent
                  progress.</li>
              <li>2.20.0: Changes strip above the message box, like Cursor's: "› 3 Files +42 −7" for this chat's changes
                  still waiting for review, with Stop and Review; open it for each file's counts, Reject / Accept, and
                  Reject all / Accept all. Click a file to open it at its change.</li>
              <li>2.19.0: Comment while Claude works: Queue (Enter) waits for the task to finish, Send now (Alt+Enter)
                  reaches Claude at its next step without stopping it, Stop &amp; send (Cmd/Ctrl+Enter) interrupts the
                  task and sends. Queued messages can also be sent now or with a stop.</li>
              <li>2.18.0: Plan to-dos tick themselves off while the plan is built: Claude tracks the build with one task per
                  to-do, and each task it completes ticks the matching to-do in the plan file and the Plan tab.</li>
              <li>2.17.0: Plan to-dos like Cursor's: Claude ends plans with a to-do list, which the Plan tab shows as a card
                  ("4 To-dos") where steps are ticked off or added (+ New), saved in the plan file. One plan file per
                  chat: a revised plan replaces it instead of adding another .md. Inline code in a calmer sage green.</li>
              <li>2.16.1: Fix: Reject and Accept on a single change in the review tab did nothing when the file had changed
                  since the change was shown (e.g. formatting or saving after an inline edit); the change is now found
                  again, and a click that can't apply is logged.</li>
              <li>2.16.0: Color for skimming: section headings in orange, inline code in violet, file links in blue, list
                  markers tinted, body text a shade softer so bold stands out, quotes with an orange bar. Claude is
                  asked to bold the key points (conclusions, files to change, risks); turn it off in Settings → Tools →
                  Claude Chat.</li>
              <li>2.15.1: Easier reading: replies, plans, your messages and plan summaries at a 1.6 line height, more room
                  between list items, headings and blocks, a fainter tint behind inline code, and evenly spaced plan
                  cards.</li>
              <li>2.15.0: Chat tabs drawn by the plugin, each with its own close button (the IDE's tabs only showed × on
                  hover, and none once the narrow window turned them into a list). Flowcharts route arrows that go back
                  against the flow around the side, in their own lanes, instead of through the middle.</li>
              <li>2.14.0: A modern look for plans and replies: tables (also right after a line of text), headings without
                  rules, quieter inline code, code blocks as cards with Copy (in the Plan tab too), strikethrough and
                  bare links. Your IDE keymap now works in the chat, Plan and Review views: copy, cut, paste, select
                  all, undo/redo, line and word moves and deletes, e.g. Ctrl+C or Home/End with a Windows keymap on a
                  Mac.</li>
              <li>2.13.1: Fix: the Plan and Review tabs, long tool output and subagent output didn't scroll; their
                  content was cut off instead.</li>
              <li>2.13.0: Color coding: commands and skills (/name) in Claude's orange, files (@path and file chips) in
                  the link color, in the message box as you type and in sent messages.</li>
              <li>2.12.1: A command picked later in a message is completed where it was typed (Claude runs a skill named
                  there); Close Chat and Close Other Chats in the tool window's title bar and ⋮ menu, since a tab's ×
                  is hidden once the tabs turn into a list; closing a chat that's still working asks first.</li>
              <li>2.12.0: Slash commands anywhere: "/" after a space later in a message lists the commands too, and
                  picking one moves it to the front so the CLI runs it with the message as its arguments. The "/" list
                  also appears when it was typed before the commands had loaded. Plan cards have Show full plan.</li>
              <li>2.11.0: Instruction files: give Claude your own project guides (e.g. agent.md, spec.md) instead of
                  CLAUDE.md, in chats and Edit with Claude. Settings → Tools → Claude Chat.</li>
              <li>2.10.1: Polish: the welcome screen describes the current mode (it said "you approve each action" in
                  every mode) and shows the plugin's shortcuts; Settings lists the CLI's models and the shortcuts;
                  long chats no longer keep a second copy of every reply; updated plugin description.</li>
              <li>2.10.0: Edit with Claude (Cmd+Alt+K / Ctrl+Alt+\): describe a change to the selected code, or code to
                  add at the caret, and review Claude's edit in place with Reject / Accept, like Cursor's Cmd+K.</li>
              <li>2.9.1: Fix: Reject / Accept under a change in the editor didn't respond to clicks when the editor had
                  grown wider since the change appeared.</li>
              <li>2.9.0: Input power: model picker in the message box, slash-command completion for the CLI's commands
                  and skills, messages queued while Claude works (editable and removable until sent), and Focus Claude
                  Chat (Cmd+Shift+J / Ctrl+Shift+;) to jump to the message box with the selection attached.</li>
              <li>2.8.0: Agent visibility: Claude's task list pinned above the message box (task tools enabled; can be
                  turned off in settings), live progress on sub-agent cards, and a context meter.</li>
              <li>2.7.0: Message actions: copy messages and code blocks, insert code at the editor caret, edit any of
                  your messages (the conversation continues from just before it; optionally undoing later file
                  changes), retry the last message.</li>
              <li>2.6.0: Chat history: past chats for the project (including ones started in a terminal) in a searchable
                  list and on the welcome screen; reopen one to see it and continue it; rename and delete. Chat tabs are
                  named after the chat.</li>
              <li>2.5.0: Checkpoints: "Restore checkpoint" under a message puts the files Claude changed after it back as
                  they were (files it created are deleted); Claude is told with the next message.</li>
              <li>2.4.0: Context for messages: the current file as a removable chip, @ to search and add project files
                  and folders, paste or drop images and files, Ask Claude About Selection adds the lines as a chip.
                  Sent messages show their context.</li>
              <li>2.3.1: Changes also show in the editor itself: added lines in green, removed lines in red where they
                  were, and Reject / Accept under each change.</li>
              <li>2.3.0: Review Claude's changes in a "Claude Changes" tab: accept or reject each change, each file, or
                  everything; rejecting puts the old lines back (undoable) and Claude is told on the next message.
                  A "Files changed" card follows each turn that edited files. Plan files are named after the plan.</li>
              <li>2.2.0: Plans open in a rendered Plan tab with Mermaid flowcharts drawn inline (also in chat replies);
                  Claude is asked to include a flow diagram in plans. Modes renamed Agent, Agent · auto-edit, Plan, Auto;
                  Shift+Tab switches mode. "Allow always" buttons now say where they apply.</li>
              <li>2.1.0: Plans open as markdown tabs, with a plan card to Build, Build and ask before edits, or keep planning.</li>
              <li>2.0.0: New Compose UI: animated messages, bottom-anchored streaming, tool cards with live status,
                  growing message box with a send/stop button, mode chip, welcome screen with suggestions.</li>
              <li>1.0.0: Settings page (CLI path with a test button, model, default permission mode), icons.</li>
              <li>0.3: Clickable file links, Ask Claude About Selection, review diffs, saving and refreshing files.</li>
              <li>0.2: Allow always and a permission-mode selector.</li>
              <li>0.1: Chat tool window with streaming replies, tool cards, permission prompts and Stop.</li>
            </ul>
        """.trimIndent())
    }
    // Signing and publishing read their secrets from the environment, never from the repo:
    //   CERTIFICATE_CHAIN_FILE, PRIVATE_KEY_FILE, PRIVATE_KEY_PASSWORD  (see README: Publishing)
    //   PUBLISH_TOKEN                                                   (a JetBrains Marketplace token)
    signing {
        certificateChainFile = providers.environmentVariable("CERTIFICATE_CHAIN_FILE").map { file(it) }
        privateKeyFile = providers.environmentVariable("PRIVATE_KEY_FILE").map { file(it) }
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

/** The listing shows the latest [count] versions' notes; the rest are on GitHub Releases. */
fun recentNotes(all: String, count: Int = 8): String {
    val items = Regex("<li>.*?</li>", RegexOption.DOT_MATCHES_ALL).findAll(all).map { it.value }.toList()
    return "<ul>\n" + items.take(count).joinToString("\n") + "\n</ul>\n" +
        "<p>Older versions: <a href=\"https://github.com/Jemshi101/ClaudeChat/releases\">GitHub Releases</a>.</p>"
}

// Sandbox runs for manual and scripted checks:
//   -PrunIdeProject=<dir>   open that project without the trust prompt
//   -PautoPrompt=<text>     the first chat sends this in Plan mode (see ChatPanel.sendDevAutoPrompt)
//   -PautoMode=<id>         ...or in this permission mode instead (e.g. acceptEdits)
//   -PautoAttach=<paths>    ...with these files or images attached (path-separator separated)
//   -PautoResume            ...as a follow-up in the project's most recent chat
//   -PautoSteps=<a||b>      ...then, after each turn, the next step ("edit:<text>" edits the last message,
//                           "model:<value>" picks a model)
//   -PtestScript=<file>     run a Performance Testing plugin script (%sleep, %takeScreenshot, %exitApp, ...)
tasks.named<RunIdeTask>("runIde") {
    providers.gradleProperty("runIdeProject").orNull?.let { project ->
        args(project)
        jvmArgs("-Didea.trust.all.projects=true")
    }
    providers.gradleProperty("autoPrompt").orNull?.let { jvmArgs("-Dclaudechat.dev.autoPrompt=$it") }
    providers.gradleProperty("autoMode").orNull?.let { jvmArgs("-Dclaudechat.dev.autoMode=$it") }
    providers.gradleProperty("autoAttach").orNull?.let { jvmArgs("-Dclaudechat.dev.autoAttach=$it") }
    providers.gradleProperty("autoResume").orNull?.let { jvmArgs("-Dclaudechat.dev.autoResume=true") }
    providers.gradleProperty("autoSteps").orNull?.let { jvmArgs("-Dclaudechat.dev.autoSteps=$it") }
    providers.gradleProperty("testScript").orNull?.let {
        // Compose panels draw through Skia by default, which %takeScreenshot can't capture.
        jvmArgs("-Dtestscript.filename=$it", "-Dcompose.swing.render.on.graphics=true")
    }
}

// Tests write plan files under build/, not the user's ~/.claude/plans (see PlanFiles).
tasks.named<Test>("test") {
    systemProperty("claudechat.plansDir", layout.buildDirectory.dir("test-plans").get().asFile.path)
}

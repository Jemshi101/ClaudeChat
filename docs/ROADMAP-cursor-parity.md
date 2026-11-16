# Roadmap: Cursor chat parity

Goal: the Claude tool window should cover what Cursor's chat panel does, built on the local `claude` CLI.
Each phase ships as its own plugin version, with unit tests, offscreen renders (`ChatScreenRenderTest`), a Plugin
Verifier run, and a hands-on check in Android Studio before the next phase starts.

Protocol facts each phase relies on are checked against the real CLI first (deny-only test runs) and recorded in
`docs/PROTOCOL.md`. Items marked **Unknown** need that check before building.

## Phase 1 — Plans as markdown tabs (2.1, 2.2) — done

Cursor: plan mode writes a markdown plan, opens it in an editor tab, you edit it, then Build.

- When Claude finishes planning (`ExitPlanMode`), open the plan file in an editor tab with the rendered preview
  next to the editor. The CLI already writes it to `~/.claude/plans/<slug>.md` (`planFilePath`).
- Plan card in the chat: title, Build (auto-accept edits), Build and ask before edits, Keep planning, Open plan.
- Build sends the file's current text, so edits made in the tab are what Claude implements.
- Keep planning tells Claude to wait for feedback; the next message is the feedback.
- Hide the raw ExitPlanMode tool card.
- Plan tab renders the plan as a document with Mermaid flowcharts drawn inline (native parser, layered layout,
  Compose drawing; no browser). Claude is asked to include a flowchart in each plan (setting, on by default).
- Modes named like Cursor (Agent, Plan, …); Shift+Tab cycles them.
- **Unknown:** whether the CLI uses an edited `plan` from `updatedInput` (verify in the IDE).

## Phase 2 — Review changes (2.3) — done

Cursor: edits land in the files right away; a review shows each change with Keep / Undo.

- Baseline per file from the CLI's `tool_use_result.originalFile` (exact, no race with the CLI's write).
- "Claude Changes" tab: files with +/− counts, each hunk with context, Reject / Accept per hunk, per file, all.
- Reject edits the document (undoable) and saves; rejecting a created file deletes it. Claude is told which files
  had rejected changes with the next message.
- "Files changed" card after each turn that edited files; the tab opens (without focus) at turn end.
- Plan files are named after the plan's title instead of the CLI's random words.
- In the editor too (2.3.1): added lines highlighted, removed lines as a block inlay, Reject / Accept buttons
  painted in an inlay under each change.

## Phase 3 — Context: chips, @-mentions, images (2.4) — done

- Current editor file shown as a removable chip above the input and sent as context.
- `@` opens a popup to search project files and folders; picks become chips.
- Drag files from the project tree or editor tabs onto the chat.
- Paste or drop images (screenshots) as attachments.
- Resolved: stream-json input takes image blocks, and the CLI expands `@path`, `@"path with spaces"` and
  `#L10-20` mentions itself (see docs/PROTOCOL.md). Checked end to end in the sandbox IDE.

## Phase 4 — Checkpoints (2.5) — done

- Done with plugin-side snapshots: each edit's `originalFile` is recorded per message (`review/Checkpoints.kt`);
  restoring writes the earliest text per file from that message on, through the documents (undoable).
- Not covered: changes made by shell commands. The CLI's own rewind wasn't needed, since edit results already
  carry the text before each change.
- Checkpoints last as long as the chat; they'll need saving once chats can be reopened (Phase 5).

## Phase 5 — Chat history (2.6) — done

- Sessions come from `~/.claude/projects/<project path with non-alphanumerics as '-'>/<id>.jsonl`; titles from the
  latest `custom-title`, else `ai-title`, else the first message. Rename appends a `custom-title` entry, as the
  CLI's /rename does. Reopening replays the transcript through the event reducer and continues with `--resume`
  (checked in the sandbox: same session, earlier context kept).
- Not replayed: plan cards (plans come from permission requests, which transcripts don't hold) and images.
- Checkpoints and pending reviews of a reopened chat start fresh.

- History list of past chats for the project (from the CLI's session transcripts), with titles and times.
- Reopen a chat: replay its transcript into the view and continue it with `--resume`.
- Rename and delete.

## Phase 6 — Message actions (2.7) — done

- Resolved: the CLI forks a session at any message with `--resume <id> --resume-session-at <uuid> --fork-session`;
  stream-json events carry the same uuids as the transcript. Each message records the last uuid before it (its
  fork point), so any message can be edited, not just the last. Checked in the sandbox (the replaced message is
  forgotten).
- The original session stays in the history as its own chat.

- Copy a message; copy a code block or insert it at the editor caret.
- Edit and resend the last message; retry a failed turn.
- **Unknown:** forking a session at an earlier message, needed for editing older messages.

## Phase 7 — Agent visibility (2.8) — done

- Task list: the CLI leaves its task tools (TaskCreate/TaskUpdate/TaskList/TaskGet) out of the `-p` default set
  and `--tools` replaces rather than adds, so the chat learns the default set from `init` and passes it back with
  the task tools (`cli/TaskTools.kt`); re-learned when the CLI version changes. Checked in the sandbox.
- Sub-agents (`Agent` tool): `task_started` / `task_progress` (activity, tool uses, tokens) / `task_notification`.
- Context meter: per-message `usage` over the model's `contextWindow` from the result's `modelUsage`.

- Live to-do list from Claude's TodoWrite calls, pinned above the input while a task runs.
- Sub-agent (Task) cards showing progress.
- Context usage meter from the CLI's usage reports.

## Phase 8 — Input power (2.9) — done

- Model picker in the composer, from the CLI's own model list (`initialize`); switches a running chat with
  `set_model` and becomes the default for new chats. Follows `/model` typed in the chat.
- Slash-command autocomplete from `initialize` and `commands_changed`: `/` at the start lists commands and skills;
  Enter or Tab completes, Enter on a full name sends. Commands go to the CLI without the chat's added context.
  `/clear` starts the view over.
- Queued messages: sending while Claude works queues the message above the box (click to edit, × to remove);
  they're sent in order as turns end. Stop puts them back in the box.
- Focus Claude Chat: Cmd+Shift+J on macOS, Ctrl+Shift+; elsewhere (Cmd+L is Go to Line and Cmd+Shift+L is the
  emulator's tilt); adds the editor selection to the context first, like Cursor's Cmd+L. Rebindable in the keymap.

## Phase 9 — Inline edit, Cmd+K (2.10) — done

- **Edit with Claude…** (editor menu; Cmd+Alt+K on macOS, Ctrl+Alt+\ elsewhere, since Cmd+K is Commit): a box at
  the caret for an instruction about the selection, or for code to insert at the caret.
- One-shot `claude -p` with no tools and no session (docs/PROTOCOL.md, "Inline edits"): Claude only answers with
  the new text, and the plugin applies it as one undoable command. The range is tinted while Claude works; the
  progress can be cancelled; if the code there changed meanwhile, nothing is applied.
- The edit shows as a change in the editor with Reject / Accept (the review from Phase 2). Rejecting it isn't
  reported to the chat, which didn't make it.

## Out of scope

- Cursor's own models and its codebase index; Claude Code searches with its tools instead.
- Inline editor completions (Cursor Tab): a separate feature from the chat panel.

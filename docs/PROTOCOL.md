# Claude CLI stream-json protocol

Observed against Claude Code CLI 2.1.309 (2026-10-23). The plugin talks to the CLI over
stdin/stdout, one JSON object per line.

## Launch

```
claude -p --input-format stream-json --output-format stream-json \
       --verbose --include-partial-messages \
       --permission-prompts host --permission-prompt-tool stdio \
       [--model <alias>] [--permission-mode <mode>] [--resume <session_id>]
```

- Run with the project base path as the working directory.
- `--permission-prompt-tool stdio` is hidden from `--help` but required: without it,
  `--permission-prompts host` alone denies every prompting tool call (`system/permission_denied`)
  and no `control_request` reaches the host.
- Launch `claude` by absolute path (`~/.local/bin/claude`); IDEs started from the Dock do not
  inherit the shell `PATH`.

## Host → CLI (stdin)

User message:

```json
{"type":"user","message":{"role":"user","content":"..."}}
```

Permission answer (reply to a `can_use_tool` request, same `request_id`):

```json
{"type":"control_response","response":{"subtype":"success","request_id":"<id>",
  "response":{"behavior":"allow","updatedInput":{...original input...}}}}
{"type":"control_response","response":{"subtype":"success","request_id":"<id>",
  "response":{"behavior":"deny","message":"<reason shown to Claude>"}}}
```

Both `allow` and `deny` are confirmed working (allow verified in the IDE on 2026-10-23).

"Allow always": add the chosen entries of the request's `permission_suggestions` to an allow answer
as `"updatedPermissions": [ ... ]` (Agent SDK shape; not yet verified against the CLI).

Change permission mode mid-session (verified):

```json
{"type":"control_request","request_id":"<id>","request":{"subtype":"set_permission_mode","mode":"plan"}}
```

The CLI answers with `control_response` (`response.mode`) and a `system`/`status` event carrying
`permissionMode`. `manual` is reported back as `default`.

### `permission_suggestions` (observed)

```json
[{"type":"setMode","mode":"acceptEdits","destination":"session"}]                       // Write/Edit
[{"type":"addRules","behavior":"allow","destination":"localSettings",
  "rules":[{"toolName":"Bash","ruleContent":"mkdir -p out"},
           {"toolName":"Bash","ruleContent":"touch out/a.txt"}]}]                        // Bash
```

`destination`: `session` (this chat), `localSettings` (`.claude/settings.local.json`),
`projectSettings` (`.claude/settings.json`), `userSettings` (`~/.claude/settings.json`).
Read-only Bash commands such as `git status` run without a prompt in the default mode.

## CLI → host (stdout)

| `type` / `subtype` | Use |
|---|---|
| `system` / `init` | `session_id`, `cwd`, `tools`, `mcp_servers`, `permissionMode`, `model`. Store `session_id` for `--resume`. |
| `system` / `status` | `status: "requesting"` while waiting on the API: show a busy indicator. Also carries `permissionMode` after a mode change. |
| `system` / `thinking_tokens` | Estimated thinking tokens; optional "thinking…" indicator. |
| `system` / `commands_changed` | Slash commands and skills available (for input autocomplete; see "Models and slash commands"). |
| `conversation_reset` | `/clear` started a new session; a new `init` follows. |
| `system` / `permission_denied` | A tool call was denied without asking the host. |
| `stream_event` | Raw API stream: `message_start`, `content_block_start` (`text`, `tool_use`, `thinking`), `content_block_delta` (`text_delta`, `input_json_delta`), `content_block_stop`, `message_stop`. Drives live typing. |
| `assistant` | Complete assistant message (`content`: `text`, `tool_use`, `thinking`). Source of truth after streaming. |
| `user` | Tool results (`content[].type == "tool_result"`, `tool_use_id`, `is_error`). |
| `control_request` / `can_use_tool` | Permission prompt: `request_id`, `tool_name`, `display_name`, `input`, `description`, `permission_suggestions`, `tool_use_id`. |
| `rate_limit_event` | Usage window utilization; optional status display. |
| `result` | End of turn: `subtype`, `result` (final text), `total_cost_usd`, `usage`, `session_id`. |

All events carry `session_id`; most carry `uuid`. `parent_tool_use_id` is non-null for
sub-agent output.

Closing stdin ends the session after the current turn.

## Plans (observed 2026-10-24)

In plan mode Claude finishes by calling `ExitPlanMode`. The host gets a `can_use_tool` request:

```json
{"subtype":"can_use_tool","tool_name":"ExitPlanMode","requires_user_interaction":true,
 "input":{"plan":"# Title\n\n## Context\n...","planFilePath":"/Users/me/.claude/plans/<slug>.md"}}
```

The CLI has already written the plan to `planFilePath`. Approve with an allow answer; the plugin sends the file's
current text as `updatedInput.plan` (whether the CLI uses edited text is not yet verified) and, for "Build", a
`setMode`/`acceptEdits`/`session` entry in `updatedPermissions`. Deny with a message to keep planning.

## Edit results (observed 2026-10-24)

Each tool result arrives as its own `user` event. Next to `message` it carries `tool_use_result`, the tool's
structured output (fixture: `src/test/resources/protocol/edit-with-original.ndjson`):

- `Edit`: `{filePath, oldString, newString, originalFile, structuredPatch, userModified, replaceAll}`.
  `originalFile` is the whole file before this edit; after an earlier edit it already includes that edit.
- `Write`: `{type: "create" | "update", filePath, content, structuredPatch, originalFile}`; `originalFile` is null
  for `create`.
- `Read`: `{type: "text", file: {filePath, content, ...}}`.

The review tab takes a file's baseline from the first edit's `originalFile`, so it doesn't race the CLI's write.

## Context in user messages (observed 2026-10-24)

- `message.content` may be an array of blocks: `{"type":"image","source":{"type":"base64","media_type":"image/png",
  "data":…}}` followed by `{"type":"text","text":…}`. Claude sees the image (checked with tools disabled).
- `@path` mentions in the text are expanded by the CLI into the file's content, even in `-p` stream-json mode and
  with no tools. Paths with spaces are quoted (`@"fruit list.txt"`); `#L2-3` limits it to those lines.

## Session transcripts (observed 2026-10-24)

`~/.claude/projects/<dir>/<session id>.jsonl` (or under `$CLAUDE_CONFIG_DIR/projects`), where `<dir>` is the working
directory with every character other than a letter or digit replaced by `-`. One JSON object per line:

- `user` / `assistant`: `{type, uuid, parentUuid, sessionId, cwd, timestamp, isSidechain, isMeta?, message}`, with
  `message` shaped like the stream-json events. Each assistant content block is its own line (same `message.id`).
  Tool results are `user` entries whose content has `tool_result` blocks (structured output in `toolUseResult`).
  Pasted images are followed by a text block `[Image: source: …]`.
- `custom-title` `{customTitle}` (written by /rename), `ai-title` `{aiTitle}`, `last-prompt` `{lastPrompt}`: appended
  repeatedly; the last one wins.
- Many others (`attachment`, `api-request*`, `queue-operation`, `file-history-*`, `mode`, …) aren't needed here.
- `--resume <id>` continues the same session id and file.

## Forking (observed 2026-10-24, CLI 2.1.310)

- `--resume <session> --resume-session-at <message uuid> --fork-session` starts a new session (new id in `init`)
  whose history ends at that message; later messages are not visible to Claude. The flag is undocumented in
  `--help` (the Agent SDK's `resumeSessionAt`).
- `assistant` and tool-result `user` events on stdout carry a top-level `uuid`, identical to the transcript entry's.
  The user's own messages aren't echoed, so a message's fork point is the last uuid seen before sending it.

## Models and slash commands (observed 2026-10-24, CLI 2.1.310)

- `{"type":"control_request","request_id":"<id>","request":{"subtype":"initialize"}}` can be the first thing sent.
  The answer (`control_response`, same `request_id`) has `commands` (`name`, `description`, `argumentHint`,
  `aliases?`, `builtin?`), `models` (`value`, `displayName`, `description`, `resolvedModel`, effort support),
  `agents`, output styles, `account` and the CLI version. A process that gets only this request makes no API call
  and writes no transcript (about 5 s), so the plugin reads the catalog once per project that way.
- `system`/`commands_changed` carries the same `commands` list while a session runs. Names starting `__` are
  internal.
- `{"type":"control_request",...,"request":{"subtype":"set_model","model":"haiku"}}` switches the model from the
  next reply; the answer is `{"model":"claude-haiku-5-5"}`, or `subtype: "error"` with `error: "Model '…' not
  found"`. `default` (or no `model`) goes back to the default. `--model default` also works at launch.
- Slash commands are sent as ordinary user messages starting with `/`. Local ones answer with a synthetic
  `assistant` message (`model: "<synthetic>"`, zero usage) and a `result`, e.g. `/context` (a markdown table) and
  `/model sonnet` ("Set model to `Sonnet 5.5` for this session only"); the next `init` reports the new model.
  `/clear` emits `{"type":"conversation_reset"}` and a new `init` with a new `session_id`.
- Quirk: in a session launched with `--model haiku`, after `set_model` to `sonnet`, a typed `/model haiku` answers
  "Set model to Haiku 5.5" but replies stay on Sonnet. Without `--model`, or for any other model, it works. The
  plugin handles `/model <name>` itself with `set_model`.
- `set_model` while idle emits a `user` message `<local-command-stdout>Set model to …</local-command-stdout>`
  (no `result`), then the `control_response`.
- `system`/`init` is sent again with each user message in stream-json mode, carrying the current `model`.

## Instruction files (observed 2026-10-25, CLI 2.1.310)

- `CLAUDE_CODE_DISABLE_CLAUDE_MDS=1` (environment, undocumented) stops the CLI loading any CLAUDE.md: tested with
  one in the working folder and one in its parent, neither was seen. Login, settings and skills are unaffected.
- `--bare` also skips CLAUDE.md but stops reading the OAuth login and keychain, so it's unusable with a
  subscription login.
- With the variable set, text passed with `--append-system-prompt` (or `--append-system-prompt-file`) is the only
  project guidance Claude sees. The plugin appends the configured files (e.g. agent.md, spec.md) this way, after
  its own plan-diagram prompt.

## Inline edits (observed 2026-10-24, CLI 2.1.310)

- `claude -p --output-format json --tools "" --strict-mcp-config --no-session-persistence [--model m]` with the
  prompt on stdin answers once with `{"type":"result","subtype":"success","is_error":false,"result":"…"}`.
- `--tools ""` removes the built-in tools but not MCP tools (Claude tried one, and `-p` denied it:
  `permission_denials`); `--strict-mcp-config` without `--mcp-config` removes those too. With both, Claude has no
  tools and answers in one turn (about 1.3 s on Haiku, 2 s on Opus for a small edit).
- `--no-session-persistence` writes no transcript (the project folder under `~/.claude/projects` gets only an
  empty `memory` folder).
- Asked to rewrite the text between `<selection>` and `</selection>` and reply with one fenced block, both Haiku
  and Opus return exactly the replacement, indented as in the file.

## Tasks, sub-agents and usage (observed 2026-10-24, CLI 2.1.310)

- Task tools aren't in the `-p` default set, and `--tools` replaces the set (`default` can't be combined with
  names). Passing the default list from `init` plus `TaskCreate,TaskGet,TaskList,TaskUpdate` gives exactly the
  defaults plus those. They're deferred: Claude loads them with `ToolSearch` first.
  - `TaskCreate {subject, description, activeForm?}` → `tool_use_result.task.id`.
  - `TaskUpdate {taskId, status: pending|in_progress|completed|deleted, subject?, activeForm?}` →
    `{success, statusChange}`.
- Sub-agents use the `Agent` tool (`{description, subagent_type, prompt}`). `system` events with its `tool_use_id`:
  `task_started`, `task_progress` (`description` = current activity, `usage {total_tokens, tool_uses,
  duration_ms}`, `last_tool_name`), `task_updated {patch {status}}`, `task_notification {status}`. The agent's
  own messages carry `parent_tool_use_id`.
- `assistant` messages carry `usage` (input, cache creation, cache read, output tokens); the `result` event's
  `modelUsage[model].contextWindow` gives the window (`init.model` names the main model).


## Messages sent during a turn (observed 2026-10-26, CLI 2.1.310)

- A `user` message written while a turn runs isn't answered separately: the CLI hands it to Claude with the next tool
  result, inside the same turn (one `result`; no echo of the message). If no further tool result comes before the
  turn ends, it runs as a turn of its own afterwards (its own `init` and `result`).

## Background shell commands (observed 2026-10-26, CLI 2.1.310; fixture `protocol/background-bash.ndjson`)

- `Bash` with `run_in_background: true` returns at once: the result text names the task, and
  `tool_use_result.backgroundTaskId` gives its id.
- `system` events follow the task: `background_tasks_changed {tasks: [{task_id, task_type: "local_bash",
  description}]}` (the full running list; `[]` once none is left), `task_started {task_id, tool_use_id, description,
  task_type, is_backgrounded: true}`, `task_updated {task_id, patch {status: "completed", end_time}}` and
  `task_notification {task_id, tool_use_id, status, output_file}`. The output file ends with `[exited with code N]`.
- These can arrive after the turn's `result`; the CLI then starts a turn on its own (a new `init`, no user message)
  so Claude can react to the finished command. Claude stops a background task with the `TaskStop` tool.
- The client stops one with `control_request {subtype: "stop_task", task_id}` (fixture `protocol/background-stop.ndjson`):
  `task_updated` reports `killed`, `task_notification` reports `stopped`, the list empties, and no follow-up turn
  starts.

## Not signed in (observed 2026-10-27, CLI 2.1.311; fixture `protocol/not-logged-in.ndjson`)

- With no credentials, a user message gets a made-up reply: `assistant` with a top-level `error:
  "authentication_failed"` and `message.model: "<synthetic>"`, text "Not logged in · Please run /login". The
  `result` repeats the text (`subtype: "success"`, `is_error: true`, `terminal_reason: "api_error"`).
- `/login` in stream-json answers "/login isn't available in this environment."
- `claude auth login [--console]` works without a terminal: it opens the browser, prints "If the browser didn't open,
  visit: <url>" (the address as an OSC 8 hyperlink), then "Paste code here if prompted > " and reads a code from
  stdin. It exits when signed in.
- `claude auth status --json` prints `{loggedIn, authMethod, email, ...}`; it exits 1 when signed out.
- The session's transcript is saved even for a failed turn, so the chat can be resumed after signing in.

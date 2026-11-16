"""
A stand-in for `claude -p --input-format stream-json ...`, for ChatPanelFlowTest. It records every line it
receives in received.jsonl next to this script and answers with the CLI's shapes (see docs/PROTOCOL.md):
- initialize: a small catalog of models and commands
- set_model / interrupt: a control_response (an interrupt also ends a held turn)
- a message sent while a WAIT turn is held: no events; the held turn reads it at its next step (a tool call, then
  "done: ... | read: <messages>"), as the CLI does
- each start: its arguments, in args.jsonl
- with --output-format json (inline edits): records the prompt in prompts.txt and answers with reply.txt
- a user message: init, then an assistant reply and a result; a message containing WAIT is held until a file
  named `release` appears next to this script; /clear answers with conversation_reset; "EDIT <path>" changes
  "old" to "new" in that file, reported like Claude's Edit tool (with the file's original text)
- signed out (a file named `signed-out` next to this script): a user message gets the CLI's "Not logged in" answer
- `auth login`: prints the sign-in address as the CLI does, then reads a code; "good-code" signs in
- `auth status --json`: whether it's signed in
- "REPRO": answers with Debug mode's reproduction steps
- "ASKEDIT": asks permission to Edit (a can_use_tool control_request), then answers with the decision it gets
"""
import json, os, sys, threading, time

HERE = os.path.dirname(os.path.abspath(__file__))
lock = threading.Lock()
held = []
notes = []  # messages sent while a held turn runs


def emit(obj):
    with lock:
        sys.stdout.write(json.dumps(obj, separators=(",", ":")) + "\n")  # compact, like the CLI
        sys.stdout.flush()


def record(line):
    with open(os.path.join(HERE, "received.jsonl"), "a") as f:
        f.write(line + "\n")


def reply(text, subtype="success"):
    emit({"type": "assistant", "uuid": "a-" + str(time.time()), "message": {"id": "m-" + str(time.time()), "model": "claude-opus-5-5",
          "content": [{"type": "text", "text": text}]}})
    emit({"type": "result", "subtype": subtype, "is_error": subtype != "success", "duration_ms": 5, "total_cost_usd": 0.0})


def release_when_ready(text):
    release = os.path.join(HERE, "release")
    while not os.path.exists(release):
        if text not in held:
            return  # interrupted
        time.sleep(0.02)
    os.remove(release)
    if text in held:
        held.remove(text)
        if notes:
            # Like the CLI: messages sent meanwhile are read at the turn's next step, after a tool result.
            tool_id = "t-" + str(time.time())
            emit({"type": "assistant", "uuid": "a3-" + str(time.time()), "message": {"id": "m-n" + str(time.time()), "model": "claude-opus-5-5",
                  "content": [{"type": "tool_use", "id": tool_id, "name": "Read", "input": {"file_path": "notes.txt"}}]}})
            emit({"type": "user", "uuid": "u3-" + str(time.time()), "message": {"role": "user", "content": [
                  {"type": "tool_result", "tool_use_id": tool_id, "content": "read"}]}})
            read = " | read: " + "; ".join(notes)
            notes.clear()
            reply("done: " + text + read)
        else:
            reply("done: " + text)


CATALOG = {
    "models": [
        {"value": "default", "displayName": "Default (recommended)", "description": "Opus 5.5", "resolvedModel": "claude-opus-5-5"},
        {"value": "opus", "displayName": "Opus 5.5", "description": "", "resolvedModel": "claude-opus-5-5"},
        {"value": "haiku", "displayName": "Haiku 5.5", "description": "", "resolvedModel": "claude-haiku-5-5"},
    ],
    "commands": [
        {"name": "clear", "description": "Start a new session", "argumentHint": "", "aliases": ["reset", "new"]},
        {"name": "compact", "description": "Summarize the conversation", "argumentHint": "<instructions>"},
        {"name": "model", "description": "Set the model", "argumentHint": "<model>"},
    ],
}

with open(os.path.join(HERE, "args.jsonl"), "a") as f:
    f.write(json.dumps(sys.argv[1:]) + "\n")

SIGNED_OUT = os.path.join(HERE, "signed-out")

if sys.argv[1:3] == ["auth", "login"]:
    url = "https://claude.com/cai/oauth/authorize?code=true&state=fake"
    sys.stdout.write("Opening browser to sign in\u2026\nIf the browser didn't open, visit: \x1b]8;;" + url + "\x07" + url + "\x1b]8;;\x07\nPaste code here if prompted > ")
    sys.stdout.flush()
    if sys.stdin.readline().strip() != "good-code":
        print("\nLogin failed: invalid code")
        sys.exit(1)
    if os.path.exists(SIGNED_OUT):
        os.remove(SIGNED_OUT)
    print("\nLogin successful.")
    sys.exit(0)

if sys.argv[1:3] == ["auth", "status"]:
    signed_in = not os.path.exists(SIGNED_OUT)
    print(json.dumps({"loggedIn": signed_in, "authMethod": "claude.ai" if signed_in else "none", "email": "dev@example.com"} if signed_in else {"loggedIn": False, "authMethod": "none"}))
    sys.exit(0 if signed_in else 1)

# `claude mcp add-json`: records its arguments and the client secret it got in the environment.
if sys.argv[1:3] == ["mcp", "add-json"]:
    with open(os.path.join(HERE, "mcp-add.json"), "w") as f:
        json.dump({"args": sys.argv[3:], "secret": os.environ.get("MCP_CLIENT_SECRET")}, f)
    sys.exit(0)

# One-shot mode (inline edits): the prompt on stdin, one JSON result on stdout. The reply is reply.txt's text.
if "--output-format" in sys.argv and sys.argv[sys.argv.index("--output-format") + 1] == "json":
    prompt = sys.stdin.read()
    with open(os.path.join(HERE, "prompts.txt"), "a") as f:
        f.write(prompt + "\n----\n")
    reply_file = os.path.join(HERE, "reply.txt")
    text = open(reply_file).read() if os.path.exists(reply_file) else ""
    sys.stdout.write(json.dumps({"type": "result", "subtype": "success", "is_error": False, "result": text}, separators=(",", ":")))
    sys.exit(0)

mcp_signed_in = set()
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    record(line)
    msg = json.loads(line)
    if msg.get("type") == "control_request":
        request = msg["request"]
        answer = {}
        if request["subtype"] == "initialize":
            answer = CATALOG
        elif request["subtype"] == "set_model":
            answer = {"model": request.get("model")}
        elif request["subtype"] == "mcp_authenticate":
            # Signing in "finishes" at once: the next mcp_status reports the server connected.
            mcp_signed_in.add(request["serverName"])
            answer = {"authUrl": "https://auth.example.com/authorize?state=1", "requiresUserAction": True, "callbackExpected": True}
        elif request["subtype"] == "mcp_status":
            answer = {"mcpServers": [{"name": "docs", "status": "connected" if "docs" in mcp_signed_in else "needs-auth"}]}
        emit({"type": "control_response", "response": {"subtype": "success", "request_id": msg["request_id"], "response": answer}})
        if request["subtype"] == "interrupt" and held:
            held.clear()
            emit({"type": "result", "subtype": "error_during_execution", "is_error": True, "duration_ms": 5})
        continue
    if msg.get("type") == "control_response" and msg["response"].get("request_id") == "perm-1":
        decision = msg["response"].get("response", {})
        reply("permission: " + decision.get("behavior", "") + " " + decision.get("message", ""))
        continue
    if msg.get("type") != "user":
        continue
    content = msg["message"]["content"]
    text = content if isinstance(content, str) else " ".join(b.get("text", "") for b in content if b.get("type") == "text")
    if held and not text.startswith("/") and "WAIT" not in text:
        # Mid-turn: no events now; the held turn reads it at its next step.
        notes.append(text)
        continue
    emit({"type": "system", "subtype": "init", "session_id": "fake-session", "model": "claude-opus-5-5"})
    if os.path.exists(SIGNED_OUT):
        # Like the CLI: a made-up message with an error, and a result that repeats it.
        emit({"type": "assistant", "error": "authentication_failed", "message": {"id": "m-auth", "model": "<synthetic>",
              "content": [{"type": "text", "text": "Not logged in \u00b7 Please run /login"}]}})
        emit({"type": "result", "subtype": "success", "is_error": True, "result": "Not logged in \u00b7 Please run /login", "duration_ms": 0})
    elif text.startswith("/clear"):
        emit({"type": "conversation_reset"})
        emit({"type": "result", "subtype": "success", "is_error": False, "duration_ms": 1})
    elif text.startswith("EDIT "):
        path = text.split()[1]
        with open(path) as f:
            original = f.read()
        with open(path, "w") as f:
            f.write(original.replace("old", "new"))
        tool_id = "t-" + str(time.time())
        emit({"type": "assistant", "uuid": "a2-" + str(time.time()), "message": {"id": "m-e2" + str(time.time()), "model": "claude-opus-5-5",
              "content": [{"type": "tool_use", "id": tool_id, "name": "Edit",
                           "input": {"file_path": path, "old_string": "old", "new_string": "new"}}]}})
        emit({"type": "user", "uuid": "u-" + str(time.time()), "message": {"role": "user", "content": [
              {"type": "tool_result", "tool_use_id": tool_id, "content": "edited"}]},
              "tool_use_result": {"filePath": path, "originalFile": original}})
        reply("edited " + path)
    elif "REPRO" in text:
        reply("Two ideas; I added logging for both.\n\n<reproduction_steps>\n1. Open Set Clock.\n2. Rotate the phone.\n</reproduction_steps>")
    elif "ASKEDIT" in text:
        emit({"type": "control_request", "request_id": "perm-1", "request": {"subtype": "can_use_tool", "tool_name": "Edit",
              "input": {"file_path": "Main.kt", "old_string": "a", "new_string": "b"}}})
    elif "WAIT" in text:
        held.append(text)
        threading.Thread(target=release_when_ready, args=(text,), daemon=True).start()
    else:
        reply("echo: " + text)

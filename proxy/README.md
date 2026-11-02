# adb-warden proxy

MCP server (stdio) that is the only path from an agent to `adb`. Every call needs a session the phone's
owner approved in the ADB Warden app, is checked against the rules set on the phone, is streamed to the
phone's activity feed, and is written to `~/.local/state/adb-warden/audit.jsonl`.

```sh
npm install && npm run build && npm link     # puts `adb-warden` on PATH
adb-warden link 482913                        # code from the app: Setup → Link a computer
claude mcp add adb-warden -- adb-warden serve
adb-warden status
adb-warden log -f
```

Protocol and rules: [../docs/PROTOCOL.md](../docs/PROTOCOL.md). Tests: `npm test`.

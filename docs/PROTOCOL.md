# ADB Warden — architecture and bridge protocol (v1)

Two parts:

- **proxy/** — Node/TypeScript MCP server (stdio) on the computer. It is the only thing that talks to `adb`.
  Every tool call is checked against the policy, reported to the phone, and written to an audit log.
- **android/** — the phone app (package `com.wadjinny.adbwarden`). It owns the policy, asks for consent,
  shows the in-control indicator, live feed, history and logs, and collects the wireless-debugging pairing code.

The phone is the source of truth for the policy. The proxy fails closed: no bridge connection → no device access.

## Transport

- The app runs a TCP server on `0.0.0.0:47800` (port configurable in the app).
- The app advertises it with mDNS/NSD: service type `_adbwarden._tcp`, instance name `ADB Warden <model>`,
  TXT records `id=<deviceId>`, `v=1`.
- Framing: UTF-8 NDJSON — one JSON object per line, `\n` terminated. Max line 1 MiB. Every object has `"t"` (type).
- Keepalive: the proxy sends `{"t":"ping"}` every 20 s, the app answers `{"t":"pong"}`. Either side closes after
  60 s without receiving anything.

## Handshake

1. App → `{"t":"hello","v":1,"deviceId":"<uuid>","deviceName":"<manufacturer model>","nonce":"<32 hex chars>"}`
2. Proxy → one of:
   - **Auth** (already linked):
     `{"t":"auth","computerId":"<id>","mac":"<hex HMAC-SHA256(key=token bytes as UTF-8 string, msg=nonce string)>","client":"adb-warden-proxy/0.1.0","host":"<computer hostname>"}`
   - **Link** (first time; only accepted while the user has "Link a computer" open in the app, for 3 minutes):
     `{"t":"link","code":"<6 digits>","host":"<computer hostname>"}`
3. App →
   - after a good auth: `{"t":"ready","policy":{…},"device":{"manufacturer":"samsung","model":"SM-A305F","androidVersion":"11","sdk":30,"ips":["192.168.0.12"]}}`
   - after a good link: `{"t":"linked","computerId":"<uuid>","token":"<base64url, 32 random bytes>","deviceId":"<uuid>","deviceName":"…"}` then the app closes the socket.
     The proxy saves it and reconnects with `auth`.
   - on failure: `{"t":"error","code":"bad_auth"|"bad_link_code"|"not_linking"|"bad_request","message":"…"}` then close.
     Five wrong link codes end link mode.

Each linked computer has its own `computerId` + token. Removing a computer in the app invalidates its token.

## Requests (proxy → app, answered with `reply` carrying the same `id`)

`id` is an integer chosen by the proxy, unique per connection.

### session_request
```json
{"t":"session_request","id":1,"agent":"claude-code 2.1.0","reason":"Check the battery settings"}
```
The app shows the consent prompt. Reply:
```json
{"t":"reply","id":1,"approved":true,"sessionId":"<uuid>"}
{"t":"reply","id":1,"approved":false,"reason":"denied"|"busy"|"timeout"|"cooldown"}
```
- `busy`: another session (from any computer) is active.
- `timeout`: no answer within 60 s.
- `cooldown`: the user denied or stopped a session from this computer less than 20 s ago (anti-spam).

### pairing_request
Sent when the proxy cannot reach the phone over adb and needs a wireless-debugging pairing code.
```json
{"t":"pairing_request","id":2,"reason":"Phone is not paired with this computer"}
```
The app shows a heads-up notification with an inline text field (RemoteInput) so the user can type the code
while the system "Pair device with pairing code" dialog stays open, plus an in-app screen with code + optional port.
Reply:
```json
{"t":"reply","id":2,"code":"123456","port":37155}
{"t":"reply","id":2,"cancelled":true}
```
`port` is optional (null when the user only typed the code; the proxy then finds it through `adb mdns services`).
The app gives up after 180 s and replies `cancelled`.

## Events (no reply)

Proxy → app:
```json
{"t":"action","sessionId":"…","seq":1,"ts":1730566800000,"tool":"tap","summary":"Tap (540, 1200)","status":"ok"|"denied"|"error","detail":null,"durationMs":84}
{"t":"session_end","sessionId":"…","reason":"agent_ended"|"idle_timeout"|"proxy_exit","summary":"optional text from the agent"}
{"t":"pairing_result","id":2,"ok":true,"message":"Paired and connected to 192.168.0.12:41235"}
{"t":"log","level":"info"|"warn"|"error","message":"adb connected to 192.168.0.12:41235"}
{"t":"ping"}
```
App → proxy:
```json
{"t":"policy","policy":{…}}          // the user changed the rules
{"t":"stop","sessionId":"…"}         // the user pressed Stop
{"t":"pong"}
```
After `stop`, the proxy refuses every call for that session. If the TCP connection drops, the app ends the
active session from that computer (`proxy_disconnected`) and the proxy must request a new session.

## Policy

```json
{
  "v": 1,
  "screen": true,
  "input": true,
  "apps": true,
  "install": false,
  "shell": "readonly",
  "appFilter": "blocklist",
  "packages": [],
  "idleTimeoutSec": 300,
  "adbLock": false,
  "adbLockUsb": false
}
```

| Field | Meaning |
|---|---|
| `screen` | `screenshot`, `ui_tree`, `current_app` |
| `input` | `tap`, `long_press`, `swipe`, `type_text`, `press_key` |
| `apps` | `launch_app`, `list_apps` |
| `install` | `install_app` |
| `shell` | `off` · `readonly` (allowlisted read-only commands, see below) · `any` (full shell — warn the user this can bypass every other rule) |
| `appFilter` | `off` · `blocklist` (packages listed are off limits) · `allowlist` (only listed packages, plus the home launcher and `com.android.systemui`) |
| `packages` | package names for the filter |
| `idleTimeoutSec` | the proxy ends the session after this many seconds without a tool call (60–3600) |
| `adbLock` | the app keeps wireless debugging off outside sessions, turns it on when a session is approved (or a pairing code is requested) and off when it ends. The proxy then waits up to 30 s for the phone to reappear over adb before asking for pairing. Needs `adb shell pm grant com.wadjinny.adbwarden android.permission.WRITE_SECURE_SETTINGS` once. |
| `adbLockUsb` | with `adbLock`, USB debugging is switched the same way |

App filter enforcement (proxy side): `launch_app` checks the target package; `screen` and `input` tools check the
**foreground package** first and are denied if it is filtered.

Always on, not configurable (self-protection): screen and input tools are denied while
`com.wadjinny.adbwarden` is in the foreground, `launch_app` refuses it, and shell commands that mention it are denied.
This stops an agent from approving its own requests or editing its own rules.

Read-only shell (`readonly`): no `; & > < $ \` ( )` or newlines; `|` is allowed and every pipe segment must start with an
allowlisted command: `ls cat head tail wc grep find(no -exec/-delete) stat du df ps top(-n required) uptime date id whoami
uname getprop echo dumpsys "pm list" "pm path" "settings get" "wm size" "wm density" "logcat -d" "ip addr" "ip route" "cmd battery get"?`
(see `proxy/src/shell-policy.ts` for the exact list). Every shell command has a 30 s timeout and 200 KB output cap.

## MCP tools (proxy)

| Tool | Category | Notes |
|---|---|---|
| `start_session {reason}` | — | Asks the phone for consent. Other tools call it implicitly (reason "(no reason given)"). |
| `end_session {summary?}` | — | Ends the session; the phone shows the "done" notice. |
| `status` | — | Bridge, adb device, session, and the current rules. Needs no session. |
| `screenshot` | screen | Image (JPEG after downscale); `settleMs?` waits that long before capturing (a shot taken right after a tap can catch a mid-transition frame) |
| `ui_tree` | screen | Compact list of on-screen elements with tap centers; `settleMs?` waits before the dump |
| `current_app` | screen | Foreground package/activity |
| `tap {x,y}` / `long_press {x,y,durationMs?}` / `swipe {x1,y1,x2,y2,durationMs?}` | input | swipes must start and end ~60 px from the screen edges (system edge gestures) |
| `type_text {text}` | input | ASCII via `input text` |
| `press_key {key}` | input | back, home, recents, enter, delete, tab, escape, space, menu, search, volume_up, volume_down, dpad_* |
| `launch_app {package}` / `list_apps {includeSystem?}` | apps | |
| `install_app {apkPath}` | install | Paths invisible to the server (sandboxed daemon, MCP server on another host) fail with staging guidance |
| `shell {command}` | shell | 30 s timeout, 200 KB output |

APK staging: what the agent's filesystem may not be readable by the server — the secure-mode daemon cannot see
`/home` or `/tmp` (systemd sandbox), and the MCP server can run on a different host than the agent's files. A
world-writable staging directory `/dev/shm/adb-warden` is created where adb-warden actually runs; when
`install` is on, `status` names it (CLI and MCP) and `install_app` errors include the copy-and-install recipe.

## Proxy files

- Config: `~/.config/adb-warden/config.json` — `{computerId, token, deviceId, deviceName, host, port}`
- Audit log: `~/.local/state/adb-warden/audit.jsonl` — one line per session start/end and per action.

## Device selection

The proxy uses the adb device whose Wi-Fi IP matches one of the app's `device.ips` (serial `ip:port`, an mDNS
serial resolved by `adb mdns services`, or `ip -4 addr` on a USB device). If none: `adb mdns services` →
`adb connect` the `_adb-tls-connect._tcp` entry with the phone's IP. If that fails: `pairing_request` →
`adb pair ip:port code` (port from the reply or from `_adb-tls-pairing._tcp`) → connect.

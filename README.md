# ADB Warden

An MCP server that gives AI agents controlled access to an Android phone over ADB, plus a phone app that asks
for consent, shows when the agent is in control, keeps a history and logs, and collects the wireless-debugging
pairing code.

- `proxy/` — the MCP server (Node/TypeScript). The only thing that talks to `adb`; checks every call against the rules set on the phone.
- `android/` — the ADB Warden app (Kotlin, Jetpack Compose, Android 11+).
- `docs/PROTOCOL.md` — architecture and the phone ↔ proxy protocol. `docs/FEATURES.md` — MVP and later features.

## Quick start

1. Build and install the app:
   ```sh
   cd android && ./gradlew --offline assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   Open it and grant notifications and "Display over other apps" from the Setup tab.
2. Build the proxy:
   ```sh
   cd proxy && npm install && npm run build && npm link
   ```
3. Link once: in the app tap **Link a computer**, then run `adb-warden link <code>` (add `<ip>:47800` if the phone isn't found).
4. Add it to your MCP client, e.g. `claude mcp add adb-warden -- adb-warden serve`.

`adb-warden status` checks the connection; `adb-warden log -f` follows the audit log.

## Secure mode (optional, Linux)

`adb-warden secure install` asks for your password in the desktop's own polkit dialog (the tool never sees it)
and installs a systemd daemon running as the `adbwarden` system user. It holds its own adb key and runs a
private adb server on a socket only it can open. `adb-warden serve` then pipes MCP to the daemon.

After installing: on the phone, forget this computer's old wireless-debugging pairing, then run
`adb-warden secure pair`. From then on plain `adb` on this computer is refused by the phone, and the only way
in is through the consent prompt. `adb-warden secure status` / `secure uninstall` manage it; the audit log
moves to `/var/log/adb-warden/audit.jsonl` (readable, not writable, by you).

Limits: the install moment is as trustworthy as your account (install while no agent runs); agents must not
have passwordless sudo; updates need the password again.

## Security notes

- Rules are enforced by the proxy. If the agent can also run `adb` directly on the computer, it can bypass them.
- Shell mode "any" can bypass every other rule.
- The bridge is authenticated (HMAC challenge) but not encrypted; use it on a trusted network.

# ADB Warden

An MCP server that gives AI agents controlled access to an Android phone over ADB, plus a phone app that asks
for consent, shows when the agent is in control, keeps a history and logs, and collects the wireless-debugging
pairing code.

- `proxy/` — the MCP server (Node/TypeScript). The only thing that talks to `adb`; checks every call against the rules set on the phone.
- `android/` — the ADB Warden app (Kotlin, Jetpack Compose, Android 11+).
- `docs/PROTOCOL.md` — architecture and the phone ↔ proxy protocol. `docs/FEATURES.md` — MVP and later features.

## Install

No clone or build needed: every [release](https://github.com/Wadjinny/adb-warden/releases) has the signed phone app
and the MCP server package.

| File | What it is | Latest download |
|---|---|---|
| `adb-warden-<version>.apk` | Android app | [adb-warden.apk](https://github.com/Wadjinny/adb-warden/releases/latest/download/adb-warden.apk) |
| `adb-warden-<version>.tgz` | MCP server (npm package) | [adb-warden.tgz](https://github.com/Wadjinny/adb-warden/releases/latest/download/adb-warden.tgz) |

### What you need

- **Phone:** Android 11 or newer, on the same Wi-Fi as the computer.
- **Computer:** [Node.js](https://nodejs.org) 20 or newer, `adb` from
  [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) on your `PATH`, and an MCP
  client such as [Claude Code](https://claude.com/claude-code).

### 1. Install the app on the phone

1. Turn on **Developer options**: Settings → About phone → tap **Build number** 7 times.
2. Turn on **Wireless debugging**: Settings → Developer options → Wireless debugging.
3. On the phone, download **[adb-warden.apk](https://github.com/Wadjinny/adb-warden/releases/latest/download/adb-warden.apk)**
   and open it. Allow installing from your browser if Android asks.
   Or from the computer, with the phone on USB: `adb install adb-warden.apk`.
4. Open **ADB Warden** and, in the **Setup** tab, grant **notifications** and **Display over other apps**.

### 2. Install the MCP server on the computer

The MCP server is an npm package attached to each release; it is not on the npm registry. Install it with npm
straight from the release URL:

```sh
npm install -g https://github.com/Wadjinny/adb-warden/releases/latest/download/adb-warden.tgz
adb-warden --version
```

This puts the `adb-warden` command on your `PATH`. To update later, run the same command again. To pin a version,
use its versioned file instead, e.g.
`https://github.com/Wadjinny/adb-warden/releases/download/v0.2.0/adb-warden-0.2.0.tgz`.

### 3. Link the computer to the phone (once)

In the app, tap **Setup → Link a computer** to get a 6-digit code, then run:

```sh
adb-warden link <code>
# if the phone isn't found on the network:
adb-warden link <code> <phone-ip>:47800
```

### 4. Add it to Claude Code

```sh
claude mcp add adb-warden -- adb-warden serve
```

The first time the agent uses the phone, ADB Warden asks you to approve the session on the phone. If the computer
is not yet paired for wireless debugging, it also asks for a pairing code: on the phone open **Wireless debugging →
Pair device with pairing code** and type the code into the ADB Warden notification.

Check everything with `adb-warden status`; follow the audit log with `adb-warden log -f`.

#### Without a global install (npx)

You can skip step 2 and run the package with `npx` instead. Use a **versioned** URL here: npx caches what it
downloads, so the "latest" link would stay on the first version it fetched.

```sh
PKG=https://github.com/Wadjinny/adb-warden/releases/download/v<version>/adb-warden-<version>.tgz
npx -y --package=$PKG adb-warden link <code>
claude mcp add adb-warden -- npx -y --package=$PKG adb-warden serve
```

### Updating the app

Install the new APK over the old one. If you installed a build you made yourself (signed with your own debug
key), uninstall it first: Android only updates an app signed with the same key. Uninstalling clears the app's
rules and links, so link the computer again afterwards.

## Releasing

Push a version tag; CI builds, signs and attaches both files to a GitHub release:

```sh
git tag v0.2.0 && git push origin v0.2.0
```

The version comes from the tag. Signing uses the repository secrets `ADBWARDEN_KEYSTORE_BASE64`,
`ADBWARDEN_KEYSTORE_PASSWORD`, `ADBWARDEN_KEY_ALIAS` and `ADBWARDEN_KEY_PASSWORD`. Local release builds use
`~/.android-keys/adb-warden-release.properties` if it exists, otherwise the debug key.

## Build from source

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

### Installing APKs the server cannot see

The daemon cannot read your home directory or `/tmp` (systemd sandbox), and the MCP server may even run on a
different machine than the files an agent is working with. When `install_app` gets such a path, the error
explains visibility instead of just "No such file": it names `/dev/shm/adb-warden` (created by adb-warden where
it runs, shared memory that the sandbox keeps accessible) and gives the copy-and-install recipe. `status` shows
the same staging directory while the Install category is on, so agents can stage the APK before the first
failure.

## Security notes

- Rules are enforced by the proxy. If the agent can also run `adb` directly on the computer, it can bypass them.
- Shell mode "any" can bypass every other rule.
- The bridge is authenticated (HMAC challenge) but not encrypted; use it on a trusted network.

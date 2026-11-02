# ADB Warden — features

## MVP

### Proxy (computer)
1. MCP tools: screenshot, UI tree, current app, tap / long press / swipe / type / key, launch app, list apps, install app, shell.
2. Policy check on every call (tool category, app filter on the foreground/target app, shell mode). Denials return a clear message.
3. Sessions: the first call asks the phone for consent; nothing runs before approval. Ends on `end_session`, idle timeout, user Stop, or proxy exit.
4. Live event stream of every action to the phone.
5. adb connection handling: matches the phone by IP, reconnects through mDNS when the port changes.
6. Pairing: asks the phone for the wireless-debugging code instead of the agent.
7. Audit log on the computer (`audit.jsonl`) + `adb-warden log` command.
8. `adb-warden link <code>` one-time linking (mDNS discovery of the phone).

### Phone app
1. Consent prompt (full-screen dialog + heads-up notification): agent, computer, reason, what the rules allow — Allow / Deny, 60 s timeout.
2. In-control indicator: colored screen border + floating pill with the last action; ongoing notification as fallback.
3. Stop button on the pill and in the notification.
4. Live activity feed on the Home tab.
5. "Done" notification with a summary (actions, duration, denied count).
6. Pairing code entry: inline notification reply (works over the system pairing dialog) and an in-app screen.
7. Rules tab: tool categories, shell mode, app allowlist/blocklist with an installed-app picker, idle timeout.
8. **History**: every session (including denied and timed-out requests) with agent, computer, reason, start/end, end reason, action count, denied count; tap for the full action list. Export as JSON, clear, auto-prune after 30 days.
9. **Logs**: connection, auth, linking, pairing and service events with level; export as JSON, clear, keep the last 2000.
10. Setup tab: permission checklist (notifications, display over other apps, battery optimisation), link a computer (6-digit code), linked computers with remove, server port.
11. **ADB lock** (Rules tab): wireless (optionally USB) debugging is on only during approved sessions; turning it on from outside is reverted with a notification; "Pause for 1 hour" for your own adb work.

## Later
- Ask-before for specific risky actions; allow-once / always-allow per agent; time and action limits; rule profiles.
- Auto-pause on lock screen, password fields, or a blocked app appearing.
- Agent goal banner; thumbnails in the feed; undo hints.
- QR pairing; remote access (Tailscale).
- Multiple phones per proxy; take over / hand back; answer agent questions from the phone; widget and quick-settings tile.
- TLS on the bridge; hide the overlay during screenshots.

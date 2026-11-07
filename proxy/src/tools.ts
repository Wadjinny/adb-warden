import fs from "node:fs";
import { shrinkScreenshot } from "./image.js";

const ADB_LOCK_WAIT_MS = 30_000;
const SESSION_REMINDER = "(The phone shows \"Agent in control\" until you call end_session. Call it as soon as you are done. Use only adb-warden tools for the phone; never run adb directly.)";
import path from "node:path";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import { adb, adbRaw, DeviceManager, shellArgs } from "./adb.js";
import { BridgeConnection, BridgeManager } from "./bridge.js";
import { isSecureDaemon, logErr, SELF_PACKAGE, VERSION } from "./config.js";
import { checkCategory, checkPackage, denial, PACKAGE_RE, Policy } from "./policy.js";
import { ActiveSession, SessionDenied, SessionManager } from "./session.js";
import { checkReadonlyShell, mentionsSelf } from "./shell-policy.js";
import { escapeInputText, formatUiNode, KEYCODES, parseUiNodes } from "./ui.js";

const text = (s: string): CallToolResult => ({ content: [{ type: "text", text: s }] });
const fail = (s: string): CallToolResult => ({ content: [{ type: "text", text: s }], isError: true });

function resultText(r: CallToolResult): string {
  const t = r.content.find((c) => c.type === "text");
  return t && t.type === "text" ? t.text : "";
}

function clip(s: string, n = 60): string {
  const one = s.replace(/\s+/g, " ");
  return one.length > n ? one.slice(0, n - 1) + "…" : one;
}

const wait = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

const SHM = "/dev/shm";
/** Where files can be staged so adb-warden can read them (see stageDir). */
let stageDirCache: string | null | undefined;

/**
 * Staging directory for files agents want adb-warden to see. The secure-mode daemon sandbox hides /home and
 * /tmp (ProtectHome, PrivateTmp), but the shared-memory tmpfs stays visible, so a world-writable staging
 * directory is created in it. Returns null when nothing shared is available.
 */
export function stageDir(): string | null {
  if (stageDirCache !== undefined) return stageDirCache;
  let result: string | null = null;
  for (const candidate of [path.join(SHM, "adb-warden"), SHM]) {
    try {
      fs.mkdirSync(candidate, { recursive: true });
      try { fs.chmodSync(candidate, 0o1777); } catch { /* owned by another user */ }
      fs.accessSync(candidate, fs.constants.W_OK | fs.constants.R_OK);
      result = candidate;
      break;
    } catch {
      // not usable here (missing or read-only /dev/shm, ...) — try the next candidate
    }
  }
  stageDirCache = result;
  return result;
}

/** Error for a path invisible to this process: the sandboxed daemon, or the MCP server on another host. */
export function missingApkMessage(
  abs: string,
  opts: { secureDaemon: boolean; stage: string | null } = { secureDaemon: isSecureDaemon(), stage: stageDir() },
): string {
  const name = path.basename(abs);
  const cause = opts.secureDaemon
    ? "this ADB Warden runs as its own daemon user in a hardened sandbox: paths under /home, /tmp and /var/tmp are invisible to it, even though they exist on your side"
    : "the ADB Warden server may run on a different machine or container that cannot see your filesystem — or the path itself may be wrong";
  const recipe = opts.stage
    ? `Copy the APK where ADB Warden can read it, then call install_app on the copy:\n  cp ${abs} ${opts.stage}/\n  install_app ${opts.stage}/${name}`
    : "Copy the APK to a location ADB Warden can read (no shared staging directory is writable here), then call install_app on the copy.";
  return `No such file: ${abs}\nUsually the file exists but is invisible to ADB Warden: ${cause}.\n${recipe}`;
}

export function describePolicy(p: Policy): string {
  const on = (b: boolean) => (b ? "allowed" : "off");
  const filter =
    p.appFilter === "off"
      ? "no app filter"
      : p.appFilter === "blocklist"
        ? `blocklist: ${p.packages.length ? p.packages.join(", ") : "(empty)"}`
        : `allowlist (plus home screen): ${p.packages.length ? p.packages.join(", ") : "(empty)"}`;
  return [
    `screen: ${on(p.screen)}`,
    `input: ${on(p.input)}`,
    `apps: ${on(p.apps)}`,
    `install: ${on(p.install)}`,
    `shell: ${p.shell}`,
    `apps filter: ${filter}`,
    `idle timeout: ${p.idleTimeoutSec}s`,
    `adb lock: ${p.adbLock ? "on (wireless debugging is on only during sessions)" : "off"}`,
  ].join("; ");
}

export interface Context {
  bridge: BridgeManager;
  devices: DeviceManager;
  sessions: SessionManager;
}

interface GuardOpts {
  /** Check the foreground app against the app filter (screen and input tools). */
  foreground?: boolean;
  /** Allow the call even when ADB Warden itself is in the foreground (press_key home). */
  allowOverSelf?: boolean;
  /** Target package to check against the app filter (launch_app). */
  target?: string;
  /** Extra policy check that may deny the call. */
  precheck?: (policy: Policy) => string | null;
}

export interface Shared {
  bridge: BridgeManager;
  devices: DeviceManager;
  /** Extra listeners for device/pairing progress (e.g. `secure pair` output). */
  logListeners: Set<(level: string, message: string) => void>;
}

/** Context for one MCP client. In daemon mode the bridge and devices are shared by all clients. */
export function createContext(agentName: () => string, shared: Shared = createShared()): Context {
  const sessions = new SessionManager(shared.bridge, agentName);
  return { ...shared, sessions };
}

export function createShared(): Shared {
  const bridge = new BridgeManager();
  const logListeners = new Set<(level: string, message: string) => void>();
  const emit = (level: string, message: string) => logListeners.forEach((l) => l(level, message));
  const devices = new DeviceManager({
    requestPairing: async (reason) => {
      const conn = await bridge.ensure();
      emit("info", "pairing requested — on the phone open Wireless debugging → Pair device with pairing code, then type the code into the ADB Warden notification");
      let id = 0;
      const reply = await conn.request("pairing_request", { reason }, 200_000, (i) => (id = i));
      if (reply.cancelled === true || typeof reply.code !== "string") return null;
      const port = reply.port === null || reply.port === undefined ? null : Number(reply.port) || null;
      return {
        code: reply.code,
        port,
        reply: (ok: boolean, message: string) => conn.send({ t: "pairing_result", id, ok, message }),
      };
    },
    log: (level, message) => {
      logErr(message);
      emit(level, message);
      bridge.current?.send({ t: "log", level, message });
    },
  });
  bridge.on("disconnected", () => devices.forget());
  bridge.setMaxListeners(200);
  return { bridge, devices, logListeners };
}

export function registerTools(server: McpServer, ctx: Context): void {
  const { bridge, devices, sessions } = ctx;

  /** With the ADB lock on, the phone turns wireless debugging on only after approval; give it time to come up. */
  const adbWaitMs = () => (bridge.policy.adbLock ? ADB_LOCK_WAIT_MS : 0);

  async function guarded(
    tool: string,
    summary: string,
    opts: GuardOpts,
    fn: (serial: string, conn: BridgeConnection) => Promise<CallToolResult>,
  ): Promise<CallToolResult> {
    const t0 = Date.now();
    const hint = sessions.stoppedHint();
    if (hint && !sessions.current) {
      sessions.record(tool, summary, "denied", hint, 0);
      return fail(hint);
    }
    let session;
    try {
      session = await sessions.ensure();
    } catch (e) {
      const msg = e instanceof SessionDenied ? e.message : `Cannot start a session: ${(e as Error).message}`;
      return fail(msg);
    }
    sessions.touch();
    const deny = (why: string) => {
      sessions.record(tool, summary, "denied", why, Date.now() - t0);
      return fail(why);
    };
    const policy = bridge.policy;
    const catDeny = checkCategory(policy, tool) ?? opts.precheck?.(policy) ?? null;
    if (catDeny) return deny(catDeny);
    try {
      const serial = await devices.ensure(session.conn.device?.ips ?? [], true, adbWaitMs());
      if (opts.target !== undefined || opts.foreground) {
        const home = await devices.homePackage(serial);
        if (opts.target !== undefined) {
          const d = checkPackage(policy, opts.target, { role: "target", homePackage: home });
          if (d) return deny(d);
        }
        if (opts.foreground) {
          const fg = await devices.foreground(serial);
          if (!(opts.allowOverSelf && fg?.pkg === SELF_PACKAGE)) {
            const d = checkPackage(policy, fg?.pkg ?? null, { role: "foreground", homePackage: home });
            if (d) return deny(d);
          }
        }
      }
      if (!sessions.current || sessions.current.id !== session.id) return fail("The session ended (stopped on the phone).");
      await recordStartApp(session, serial);
      const r = await fn(serial, session.conn);
      sessions.record(tool, summary, r.isError ? "error" : "ok", r.isError ? clip(resultText(r), 300) : null, Date.now() - t0);
      if (!r.isError) r.content = [...r.content, { type: "text", text: SESSION_REMINDER }];
      return r;
    } catch (e) {
      const msg = (e as Error).message;
      sessions.record(tool, summary, "error", clip(msg, 300), Date.now() - t0);
      return fail(msg);
    }
  }

  /** Remember the app on screen when the session started, so the agent can put the phone back. */
  async function recordStartApp(s: ActiveSession, serial: string): Promise<void> {
    if (s.startApp !== undefined) return;
    let fg = await devices.foreground(serial).catch(() => null);
    // Right after approval the consent screen may still be closing; wait for the app underneath.
    for (let i = 0; i < 4 && fg?.pkg === SELF_PACKAGE; i++) {
      await wait(500);
      fg = await devices.foreground(serial).catch(() => null);
    }
    if (s.startApp !== undefined) return;
    s.startApp = fg && fg.pkg !== SELF_PACKAGE ? fg : null;
    if (fg && fg.pkg !== SELF_PACKAGE) s.startHome = fg.pkg === (await devices.homePackage(serial).catch(() => null));
  }

  /** How to get back to the start app, e.g. "launch_app com.foo" or "press_key home". */
  function returnStep(s: ActiveSession): string | null {
    if (!s.startApp) return null;
    return s.startHome ? "the home screen (press_key home)" : `${s.startApp.pkg} (launch_app ${s.startApp.pkg})`;
  }

  const inputShell = async (serial: string, cmd: string): Promise<CallToolResult> => {
    const r = await adbRaw(shellArgs(serial, cmd), { timeoutMs: 20_000 });
    const out = (r.stdout.toString("utf8") + r.stderr).trim();
    if (r.timedOut) return fail(`timed out: ${cmd}`);
    if (r.code !== 0 || /Exception|Error:/i.test(out)) return fail(out || `exit ${r.code}`);
    return text("done");
  };

  const coord = z.number().int().min(0).max(20000);

  // ---- session tools ----

  server.registerTool(
    "start_session",
    {
      description:
        "The only permitted way to use the user's Android phone (never run adb directly). Ask the phone's owner for permission to control the phone. A consent prompt appears on the phone; nothing runs until they accept. Other tools call this automatically, but calling it first lets you explain why. Returns the rules the owner has set.",
      inputSchema: { reason: z.string().min(1).max(500).describe("Short reason shown to the user on the phone") },
    },
    async ({ reason }) => {
      const hint = sessions.stoppedHint();
      if (hint && !sessions.current) return fail(hint);
      try {
        const s = await sessions.ensure(reason);
        let adbNote = "";
        try {
          const serial = await devices.ensure(s.conn.device?.ips ?? [], true, adbWaitMs());
          adbNote = `adb device: ${serial}`;
          await recordStartApp(s, serial);
        } catch (e) {
          adbNote = `adb not ready yet: ${(e as Error).message}`;
        }
        const back = returnStep(s);
        const restore = back
          ? `When the session started the phone was on ${back}. Leave it as you found it: before end_session, back out of every app you open (press_key back) and return to ${back}.`
          : "Leave the phone as you found it: before end_session, back out of every app you open (press_key back).";
        return text(
          `Session ${s.id} is active on ${s.conn.hello?.deviceName || "the phone"}.\nRules: ${describePolicy(bridge.policy)}\n${adbNote}\n${restore}\nCall end_session when you are done.`,
        );
      } catch (e) {
        return fail(e instanceof SessionDenied ? e.message : `Cannot start a session: ${(e as Error).message}`);
      }
    },
  );

  server.registerTool(
    "end_session",
    {
      description:
        "End the control session. The phone shows that you are done. Always call this when you finish, after closing the apps you opened and returning to the app that was on screen at the start. If the phone is not back where it started, the first call is refused with instructions; a second call ends the session anyway.",
      inputSchema: { summary: z.string().max(1000).optional().describe("One or two sentences on what you did, shown to the user") },
    },
    async ({ summary }) => {
      const cur = sessions.current;
      if (cur && cur.startApp && !cur.restoreWarned) {
        const fg = await devices
          .ensure(cur.conn.device?.ips ?? [], false)
          .then((serial) => devices.foreground(serial))
          .catch(() => null);
        if (fg && fg.pkg !== cur.startApp.pkg && fg.pkg !== SELF_PACKAGE) {
          cur.restoreWarned = true;
          const opened = cur.opened.length ? ` You opened: ${[...new Set(cur.opened)].join(", ")}.` : "";
          return fail(
            `Not ended yet: the phone is on ${fg.pkg}, but it was on ${returnStep(cur)} when the session started.${opened} ` +
              `Back out of the apps you opened (press_key back until you have left them), return to ${returnStep(cur)}, then call end_session again. ` +
              "(Calling end_session again ends the session either way.)",
          );
        }
      }
      const s = sessions.finish("agent_ended", summary ?? null, true);
      if (!s) return text("No session was active.");
      return text(`Session ended after ${s.actions} action(s).`);
    },
  );

  server.registerTool(
    "status",
    { description: "Show the connection to the phone app, the adb device, the session, and the current rules. Does not need a session.", inputSchema: {} },
    async () => {
      const lines: string[] = [`adb-warden proxy ${VERSION}`];
      try {
        const conn = await bridge.ensure();
        lines.push(`phone app: connected to ${conn.hello?.deviceName || "phone"} at ${conn.host}:${conn.port}`);
        lines.push(`phone IPs: ${conn.device?.ips.join(", ") || "none"}`);
        lines.push(`rules: ${describePolicy(conn.policy)}`);
        if (conn.policy.install) {
          const dir = stageDir();
          lines.push(
            dir
              ? `APK staging: when your APK path is invisible here (daemon sandbox or another host), copy it to ${dir}/ and install the copy`
              : "APK staging: no shared staging directory is writable here (/dev/shm unavailable)",
          );
        }
        try {
          lines.push(`adb device: ${await devices.ensure(conn.device?.ips ?? [], false)}`);
        } catch (e) {
          lines.push(`adb device: not connected (${(e as Error).message}) — the next tool call will try to connect or pair`);
        }
      } catch (e) {
        lines.push(`phone app: not connected — ${(e as Error).message}`);
      }
      const s = sessions.current;
      lines.push(s ? `session: active (${s.id}, ${s.actions} actions, since ${new Date(s.startedAt).toISOString()})` : "session: none");
      return text(lines.join("\n"));
    },
  );

  // ---- screen ----

  server.registerTool(
    "screenshot",
    {
      description:
        "Take a screenshot of the phone (JPEG, long side up to 1568 px; the reply says how to map image coordinates to screen coordinates).",
      inputSchema: {
        settleMs: z
          .number()
          .int()
          .min(0)
          .max(10_000)
          .optional()
          .describe("Wait this long before capturing. Use it right after a tap — an immediate screenshot often catches a mid-transition frame"),
      },
    },
    async ({ settleMs }) =>
      guarded("screenshot", settleMs ? `Screenshot after ${settleMs} ms` : "Screenshot", { foreground: true }, async (serial) => {
        if (settleMs) await wait(settleMs);
        const r = await adbRaw(["-s", serial, "exec-out", "screencap", "-p"], { timeoutMs: 20_000, maxBytes: 30 * 1024 * 1024 });
        if (r.timedOut) return fail("screencap timed out");
        if (r.truncated) return fail("screenshot too large");
        const png = r.stdout;
        if (png.length < 8 || png.readUInt32BE(0) !== 0x89504e47) return fail(`screencap failed: ${r.stderr.trim() || "no PNG data"}`);
        const img = shrinkScreenshot(png);
        const note =
          img.factor === 1
            ? `Screen ${img.srcWidth}x${img.srcHeight}. Image coordinates are screen coordinates.`
            : `Screen ${img.srcWidth}x${img.srcHeight}; image scaled to ${img.width}x${img.height}. Multiply image coordinates by ${img.factor.toFixed(3)} before tap/swipe (or use ui_tree centers, which are already in screen coordinates).`;
        return {
          content: [
            { type: "image", data: img.data.toString("base64"), mimeType: img.mimeType },
            { type: "text", text: note },
          ],
        };
      }),
  );

  server.registerTool(
    "ui_tree",
    {
      description:
        "List the elements on screen (text, resource id, description, tap center, flags). Cheaper than a screenshot; use the center coordinates with tap. raw=true returns the unfiltered uiautomator XML (for debugging what an app exposes).",
      inputSchema: {
        raw: z.boolean().optional().describe("Return the raw uiautomator XML instead of the compact list"),
        settleMs: z
          .number()
          .int()
          .min(0)
          .max(10_000)
          .optional()
          .describe("Wait this long before dumping. A dump taken during an animation can fail or miss elements"),
      },
    },
    async ({ raw, settleMs }) =>
      guarded(
        "ui_tree",
        `${raw ? "Read raw screen XML" : "Read screen elements"}${settleMs ? ` after ${settleMs} ms` : ""}`,
        { foreground: true },
        async (serial) => {
          if (settleMs) await wait(settleMs);
          const out = await adb(
            shellArgs(serial, "uiautomator dump /sdcard/adbw_ui.xml >/dev/null 2>&1; cat /sdcard/adbw_ui.xml; rm -f /sdcard/adbw_ui.xml"),
            { timeoutMs: 30_000, maxBytes: 5 * 1024 * 1024, allowFail: true },
          );
          if (!out.includes("<hierarchy")) return fail(`uiautomator dump failed${out.trim() ? `: ${clip(out.trim(), 200)}` : " (the screen may be animating; try again)"}`);
          if (raw) {
            const xml = out.slice(out.indexOf("<?xml") >= 0 ? out.indexOf("<?xml") : out.indexOf("<hierarchy"));
            return text(xml.length > 200_000 ? xml.slice(0, 200_000) + "\n(truncated at 200 KB)" : xml);
          }
          const nodes = parseUiNodes(out).filter((n) => n.pkg !== SELF_PACKAGE);
          const fg = await devices.foreground(serial);
          const header = `foreground: ${fg ? `${fg.pkg}/${fg.activity}` : "unknown"}`;
          return text(nodes.length ? `${header}\n${nodes.map((n, i) => formatUiNode({ ...n, index: i + 1 })).join("\n")}` : `${header}\n(no interesting elements on screen)`);
        },
      ),
  );

  server.registerTool(
    "current_app",
    { description: "Show the package and activity in the foreground.", inputSchema: {} },
    async () =>
      guarded("current_app", "Check foreground app", { foreground: true }, async (serial) => {
        const fg = await devices.foreground(serial);
        return text(fg ? `${fg.pkg}/${fg.activity}` : "unknown");
      }),
  );

  // ---- input ----

  server.registerTool(
    "tap",
    { description: "Tap at screen coordinates (pixels).", inputSchema: { x: coord, y: coord } },
    async ({ x, y }) => guarded("tap", `Tap (${x}, ${y})`, { foreground: true }, (s) => inputShell(s, `input tap ${x} ${y}`)),
  );

  server.registerTool(
    "long_press",
    {
      description: "Long-press at screen coordinates.",
      inputSchema: { x: coord, y: coord, durationMs: z.number().int().min(100).max(10_000).optional().describe("default 800") },
    },
    async ({ x, y, durationMs }) =>
      guarded("long_press", `Long press (${x}, ${y})`, { foreground: true }, (s) => inputShell(s, `input swipe ${x} ${y} ${x} ${y} ${durationMs ?? 800}`)),
  );

  server.registerTool(
    "swipe",
    {
      description:
        "Swipe from (x1,y1) to (x2,y2). Swipe up (y2 < y1) to scroll down a list. Start and end at least ~60 px from the screen edges: inside the edge zone a swipe trips the system back gesture instead.",
      inputSchema: { x1: coord, y1: coord, x2: coord, y2: coord, durationMs: z.number().int().min(50).max(10_000).optional().describe("default 300") },
    },
    async ({ x1, y1, x2, y2, durationMs }) =>
      guarded("swipe", `Swipe (${x1}, ${y1}) → (${x2}, ${y2})`, { foreground: true }, (s) =>
        inputShell(s, `input swipe ${x1} ${y1} ${x2} ${y2} ${durationMs ?? 300}`),
      ),
  );

  server.registerTool(
    "type_text",
    {
      description: "Type text into the focused field (printable ASCII only; use press_key enter for a new line).",
      inputSchema: { text: z.string().min(1).max(2000) },
    },
    async ({ text: t }) => {
      let escaped: string;
      try {
        escaped = escapeInputText(t);
      } catch (e) {
        return fail((e as Error).message);
      }
      return guarded("type_text", `Type ${JSON.stringify(clip(t, 40))}`, { foreground: true }, (s) => inputShell(s, `input text ${escaped}`));
    },
  );

  const keyNames = Object.keys(KEYCODES) as [string, ...string[]];
  server.registerTool(
    "press_key",
    { description: "Press a key: " + keyNames.join(", "), inputSchema: { key: z.enum(keyNames) } },
    async ({ key }) =>
      guarded("press_key", `Press ${key}`, { foreground: true, allowOverSelf: key === "home" }, (s) => inputShell(s, `input keyevent ${KEYCODES[key]}`)),
  );

  // ---- apps ----

  server.registerTool(
    "launch_app",
    { description: "Open an app by package name (see list_apps).", inputSchema: { package: z.string().min(3).max(200) } },
    async ({ package: pkg }) => {
      if (!PACKAGE_RE.test(pkg)) return fail(`"${pkg}" is not a valid package name`);
      return guarded("launch_app", `Open ${pkg}`, { target: pkg }, async (serial) => {
        const out = await adb(shellArgs(serial, `monkey -p ${pkg} -c android.intent.category.LAUNCHER 1`), { timeoutMs: 20_000, allowFail: true });
        if (/No activities found|monkey aborted|Error/i.test(out)) return fail(`Could not open ${pkg}: ${clip(out.trim(), 200)}`);
        const s = sessions.current;
        if (s && s.startApp?.pkg !== pkg) s.opened.push(pkg);
        const back = s ? returnStep(s) : null;
        return text(`Opened ${pkg}. When you are done with it, back out of it (press_key back)${back ? ` and return to ${back}` : ""} before end_session.`);
      });
    },
  );

  server.registerTool(
    "list_apps",
    {
      description: "List installed packages (third-party by default). Apps the owner has blocked are marked.",
      inputSchema: { includeSystem: z.boolean().optional() },
    },
    async ({ includeSystem }) =>
      guarded("list_apps", includeSystem ? "List all apps" : "List apps", {}, async (serial) => {
        const out = await adb(shellArgs(serial, includeSystem ? "pm list packages" : "pm list packages -3"), { timeoutMs: 20_000, maxBytes: 2 * 1024 * 1024 });
        const p = bridge.policy;
        const home = await devices.homePackage(serial);
        const pkgs = out
          .split("\n")
          .map((l) => l.trim().replace(/^package:/, ""))
          .filter((x) => x && x !== SELF_PACKAGE)
          .sort();
        const lines = pkgs.map((x) => (checkPackage(p, x, { role: "target", homePackage: home }) ? `${x} (blocked by user rules)` : x));
        return text(lines.join("\n") || "(none)");
      }),
  );

  server.registerTool(
    "install_app",
    {
      description:
        "Install (or update) an APK from a path on this computer. The file must be readable by adb-warden: when your own paths cannot be seen here (secure-mode sandbox or MCP server on another host), the error names a staging directory to copy the APK to.",
      inputSchema: { apkPath: z.string().min(1) },
    },
    async ({ apkPath }) => {
      const abs = path.resolve(apkPath);
      return guarded("install_app", `Install ${path.basename(abs)}`, {}, async (serial) => {
        let st: fs.Stats | null = null;
        try {
          st = fs.statSync(abs);
        } catch {
          // No such file. For this process it may be invisible rather than missing — explain where to stage it.
          return fail(missingApkMessage(abs, { secureDaemon: isSecureDaemon(), stage: stageDir() }));
        }
        if (!st.isFile()) return fail(`Not a file: ${abs}`);
        if (!abs.toLowerCase().endsWith(".apk")) return fail("Only .apk files can be installed");
        const out = await adb(["-s", serial, "install", "-r", abs], { timeoutMs: 300_000, allowFail: true });
        return /Success/.test(out) ? text(`Installed ${path.basename(abs)}`) : fail(`Install failed: ${clip(out.trim(), 300)}`);
      });
    },
  );

  // ---- shell ----

  server.registerTool(
    "shell",
    {
      description:
        "Run a shell command on the phone (as the adb shell user). Depending on the owner's rules this is off, limited to read-only commands (ls, cat, dumpsys, getprop, pm list, settings get, logcat -d, … — no ; & > < $ ` ( ) \\), or unrestricted. 30 s timeout, 200 KB output. On many devices \"pm force-stop\" is unavailable — use \"am force-stop <package>\" instead.",
      inputSchema: { command: z.string().min(1).max(4000) },
    },
    async ({ command }) =>
      guarded(
        "shell",
        `Shell: ${clip(command, 50)}`,
        {
          precheck: (p) => {
            if (mentionsSelf(command)) return denial("shell commands may not touch the ADB Warden app");
            if (p.shell === "readonly") {
              const r = checkReadonlyShell(command);
              if (!r.ok) return denial(`shell is limited to read-only commands (${r.reason})`);
            }
            return null;
          },
        },
        async (serial) => {
          const r = await adbRaw(shellArgs(serial, command), { timeoutMs: 30_000, maxBytes: 200 * 1024 });
          let out = r.stdout.toString("utf8");
          if (r.stderr.trim()) out += (out && !out.endsWith("\n") ? "\n" : "") + r.stderr;
          const notes: string[] = [];
          if (r.truncated) notes.push("[output truncated at 200 KB]");
          if (r.timedOut) notes.push("[killed after 30 s]");
          if (r.code !== null && r.code !== 0 && !r.truncated && !r.timedOut) notes.push(`[exit code ${r.code}]`);
          const body = (out.trimEnd() || "(no output)") + (notes.length ? "\n" + notes.join("\n") : "");
          return r.timedOut ? fail(body) : text(body);
        },
      ),
  );
}

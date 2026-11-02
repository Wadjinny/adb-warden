import { execFileSync, spawnSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { configPath, deleteConfig, loadConfig, saveConfig } from "./config.js";
import { daemonAdmin, DEFAULT_SOCKET } from "./daemon.js";

/**
 * Optional secure mode. One polkit prompt (the desktop's own password dialog; this tool never sees the
 * password) installs a daemon that runs as the `adbwarden` system user with its own adb key and a private
 * adb server. Once the phone trusts only that key, nothing else on this computer can use adb on it.
 */

export const MARKER = process.env.ADB_WARDEN_MARKER || "/etc/adb-warden/secure.json";
const SERVICE = "adb-warden.service";

export interface SecureMarker {
  socket: string;
  audit: string;
  version: string;
}

export function secureMarker(): SecureMarker | null {
  try {
    const m = JSON.parse(fs.readFileSync(MARKER, "utf8"));
    return { socket: String(m.socket || DEFAULT_SOCKET), audit: String(m.audit || "/var/log/adb-warden/audit.jsonl"), version: String(m.version || "") };
  } catch {
    return null;
  }
}

const UNIT = `[Unit]
Description=ADB Warden daemon (consent-gated adb for AI agents)
After=network-online.target avahi-daemon.service
Wants=network-online.target

[Service]
User=adbwarden
Group=adbwarden
Environment=HOME=/var/lib/adb-warden
Environment=XDG_CONFIG_HOME=/var/lib/adb-warden/config
Environment=XDG_STATE_HOME=/var/log
Environment=ADB_WARDEN_ADB=/opt/adb-warden/bin/adb
Environment=ADB_SERVER_SOCKET=localfilesystem:/var/lib/adb-warden/adb.sock
Environment=ADB_WARDEN_SOCKET=/run/adb-warden/api.sock
ExecStart=/opt/adb-warden/bin/node /opt/adb-warden/app/dist/cli.js daemon
RuntimeDirectory=adb-warden
RuntimeDirectoryMode=0755
UMask=0022
Restart=on-failure
RestartSec=3
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
PrivateDevices=yes
ReadWritePaths=/var/lib/adb-warden /var/log/adb-warden
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
RestrictSUIDSGID=yes
LockPersonality=yes
RestrictRealtime=yes

[Install]
WantedBy=multi-user.target
`;

/** Runs as root from a root-owned copy of the bundle. $1 = extracted bundle dir. */
const INSTALL_SH = `#!/bin/sh
set -eu
B="$1"
if ! id adbwarden >/dev/null 2>&1; then
  useradd --system --home-dir /var/lib/adb-warden --no-create-home --shell /usr/sbin/nologin adbwarden
fi
systemctl stop ${SERVICE} 2>/dev/null || true
rm -rf /opt/adb-warden.new
mkdir -p /opt/adb-warden.new/bin
cp -R "$B/app" /opt/adb-warden.new/app
install -m 0755 "$B/bin/node" /opt/adb-warden.new/bin/node
install -m 0755 "$B/bin/adb" /opt/adb-warden.new/bin/adb
chown -R root:root /opt/adb-warden.new
chmod -R go-w /opt/adb-warden.new
rm -rf /opt/adb-warden
mv /opt/adb-warden.new /opt/adb-warden
install -d -o adbwarden -g adbwarden -m 0700 /var/lib/adb-warden /var/lib/adb-warden/config /var/lib/adb-warden/config/adb-warden
install -d -o adbwarden -g adbwarden -m 0755 /var/log/adb-warden
if [ -f "$B/config.json" ] && [ ! -f /var/lib/adb-warden/config/adb-warden/config.json ]; then
  install -o adbwarden -g adbwarden -m 0600 "$B/config.json" /var/lib/adb-warden/config/adb-warden/config.json
fi
install -m 0644 "$B/${SERVICE}" /etc/systemd/system/${SERVICE}
install -d -m 0755 /etc/adb-warden
install -m 0644 "$B/secure.json" ${MARKER}
systemctl daemon-reload
systemctl enable --now ${SERVICE}
`;

/** Runs as root. Prints the daemon's link config on stdout so the user can keep using normal mode. */
const UNINSTALL_SH = `set -eu
cat /var/lib/adb-warden/config/adb-warden/config.json 2>/dev/null || true
systemctl disable --now ${SERVICE} 2>/dev/null || true
rm -f /etc/systemd/system/${SERVICE}
systemctl daemon-reload
rm -rf /opt/adb-warden /etc/adb-warden /var/lib/adb-warden /var/log/adb-warden
userdel adbwarden 2>/dev/null || true
`;

/**
 * Inline bootstrap passed to pkexec. It is in argv (so it cannot be edited after the prompt appears),
 * copies the bundle somewhere only root can write, checks its SHA-256 against the hash computed before
 * the prompt, and only then runs the installer from that copy.
 */
const BOOTSTRAP = `set -eu
D=$(mktemp -d)
trap 'rm -rf "$D"' EXIT
cp "$1" "$D/b.tgz"
echo "$2  $D/b.tgz" | sha256sum -c --quiet
mkdir "$D/x"
tar -xzf "$D/b.tgz" -C "$D/x" --no-same-owner
sh "$D/x/install.sh" "$D/x"
`;

function which(cmd: string): string | null {
  const r = spawnSync("sh", ["-c", `command -v ${cmd}`], { encoding: "utf8" });
  return r.status === 0 ? r.stdout.trim() || null : null;
}

function appRoot(): string {
  // dist/secure.js → package root
  return path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
}

/**
 * How to become root. In a terminal (or with no desktop session) use sudo, which reads the password from the
 * terminal itself; otherwise pkexec, which opens the desktop's password dialog. Either way this process never
 * sees the password.
 */
function elevation(): { cmd: string; how: string; stdinPassword?: boolean } {
  if (process.argv.includes("--password-stdin")) {
    const p = which("sudo");
    if (!p) throw new Error("sudo is not installed.");
    return { cmd: p, how: "Using the password from stdin for sudo (not stored; sudo credentials are not cached).", stdinPassword: true };
  }
  const forced = process.argv.includes("--terminal") ? "sudo" : process.argv.includes("--gui") ? "pkexec" : null;
  const graphical = !!(process.env.DISPLAY || process.env.WAYLAND_DISPLAY);
  const tty = !!process.stdin.isTTY;
  const want = forced ?? (tty || !graphical ? "sudo" : "pkexec");
  const p = which(want);
  if (!p) throw new Error(`${want} is not installed.`);
  if (want === "sudo" && !tty) {
    throw new Error("No terminal to ask for the password in. Run this command yourself in a terminal: adb-warden secure install");
  }
  return want === "sudo"
    ? { cmd: p, how: "sudo will ask for your password in this terminal. ADB Warden never sees it." }
    : { cmd: p, how: "Your system will now ask for your password in its own window. ADB Warden never sees it." };
}

function elevated(e: { cmd: string; stdinPassword?: boolean }, script: string, args: string[]): string[] {
  // sudo needs "--" so the script's arguments are not parsed as sudo options.
  // -k: ignore and do not update cached credentials, so nothing else can reuse this sudo afterwards.
  if (e.stdinPassword) return ["-S", "-k", "-p", "", "--", "/bin/sh", "-c", script, ...args];
  return e.cmd.endsWith("sudo") ? ["--", "/bin/sh", "-c", script, ...args] : ["/bin/sh", "-c", script, ...args];
}

/** Reads the password from stdin once (for --password-stdin). Not kept after use. */
function readStdinPassword(): string {
  const raw = fs.readFileSync(0, "utf8");
  const pw = raw.split(/\r?\n/)[0];
  if (!pw) throw new Error("--password-stdin: no password on stdin.");
  return pw;
}

function runElevated(e: { cmd: string; stdinPassword?: boolean }, script: string, args: string[], captureStdout = false) {
  const input = e.stdinPassword ? readStdinPassword() + "\n" : undefined;
  return spawnSync(e.cmd, elevated(e, script, args), {
    stdio: [input !== undefined ? "pipe" : "inherit", captureStdout ? "pipe" : "inherit", "inherit"],
    input,
    encoding: "utf8",
  });
}

function buildBundle(): { tgz: string; sha: string; dir: string } {
  const adb = process.env.ADB_WARDEN_ADB || which("adb");
  if (!adb) throw new Error("adb not found. Set ADB_WARDEN_ADB or put adb on PATH.");
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "adb-warden-bundle-"));
  const stage = path.join(dir, "stage");
  fs.mkdirSync(path.join(stage, "bin"), { recursive: true });
  const root = appRoot();
  fs.mkdirSync(path.join(stage, "app"));
  for (const f of ["package.json", "package-lock.json", "dist", "node_modules"]) {
    const src = path.join(root, f);
    if (fs.existsSync(src)) fs.cpSync(src, path.join(stage, "app", f), { recursive: true, dereference: true });
  }
  execFileSync("npm", ["prune", "--omit=dev", "--no-audit", "--no-fund"], { cwd: path.join(stage, "app"), stdio: "ignore" });
  fs.copyFileSync(fs.realpathSync(process.execPath), path.join(stage, "bin", "node"));
  fs.copyFileSync(fs.realpathSync(adb), path.join(stage, "bin", "adb"));
  fs.writeFileSync(path.join(stage, "install.sh"), INSTALL_SH, { mode: 0o755 });
  fs.writeFileSync(path.join(stage, SERVICE), UNIT);
  const marker: SecureMarker = { socket: DEFAULT_SOCKET, audit: "/var/log/adb-warden/audit.jsonl", version: readVersion(root) };
  fs.writeFileSync(path.join(stage, "secure.json"), JSON.stringify(marker, null, 2) + "\n");
  if (loadConfig()) fs.copyFileSync(configPath(), path.join(stage, "config.json"));
  const tgz = path.join(dir, "bundle.tgz");
  execFileSync("tar", ["-czf", tgz, "-C", stage, "."]);
  const sha = crypto.createHash("sha256").update(fs.readFileSync(tgz)).digest("hex");
  return { tgz, sha, dir };
}

function readVersion(root: string): string {
  try { return JSON.parse(fs.readFileSync(path.join(root, "package.json"), "utf8")).version; } catch { return ""; }
}

async function waitForDaemon(socket: string, ms: number): Promise<boolean> {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (fs.existsSync(socket)) return true;
    await new Promise((r) => setTimeout(r, 300));
  }
  return false;
}

export async function secureInstall(dryRun = false): Promise<number> {
  const elev = dryRun ? null : elevation();
  console.log("Preparing the secure-mode bundle…");
  const { tgz, sha, dir } = buildBundle();
  if (dryRun) {
    console.log(`Bundle: ${tgz} (${(fs.statSync(tgz).size / 1e6).toFixed(1)} MB)\nSHA-256: ${sha}\nNothing installed (dry run). Remove ${dir} when done.`);
    return 0;
  }
  try {
    console.log(`Bundle SHA-256: ${sha}`);
    console.log(elev!.how);
    const r = runElevated(elev!, BOOTSTRAP, ["adb-warden-install", tgz, sha]);
    if (r.status !== 0) {
      console.error(r.status === 126 || r.status === 127 || r.status === 1 ? `Not installed (exit ${r.status}: cancelled or wrong password).` : `Install failed (exit ${r.status}).`);
      return 1;
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
  const m = secureMarker();
  if (!m || !(await waitForDaemon(m.socket, 15_000))) {
    console.error("Installed, but the daemon did not start. Check: systemctl status adb-warden");
    return 1;
  }
  // The daemon now holds the phone link; remove this user's copy of the token.
  if (loadConfig()) {
    deleteConfig();
    console.log(`Moved the phone link into the daemon and removed ${configPath()}.`);
  }
  console.log(`
Secure mode is on. The daemon (user adbwarden) has its own adb key; nothing else on this computer can read it.

Finish on the phone:
  1. Developer options → Wireless debugging → Paired devices: forget the existing entries for this computer.
     (Or "Revoke USB debugging authorizations".) From then on, plain \`adb\` here is refused.
  2. Run: adb-warden secure pair
     The phone shows a pairing request; enter the code from "Pair device with pairing code".

Agents keep using the same MCP server (\`adb-warden serve\`); it now talks to the daemon.`);
  return 0;
}

export async function secureUninstall(): Promise<number> {
  if (!secureMarker()) {
    console.log("Secure mode is not installed.");
    return 0;
  }
  const elev = elevation();
  console.log(elev.how);
  const r = runElevated(elev, UNINSTALL_SH, ["adb-warden-uninstall"], true);
  if (r.status !== 0) {
    console.error(r.status === 126 || r.status === 127 ? "Cancelled." : `Uninstall failed (exit ${r.status}).`);
    return 1;
  }
  const cfgText = (r.stdout || "").trim();
  if (cfgText) {
    try {
      saveConfig(JSON.parse(cfgText));
      console.log(`Kept the phone link in ${configPath()}.`);
    } catch { /* not a config */ }
  }
  console.log("Secure mode removed. The daemon's adb key is deleted; pair this computer's normal adb again if you need it.");
  return 0;
}

export async function secureStatus(): Promise<number> {
  const m = secureMarker();
  if (!m) {
    console.log("Secure mode: not installed (run: adb-warden secure install)");
    return 1;
  }
  const active = spawnSync("systemctl", ["is-active", SERVICE], { encoding: "utf8" }).stdout.trim();
  console.log(`Secure mode: installed (version ${m.version || "?"}), service ${active}`);
  console.log(`Socket: ${m.socket}`);
  console.log(`Audit log: ${m.audit}`);
  if (!fs.existsSync(m.socket)) {
    console.log("Daemon socket missing. Check: systemctl status adb-warden");
    return 1;
  }
  return daemonAdmin(m.socket, "status", []);
}

export async function securePair(): Promise<number> {
  const m = secureMarker();
  if (!m) {
    console.log("Secure mode is not installed.");
    return 1;
  }
  console.log("Asking the phone for a pairing code (check the phone)…");
  return daemonAdmin(m.socket, "pair", []);
}

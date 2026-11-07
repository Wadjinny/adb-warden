import fs from "node:fs";
import os from "node:os";
import path from "node:path";

export const SELF_PACKAGE = "com.wadjinny.adbwarden";
export const DEFAULT_PORT = 47800;
/** Read from package.json (dist/config.js → ../package.json) so release builds report the tagged version. */
export const VERSION: string = (() => {
  try {
    const pkg = new URL("../package.json", import.meta.url);
    return String(JSON.parse(fs.readFileSync(pkg, "utf8")).version || "0.0.0");
  } catch {
    return "0.0.0";
  }
})();
export const CLIENT_ID = `adb-warden-proxy/${VERSION}`;

export interface Config {
  computerId: string;
  token: string;
  deviceId: string;
  deviceName: string;
  host: string;
  port: number;
}

export function configDir(): string {
  const base = process.env.XDG_CONFIG_HOME || path.join(os.homedir(), ".config");
  return path.join(base, "adb-warden");
}

export function stateDir(): string {
  const base = process.env.XDG_STATE_HOME || path.join(os.homedir(), ".local", "state");
  return path.join(base, "adb-warden");
}

export function configPath(): string {
  return path.join(configDir(), "config.json");
}

export function loadConfig(): Config | null {
  try {
    const raw = JSON.parse(fs.readFileSync(configPath(), "utf8"));
    if (typeof raw.computerId !== "string" || typeof raw.token !== "string") return null;
    return {
      computerId: raw.computerId,
      token: raw.token,
      deviceId: String(raw.deviceId ?? ""),
      deviceName: String(raw.deviceName ?? ""),
      host: String(raw.host ?? ""),
      port: Number(raw.port) || DEFAULT_PORT,
    };
  } catch {
    return null;
  }
}

export function saveConfig(cfg: Config): void {
  fs.mkdirSync(configDir(), { recursive: true, mode: 0o700 });
  const file = configPath();
  fs.writeFileSync(file, JSON.stringify(cfg, null, 2) + "\n", { mode: 0o600 });
  fs.chmodSync(file, 0o600);
}

export function deleteConfig(): boolean {
  try {
    fs.unlinkSync(configPath());
    return true;
  } catch {
    return false;
  }
}

export function adbPath(): string {
  return process.env.ADB_WARDEN_ADB || "adb";
}

/** Running inside the secure-mode daemon (sandboxed system user): tools can tailor their hints. */
export function isSecureDaemon(): boolean {
  return !!process.env.ADB_WARDEN_SOCKET;
}

/** Logging for serve mode: stdout is the MCP channel, so everything goes to stderr. */
export function logErr(...args: unknown[]): void {
  process.stderr.write(`[adb-warden] ${args.map((a) => (typeof a === "string" ? a : JSON.stringify(a))).join(" ")}\n`);
}

import { BridgeConnection, BridgeError, discoverApps } from "./bridge.js";
import { configPath, DEFAULT_PORT, deleteConfig, loadConfig, saveConfig } from "./config.js";
import { createShared, describePolicy, Shared } from "./tools.js";

/** Commands that run wherever the phone link lives: in this process, or inside the secure-mode daemon. */
export type Out = (line: string) => void;

function parseHostPort(s: string): { host: string; port: number } {
  const i = s.lastIndexOf(":");
  if (i > 0 && /^\d+$/.test(s.slice(i + 1))) return { host: s.slice(0, i), port: Number(s.slice(i + 1)) };
  return { host: s, port: DEFAULT_PORT };
}

export async function linkPhone(code: string | undefined, hostArg: string | undefined, out: Out, err: Out): Promise<number> {
  if (!code || !/^\d{6}$/.test(code)) {
    err("Usage: adb-warden link <6-digit code> [host[:port]]");
    return 2;
  }
  let candidates: Array<{ host: string; port: number }>;
  if (hostArg) {
    candidates = [parseHostPort(hostArg)];
  } else {
    out("Looking for ADB Warden on the network (10 s)…");
    const found = await discoverApps(10_000);
    if (!found.length) {
      err("No phone found. Make sure the app is open on the same Wi-Fi, or pass the address shown in the app: adb-warden link <code> <ip:port>");
      return 1;
    }
    candidates = found;
  }
  let lastErr = "";
  for (const c of candidates) {
    try {
      const info = await BridgeConnection.link(c.host, c.port, code);
      const cfg = { ...info, host: c.host, port: c.port };
      saveConfig(cfg);
      const conn = await BridgeConnection.connectAuth(c.host, c.port, cfg);
      out(`Linked to ${info.deviceName || "phone"} at ${c.host}:${c.port}.`);
      out(`Rules: ${describePolicy(conn.policy)}`);
      conn.close("link check done");
      return 0;
    } catch (e) {
      lastErr = `${c.host}:${c.port}: ${(e as Error).message}`;
      if (e instanceof BridgeError && (e.code === "bad_link_code" || e.code === "not_linking")) continue;
    }
  }
  err(`Linking failed. ${lastErr}`);
  return 1;
}

/** `shared` is the daemon's long-lived connections; without it a temporary one is opened. */
export async function statusReport(out: Out, shared?: Shared, pair = false): Promise<number> {
  const cfg = loadConfig();
  if (!cfg) {
    out("Not linked. Run: adb-warden link <code>");
    return 1;
  }
  const own = !shared;
  const ctx = shared ?? createShared();
  try {
    const conn = await ctx.bridge.ensure();
    out(`Phone app: ${conn.hello?.deviceName || cfg.deviceName} at ${conn.host}:${conn.port} (connected)`);
    out(`Device: ${[conn.device?.manufacturer, conn.device?.model].filter(Boolean).join(" ")} Android ${conn.device?.androidVersion} (SDK ${conn.device?.sdk})`);
    out(`Phone IPs: ${conn.device?.ips.join(", ") || "none"}`);
    out(`Rules: ${describePolicy(conn.policy)}`);
    const progress = (level: string, message: string) => out(`  ${level === "info" ? "·" : level}: ${message}`);
    let paired = false;
    const watch = (_l: string, m: string) => { if (/^paired with/.test(m)) paired = true; };
    if (pair) { ctx.logListeners.add(progress); ctx.logListeners.add(watch); }
    try {
      const wait = pair && conn.policy.adbLock ? 30_000 : 0;
      const serial = await ctx.devices.ensure(conn.device?.ips ?? [], pair, wait);
      out(`adb: ${serial}`);
      if (pair) {
        out(paired
          ? "Paired: the phone now trusts this computer's adb key."
          : "Already paired and connected: no new pairing was needed.");
      }
    } catch (e) {
      out(`adb: ${(e as Error).message}`);
      if (pair) return 1;
    } finally {
      ctx.logListeners.delete(progress);
      ctx.logListeners.delete(watch);
    }
    return 0;
  } catch (e) {
    out(`Phone app: ${(e as Error).message}`);
    return 1;
  } finally {
    if (own) ctx.bridge.close();
  }
}

export function unlinkPhone(out: Out): number {
  out(deleteConfig() ? `Removed ${configPath()}. Also remove this computer in the app (Setup → Linked computers).` : "Not linked.");
  return 0;
}

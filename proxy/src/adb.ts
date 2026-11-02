import { spawn } from "node:child_process";
import { adbPath } from "./config.js";
import { parseHomePackage, parseResumedActivity, parseWindowFocus } from "./policy.js";

export interface ExecResult {
  stdout: Buffer;
  stderr: string;
  code: number | null;
  timedOut: boolean;
  truncated: boolean;
}

export class AdbError extends Error {}

export function adbRaw(args: string[], opts: { timeoutMs?: number; maxBytes?: number } = {}): Promise<ExecResult> {
  const timeoutMs = opts.timeoutMs ?? 30_000;
  const maxBytes = opts.maxBytes ?? 200 * 1024;
  return new Promise((resolve, reject) => {
    let child;
    try {
      child = spawn(adbPath(), args, { stdio: ["ignore", "pipe", "pipe"] });
    } catch (e) {
      return reject(new AdbError(`cannot run adb: ${(e as Error).message}`));
    }
    const chunks: Buffer[] = [];
    let size = 0;
    let truncated = false;
    let stderr = "";
    let timedOut = false;
    child.stdout.on("data", (d: Buffer) => {
      if (truncated) return;
      if (size + d.length > maxBytes) {
        chunks.push(d.subarray(0, maxBytes - size));
        size = maxBytes;
        truncated = true;
        child.kill("SIGKILL");
        return;
      }
      chunks.push(d);
      size += d.length;
    });
    child.stderr.on("data", (d: Buffer) => {
      if (stderr.length < 64 * 1024) stderr += d.toString("utf8");
    });
    const timer = setTimeout(() => {
      timedOut = true;
      child.kill("SIGKILL");
    }, timeoutMs);
    child.on("error", (e) => {
      clearTimeout(timer);
      reject(new AdbError(`cannot run adb (${adbPath()}): ${e.message}. Set ADB_WARDEN_ADB or put adb on PATH.`));
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      resolve({ stdout: Buffer.concat(chunks), stderr, code, timedOut, truncated });
    });
  });
}

/** Run adb and return stdout as text; throws on non-zero exit (unless allowFail). */
export async function adb(args: string[], opts: { timeoutMs?: number; maxBytes?: number; allowFail?: boolean } = {}): Promise<string> {
  const r = await adbRaw(args, opts);
  const out = r.stdout.toString("utf8");
  if (r.timedOut) throw new AdbError(`adb ${args.join(" ")} timed out`);
  if (!opts.allowFail && r.code !== 0 && !r.truncated) {
    throw new AdbError(`adb ${args.join(" ")} failed: ${(r.stderr || out).trim() || `exit ${r.code}`}`);
  }
  return out;
}

export function shellArgs(serial: string, command: string): string[] {
  return ["-s", serial, "shell", command];
}

// ---- parsers (pure) ----

export interface AdbDevice {
  serial: string;
  state: string;
  model?: string;
}

export function parseDevices(text: string): AdbDevice[] {
  const out: AdbDevice[] = [];
  for (const line of text.split("\n")) {
    const l = line.trim();
    if (!l || l.startsWith("List of devices") || l.startsWith("*")) continue;
    const parts = l.split(/\s+/);
    if (parts.length < 2) continue;
    const model = parts.find((p) => p.startsWith("model:"))?.slice(6);
    out.push({ serial: parts[0], state: parts[1], model });
  }
  return out;
}

export interface MdnsService {
  name: string;
  type: string;
  host: string;
  port: number;
}

export function parseMdnsServices(text: string): MdnsService[] {
  const out: MdnsService[] = [];
  for (const line of text.split("\n")) {
    const m = line.trim().match(/^(\S+)\s+(_adb[\w-]*\._tcp)\.?\s+(\d+\.\d+\.\d+\.\d+):(\d+)/);
    if (m) out.push({ name: m[1], type: m[2], host: m[3], port: Number(m[4]) });
  }
  return out;
}

export function parseIpv4s(text: string): string[] {
  const ips = new Set<string>();
  for (const m of text.matchAll(/inet (\d+\.\d+\.\d+\.\d+)\//g)) if (!m[1].startsWith("127.")) ips.add(m[1]);
  return [...ips];
}

// ---- device selection ----

export interface PairingCode {
  code: string;
  port?: number | null;
}

export interface DeviceHooks {
  /** Ask the phone for a pairing code. Return null if the user cancelled. */
  requestPairing?: (reason: string) => Promise<{ code: string; port?: number | null; reply: (ok: boolean, message: string) => void } | null>;
  log?: (level: "info" | "warn" | "error", message: string) => void;
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export class DeviceManager {
  private serial: string | null = null;
  private selecting: Promise<string> | null = null;
  private homeCache = new Map<string, string | null>();

  constructor(private hooks: DeviceHooks = {}) {}

  get currentSerial(): string | null {
    return this.serial;
  }

  /** Find (or connect, or pair) the adb device that is the linked phone. */
  ensure(ips: string[], allowPairing = true, waitMs = 0): Promise<string> {
    if (!this.selecting) {
      this.selecting = this.select(ips, allowPairing, waitMs).finally(() => {
        this.selecting = null;
      });
    }
    return this.selecting;
  }

  private async isOnline(serial: string): Promise<boolean> {
    const out = await adb(["-s", serial, "get-state"], { timeoutMs: 5000, allowFail: true }).catch(() => "");
    return out.trim() === "device";
  }

  private async findMatch(ips: string[]): Promise<string | null> {
    const devices = parseDevices(await adb(["devices", "-l"], { timeoutMs: 10_000 }));
    const online = devices.filter((d) => d.state === "device");
    const unauthorized = devices.filter((d) => d.state === "unauthorized");
    let mdns: MdnsService[] | null = null;
    const getMdns = async () => (mdns ??= parseMdnsServices(await adb(["mdns", "services"], { timeoutMs: 10_000, allowFail: true }).catch(() => "")));

    for (const d of online) {
      const m = d.serial.match(/^(\d+\.\d+\.\d+\.\d+):\d+$/);
      if (m && ips.includes(m[1])) return d.serial;
    }
    for (const d of online) {
      if (!d.serial.includes("._adb-tls-connect._tcp")) continue;
      const name = d.serial.replace(/\._adb-tls-connect\._tcp\.?$/, "");
      const svc = (await getMdns()).find((s) => s.name === name && s.type === "_adb-tls-connect._tcp");
      if (svc && ips.includes(svc.host)) return d.serial;
    }
    for (const d of online) {
      if (/^\d+\.\d+\.\d+\.\d+:\d+$/.test(d.serial) || d.serial.includes("._adb-tls-connect._tcp")) continue;
      const out = await adb(shellArgs(d.serial, "ip -4 -o addr"), { timeoutMs: 8000, allowFail: true }).catch(() => "");
      if (parseIpv4s(out).some((ip) => ips.includes(ip))) return d.serial;
    }
    if (unauthorized.length) {
      this.hooks.log?.("warn", `adb device ${unauthorized.map((d) => d.serial).join(", ")} is unauthorized — accept the "Allow USB debugging" prompt on the phone`);
    }
    return null;
  }

  private async tryMdnsConnect(ips: string[]): Promise<string | null> {
    const services = parseMdnsServices(await adb(["mdns", "services"], { timeoutMs: 10_000, allowFail: true }).catch(() => ""));
    for (const s of services.filter((s) => s.type === "_adb-tls-connect._tcp" && ips.includes(s.host))) {
      const out = await adb(["connect", `${s.host}:${s.port}`], { timeoutMs: 15_000, allowFail: true }).catch(() => "");
      if (/connected to/i.test(out) && !/failed|cannot|unable/i.test(out)) {
        const serial = `${s.host}:${s.port}`;
        for (let i = 0; i < 10; i++) {
          if (await this.isOnline(serial)) {
            this.hooks.log?.("info", `adb connected to ${serial}`);
            return serial;
          }
          await sleep(500);
        }
      }
    }
    return null;
  }

  private async select(ips: string[], allowPairing: boolean, waitMs = 0): Promise<string> {
    if (this.serial && (await this.isOnline(this.serial))) return this.serial;
    this.serial = null;
    if (!ips.length) {
      throw new AdbError("The phone did not report a Wi-Fi IP address. Connect the phone to Wi-Fi (or USB) and try again.");
    }
    let found = (await this.findMatch(ips)) ?? (await this.tryMdnsConnect(ips));
    if (!found && waitMs > 0) {
      // The phone is turning wireless debugging on (ADB lock); adb usually reconnects on its own via mDNS.
      this.hooks.log?.("info", "waiting for the phone to turn wireless debugging on");
      const deadline = Date.now() + waitMs;
      while (!found && Date.now() < deadline) {
        await sleep(1500);
        found = (await this.findMatch(ips)) ?? (await this.tryMdnsConnect(ips));
      }
    }
    if (!found && allowPairing && this.hooks.requestPairing) {
      found = await this.pair(ips);
    }
    if (!found) {
      throw new AdbError(
        allowPairing
          ? "The phone is not reachable over adb. Turn on Developer options → Wireless debugging on the phone and try again."
          : "The phone is not connected over adb (pairing is not attempted from this command).",
      );
    }
    this.serial = found;
    return found;
  }

  private async pair(ips: string[]): Promise<string | null> {
    const req = await this.hooks.requestPairing!("This computer is not paired with the phone over wireless debugging");
    if (!req) throw new AdbError("Pairing was cancelled on the phone.");
    const code = req.code.trim();
    try {
      if (!/^\d{6}$/.test(code)) throw new AdbError("The pairing code must be 6 digits.");
      let host = ips[0];
      let port = req.port ?? null;
      if (!port) {
        for (let i = 0; i < 20 && !port; i++) {
          const svc = parseMdnsServices(await adb(["mdns", "services"], { timeoutMs: 10_000, allowFail: true }).catch(() => "")).find(
            (s) => s.type === "_adb-tls-pairing._tcp" && ips.includes(s.host),
          );
          if (svc) {
            host = svc.host;
            port = svc.port;
          } else await sleep(1500);
        }
      }
      if (!port) throw new AdbError("Could not find the pairing port. Keep the \"Pair device with pairing code\" dialog open, or type the port shown there.");
      const out = await adb(["pair", `${host}:${port}`, code], { timeoutMs: 30_000, allowFail: true });
      if (!/successfully paired/i.test(out)) throw new AdbError(`adb pair failed: ${out.trim() || "no output"}`);
      this.hooks.log?.("info", `paired with ${host}`);
      let serial: string | null = null;
      for (let i = 0; i < 15 && !serial; i++) {
        serial = (await this.findMatch(ips)) ?? (await this.tryMdnsConnect(ips));
        if (!serial) await sleep(1500);
      }
      if (!serial) throw new AdbError("Paired, but could not connect. Check that Wireless debugging is still on.");
      req.reply(true, `Paired and connected to ${serial}`);
      return serial;
    } catch (e) {
      req.reply(false, (e as Error).message);
      throw e;
    }
  }

  async homePackage(serial: string): Promise<string | null> {
    if (this.homeCache.has(serial)) return this.homeCache.get(serial)!;
    const out = await adb(
      shellArgs(serial, "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"),
      { timeoutMs: 8000, allowFail: true },
    ).catch(() => "");
    const pkg = parseHomePackage(out);
    this.homeCache.set(serial, pkg);
    return pkg;
  }

  async foreground(serial: string): Promise<{ pkg: string; activity: string } | null> {
    const a = await adb(shellArgs(serial, "dumpsys activity activities | grep -E 'ResumedActivity'"), {
      timeoutMs: 8000,
      allowFail: true,
    }).catch(() => "");
    const r = parseResumedActivity(a);
    if (r) return r;
    const w = await adb(shellArgs(serial, "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"), { timeoutMs: 8000, allowFail: true }).catch(() => "");
    return parseWindowFocus(w);
  }

  forget(): void {
    this.serial = null;
  }
}

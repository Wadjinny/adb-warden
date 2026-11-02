import net from "node:net";
import os from "node:os";
import crypto from "node:crypto";
import { EventEmitter } from "node:events";
import { execFile } from "node:child_process";
import { CLIENT_ID, Config, DEFAULT_PORT, loadConfig, saveConfig } from "./config.js";
import { LOCKED_POLICY, normalizePolicy, Policy } from "./policy.js";

export const MAX_LINE = 1024 * 1024;
const PING_MS = 20_000;
const SILENCE_MS = 60_000;

export interface DeviceInfo {
  manufacturer: string;
  model: string;
  androidVersion: string;
  sdk: number;
  ips: string[];
}

export interface Hello {
  v: number;
  deviceId: string;
  deviceName: string;
  nonce: string;
}

export interface LinkedInfo {
  computerId: string;
  token: string;
  deviceId: string;
  deviceName: string;
}

export class BridgeError extends Error {
  constructor(public code: string, message: string) {
    super(message);
  }
}

export function hmacHex(token: string, nonce: string): string {
  return crypto.createHmac("sha256", Buffer.from(token, "utf8")).update(nonce, "utf8").digest("hex");
}

type Msg = Record<string, unknown> & { t: string };

/** NDJSON line splitter with a max line length. */
export class LineReader {
  private buf = "";
  constructor(private onLine: (line: string) => void, private onOverflow: () => void) {}
  push(chunk: string): void {
    this.buf += chunk;
    let idx: number;
    while ((idx = this.buf.indexOf("\n")) >= 0) {
      const line = this.buf.slice(0, idx).replace(/\r$/, "");
      this.buf = this.buf.slice(idx + 1);
      if (line.trim()) this.onLine(line);
    }
    if (this.buf.length > MAX_LINE) {
      this.buf = "";
      this.onOverflow();
    }
  }
}

/**
 * One TCP connection to the phone app. Emits:
 *  - "policy" (Policy)
 *  - "stop" (sessionId)
 *  - "close" (reason string)
 */
export class BridgeConnection extends EventEmitter {
  private socket: net.Socket;
  private nextId = 1;
  private pending = new Map<number, { resolve: (m: Msg) => void; reject: (e: Error) => void; timer: NodeJS.Timeout }>();
  private pingTimer?: NodeJS.Timeout;
  private silenceTimer?: NodeJS.Timeout;
  private closed = false;
  hello?: Hello;
  policy: Policy = LOCKED_POLICY;
  device?: DeviceInfo;
  readonly host: string;
  readonly port: number;
  /** Set by open(): switches from handshake reads to normal message handling. */
  private finishHandshake: () => void = () => {};

  private constructor(socket: net.Socket, host: string, port: number) {
    super();
    this.socket = socket;
    this.host = host;
    this.port = port;
  }

  get isOpen(): boolean {
    return !this.closed;
  }

  /** Open a socket and wait for the app's hello. */
  private static open(host: string, port: number, timeoutMs: number): Promise<{ conn: BridgeConnection; first: Promise<Msg>; next: () => Promise<Msg> }> {
    return new Promise((resolve, reject) => {
      const socket = net.createConnection({ host, port });
      socket.setEncoding("utf8");
      socket.setNoDelay(true);
      const conn = new BridgeConnection(socket, host, port);
      const queue: Msg[] = [];
      const waiters: Array<{ res: (m: Msg) => void; rej: (e: Error) => void }> = [];
      let handshakeDone = false;
      let failure: Error | null = null;

      const fail = (e: Error) => {
        failure = failure ?? e;
        while (waiters.length) waiters.shift()!.rej(failure);
      };
      const next = (): Promise<Msg> =>
        new Promise((res, rej) => {
          if (queue.length) return res(queue.shift()!);
          if (failure) return rej(failure);
          waiters.push({ res, rej });
        });

      const reader = new LineReader(
        (line) => {
          let msg: Msg;
          try {
            msg = JSON.parse(line);
          } catch {
            return;
          }
          if (!msg || typeof msg.t !== "string") return;
          conn.touchSilence();
          if (handshakeDone) conn.handle(msg);
          else if (waiters.length) waiters.shift()!.res(msg);
          else queue.push(msg);
        },
        () => conn.close("line too long"),
      );
      socket.on("data", (d: string) => reader.push(d));
      socket.on("error", (e) => {
        fail(new BridgeError("socket", `${host}:${port}: ${e.message}`));
        conn.close(`socket error: ${e.message}`);
      });
      socket.on("close", () => {
        fail(new BridgeError("closed", `connection to ${host}:${port} closed`));
        conn.close("socket closed");
      });
      const t = setTimeout(() => {
        socket.destroy();
        reject(new BridgeError("timeout", `timed out connecting to ${host}:${port}`));
      }, timeoutMs);
      socket.once("connect", () => {
        clearTimeout(t);
        conn.finishHandshake = () => {
          handshakeDone = true;
          for (const m of queue.splice(0)) conn.handle(m);
        };
        resolve({ conn, first: next(), next });
      });
      socket.once("error", (e) => {
        clearTimeout(t);
        reject(new BridgeError("socket", `cannot connect to ${host}:${port}: ${e.message}`));
      });
    });
  }

  private static async readHello(first: Promise<Msg>, timeoutMs: number): Promise<Hello> {
    const msg = await withTimeout(first, timeoutMs, "no hello from the phone app");
    if (msg.t !== "hello") throw new BridgeError("bad_request", `expected hello, got ${msg.t}`);
    if (Number(msg.v) !== 1) throw new BridgeError("version", `unsupported protocol version ${msg.v}`);
    return { v: 1, deviceId: String(msg.deviceId ?? ""), deviceName: String(msg.deviceName ?? ""), nonce: String(msg.nonce ?? "") };
  }

  /** Connect and authenticate with a stored token. */
  static async connectAuth(host: string, port: number, cfg: Pick<Config, "computerId" | "token">, timeoutMs = 8000): Promise<BridgeConnection> {
    const { conn, first, next } = await BridgeConnection.open(host, port, timeoutMs);
    try {
      conn.hello = await BridgeConnection.readHello(first, timeoutMs);
      conn.sendRaw({ t: "auth", computerId: cfg.computerId, mac: hmacHex(cfg.token, conn.hello.nonce), client: CLIENT_ID, host: os.hostname() });
      const reply = await withTimeout(next(), timeoutMs, "no answer to auth");
      if (reply.t === "error") throw new BridgeError(String(reply.code ?? "error"), String(reply.message ?? reply.code));
      if (reply.t !== "ready") throw new BridgeError("bad_request", `expected ready, got ${reply.t}`);
      conn.policy = normalizePolicy(reply.policy);
      const d = (reply.device ?? {}) as Record<string, unknown>;
      conn.device = {
        manufacturer: String(d.manufacturer ?? ""),
        model: String(d.model ?? ""),
        androidVersion: String(d.androidVersion ?? ""),
        sdk: Number(d.sdk) || 0,
        ips: Array.isArray(d.ips) ? d.ips.map(String) : [],
      };
      conn.finishHandshake();
      conn.startKeepalive();
      return conn;
    } catch (e) {
      conn.close("handshake failed");
      throw e;
    }
  }

  /** One-shot link with a 6-digit code. */
  static async link(host: string, port: number, code: string, timeoutMs = 8000): Promise<LinkedInfo> {
    const { conn, first, next } = await BridgeConnection.open(host, port, timeoutMs);
    try {
      await BridgeConnection.readHello(first, timeoutMs);
      conn.sendRaw({ t: "link", code, host: os.hostname() });
      const reply = await withTimeout(next(), timeoutMs, "no answer to link");
      if (reply.t === "error") throw new BridgeError(String(reply.code ?? "error"), String(reply.message ?? reply.code));
      if (reply.t !== "linked") throw new BridgeError("bad_request", `expected linked, got ${reply.t}`);
      return {
        computerId: String(reply.computerId),
        token: String(reply.token),
        deviceId: String(reply.deviceId ?? ""),
        deviceName: String(reply.deviceName ?? ""),
      };
    } finally {
      conn.close("link done");
    }
  }

  private handle(msg: Msg): void {
    switch (msg.t) {
      case "reply": {
        const id = Number(msg.id);
        const p = this.pending.get(id);
        if (p) {
          clearTimeout(p.timer);
          this.pending.delete(id);
          p.resolve(msg);
        }
        break;
      }
      case "policy":
        this.policy = normalizePolicy(msg.policy);
        this.emit("policy", this.policy);
        break;
      case "stop":
        this.emit("stop", String(msg.sessionId ?? ""));
        break;
      case "ping":
        this.sendRaw({ t: "pong" });
        break;
      case "pong":
        break;
      case "error":
        this.emit("app_error", msg);
        break;
      default:
        break;
    }
  }

  private startKeepalive(): void {
    this.touchSilence();
    this.pingTimer = setInterval(() => this.sendRaw({ t: "ping" }), PING_MS);
    this.pingTimer.unref();
  }

  private touchSilence(): void {
    if (this.closed) return;
    if (this.silenceTimer) clearTimeout(this.silenceTimer);
    this.silenceTimer = setTimeout(() => this.close("no data for 60 s"), SILENCE_MS);
    this.silenceTimer.unref();
  }

  sendRaw(msg: Msg): boolean {
    if (this.closed) return false;
    this.socket.write(JSON.stringify(msg) + "\n");
    return true;
  }

  /** Send an event (no reply expected). */
  send(msg: Msg): boolean {
    return this.sendRaw(msg);
  }

  /** Flush pending writes (used on exit). */
  flush(): Promise<void> {
    return new Promise((resolve) => {
      if (this.closed) return resolve();
      this.socket.write("", () => resolve());
    });
  }

  request(t: string, payload: Record<string, unknown>, timeoutMs: number, onId?: (id: number) => void): Promise<Msg> {
    if (this.closed) return Promise.reject(new BridgeError("closed", "not connected to the phone app"));
    const id = this.nextId++;
    onId?.(id);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new BridgeError("timeout", `the phone did not answer ${t} in time`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      this.sendRaw({ t, id, ...payload });
    });
  }

  close(reason = "closed"): void {
    if (this.closed) return;
    this.closed = true;
    if (this.pingTimer) clearInterval(this.pingTimer);
    if (this.silenceTimer) clearTimeout(this.silenceTimer);
    for (const [, p] of this.pending) {
      clearTimeout(p.timer);
      p.reject(new BridgeError("closed", `connection to the phone app closed (${reason})`));
    }
    this.pending.clear();
    this.socket.end();
    this.socket.destroy();
    this.emit("close", reason);
  }
}

export function withTimeout<T>(p: Promise<T>, ms: number, what: string): Promise<T> {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new BridgeError("timeout", what)), ms);
    p.then(
      (v) => {
        clearTimeout(t);
        resolve(v);
      },
      (e) => {
        clearTimeout(t);
        reject(e);
      },
    );
  });
}

// ---- mDNS ----

export interface Discovered {
  host: string;
  port: number;
  id: string;
  name: string;
}

/** Parses `avahi-browse -rpt` resolved lines ("=;iface;IPv4;name;type;domain;host;addr;port;txt"). */
export function parseAvahi(out: string): Discovered[] {
  const found = new Map<string, Discovered>();
  for (const line of out.split("\n")) {
    const f = line.split(";");
    if (f[0] !== "=" || f[2] !== "IPv4" || f.length < 10) continue;
    const host = f[7];
    const port = Number(f[8]);
    if (!net.isIPv4(host) || !port) continue;
    const txt = f.slice(9).join(";");
    const id = /"id=([^"]*)"/.exec(txt)?.[1] ?? "";
    const name = f[3].replace(/\\(\d{3})/g, (_, d) => String.fromCharCode(Number(d)));
    found.set(`${host}:${port}`, { host, port, id, name });
  }
  return [...found.values()];
}

/** Uses the system mDNS daemon when present (works behind host firewalls that drop other mDNS replies). */
function discoverWithAvahi(timeoutMs: number): Promise<Discovered[] | null> {
  return new Promise((resolve) => {
    execFile(
      "avahi-browse",
      ["-rpt", "_adbwarden._tcp"],
      { timeout: Math.max(timeoutMs, 3000), maxBuffer: 1024 * 1024 },
      (err, stdout) => {
        const e = err as NodeJS.ErrnoException | null;
        if (e?.code === "ENOENT") return resolve(null);
        resolve(parseAvahi(String(stdout ?? "")));
      },
    );
  });
}

export async function discoverApps(timeoutMs = 4000, wantId?: string): Promise<Discovered[]> {
  if (process.platform === "linux") {
    const viaAvahi = await discoverWithAvahi(timeoutMs);
    if (viaAvahi && viaAvahi.length > 0) return viaAvahi;
  }
  return discoverWithBonjour(timeoutMs, wantId);
}

async function discoverWithBonjour(timeoutMs: number, wantId?: string): Promise<Discovered[]> {
  const { Bonjour } = await import("bonjour-service");
  const bonjour = new Bonjour();
  const found = new Map<string, Discovered>();
  return new Promise((resolve) => {
    const finish = () => {
      try {
        browser.stop();
        bonjour.destroy();
      } catch {
        /* ignore */
      }
      resolve([...found.values()]);
    };
    const browser = bonjour.find({ type: "adbwarden", protocol: "tcp" }, (svc) => {
      const addrs = (svc.addresses ?? []).filter((a) => net.isIPv4(a));
      const host = addrs[0] ?? svc.referer?.address ?? svc.host;
      const txt = (svc.txt ?? {}) as Record<string, unknown>;
      const id = String(txt.id ?? "");
      if (!host) return;
      found.set(`${host}:${svc.port}`, { host, port: svc.port, id, name: svc.name });
      if (wantId && id === wantId) {
        clearTimeout(timer);
        finish();
      }
    });
    const timer = setTimeout(finish, timeoutMs);
  });
}

// ---- connection manager ----

/**
 * Keeps one authenticated connection to the phone app. Tries the stored host first,
 * then mDNS (and updates the stored host when the phone's IP changed).
 */
export class BridgeManager extends EventEmitter {
  private conn: BridgeConnection | null = null;
  private connecting: Promise<BridgeConnection> | null = null;

  constructor(private loadCfg: () => Config | null = loadConfig) {
    super();
  }

  get current(): BridgeConnection | null {
    return this.conn && this.conn.isOpen ? this.conn : null;
  }

  get policy(): Policy {
    return this.current?.policy ?? LOCKED_POLICY;
  }

  ensure(): Promise<BridgeConnection> {
    const c = this.current;
    if (c) return Promise.resolve(c);
    if (!this.connecting) {
      this.connecting = this.connect().finally(() => {
        this.connecting = null;
      });
    }
    return this.connecting;
  }

  private async connect(): Promise<BridgeConnection> {
    const cfg = this.loadCfg();
    if (!cfg) {
      throw new BridgeError(
        "not_linked",
        "This computer is not linked to a phone yet. On the phone open ADB Warden → Setup → Link a computer, then run `adb-warden link <code>` in a terminal.",
      );
    }
    const override = process.env.ADB_WARDEN_HOST;
    const candidates: Array<{ host: string; port: number }> = [];
    if (override) {
      const [h, p] = override.split(":");
      candidates.push({ host: h, port: Number(p) || cfg.port || DEFAULT_PORT });
    } else if (cfg.host) {
      candidates.push({ host: cfg.host, port: cfg.port || DEFAULT_PORT });
    }
    let lastErr: Error | null = null;
    for (const c of candidates) {
      try {
        return this.adopt(await BridgeConnection.connectAuth(c.host, c.port, cfg));
      } catch (e) {
        lastErr = e as Error;
        if (e instanceof BridgeError && e.code === "bad_auth") throw authError(e);
      }
    }
    if (!override) {
      const found = await discoverApps(4000, cfg.deviceId || undefined);
      const ordered = found.sort((a, b) => Number(b.id === cfg.deviceId) - Number(a.id === cfg.deviceId));
      for (const f of ordered) {
        if (cfg.deviceId && f.id && f.id !== cfg.deviceId) continue;
        if (candidates.some((c) => c.host === f.host && c.port === f.port)) continue;
        try {
          const conn = await BridgeConnection.connectAuth(f.host, f.port, cfg);
          saveConfig({ ...cfg, host: f.host, port: f.port });
          return this.adopt(conn);
        } catch (e) {
          lastErr = e as Error;
          if (e instanceof BridgeError && e.code === "bad_auth") throw authError(e);
        }
      }
    }
    throw new BridgeError(
      "unreachable",
      `Cannot reach the ADB Warden app on the phone${lastErr ? ` (${lastErr.message})` : ""}. Make sure the app is running and the phone is on the same Wi-Fi network.`,
    );
  }

  private adopt(conn: BridgeConnection): BridgeConnection {
    this.conn = conn;
    conn.on("policy", (p) => this.emit("policy", p));
    conn.on("stop", (id) => this.emit("stop", id));
    conn.on("close", (r) => {
      if (this.conn === conn) this.conn = null;
      this.emit("disconnected", r);
    });
    this.emit("connected", conn);
    return conn;
  }

  close(): void {
    this.conn?.close("proxy exit");
  }
}

function authError(e: BridgeError): BridgeError {
  return new BridgeError(
    "bad_auth",
    `The phone rejected this computer's key (${e.message}). It may have been removed in the app. Link again: ADB Warden → Setup → Link a computer, then run \`adb-warden link <code>\`.`,
  );
}

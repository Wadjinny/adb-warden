import fs from "node:fs";
import net from "node:net";
import path from "node:path";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { linkPhone, statusReport, unlinkPhone } from "./admin.js";
import { logErr } from "./config.js";
import { buildMcpServer } from "./mcp.js";
import { createShared } from "./tools.js";

/**
 * Secure-mode daemon. Runs as the `adbwarden` system user, owns the only adb key the phone trusts and a
 * private adb server, and serves MCP clients over a Unix socket. Every client goes through the same consent,
 * rules, indicator and audit log, so talking to the socket directly gains nothing over the MCP tools.
 *
 * Socket protocol: the client's first line is JSON, then
 *   {"mode":"mcp"}                         → the rest of the stream is MCP (newline-delimited JSON-RPC)
 *   {"mode":"admin","cmd":"status"|"pair"|"link"|"unlink", "args":[…]}
 *                                          → the daemon writes {"out":"…"} lines, then {"exit":N}, and closes
 */
export const DEFAULT_SOCKET = "/run/adb-warden/api.sock";

export async function runDaemon(): Promise<void> {
  const socketPath = process.env.ADB_WARDEN_SOCKET || DEFAULT_SOCKET;
  const shared = createShared();
  let clients = 0;

  fs.mkdirSync(path.dirname(socketPath), { recursive: true });
  try { fs.unlinkSync(socketPath); } catch { /* not there */ }

  const server = net.createServer((sock) => {
    let buf = Buffer.alloc(0);
    const onData = (chunk: Buffer) => {
      buf = Buffer.concat([buf, chunk]);
      const nl = buf.indexOf(0x0a);
      if (nl < 0) {
        if (buf.length > 64 * 1024) sock.destroy();
        return;
      }
      sock.off("data", onData);
      sock.pause();
      const first = buf.subarray(0, nl).toString("utf8");
      const rest = buf.subarray(nl + 1);
      let hdr: { mode?: string; cmd?: string; args?: unknown[] };
      try { hdr = JSON.parse(first); } catch { sock.end(JSON.stringify({ out: "bad header", exit: 2 }) + "\n"); return; }
      if (hdr.mode === "mcp") void serveMcp(sock, rest);
      else if (hdr.mode === "admin") void serveAdmin(sock, String(hdr.cmd ?? ""), (hdr.args ?? []).map(String));
      else sock.end(JSON.stringify({ out: "unknown mode", exit: 2 }) + "\n");
    };
    sock.on("data", onData);
    sock.on("error", () => {});
  });

  async function serveMcp(sock: net.Socket, rest: Buffer): Promise<void> {
    clients++;
    const id = clients;
    const { server: mcp, ctx } = buildMcpServer(shared);
    let closed = false;
    const done = async () => {
      if (closed) return;
      closed = true;
      const s = ctx.sessions.finish("proxy_exit", null, true);
      if (s) await s.conn.flush().catch(() => {});
      ctx.sessions.dispose();
      await mcp.close().catch(() => {});
      logErr(`client ${id} disconnected`);
    };
    sock.on("close", () => void done());
    if (rest.length) sock.unshift(rest);
    await mcp.connect(new StdioServerTransport(sock, sock));
    sock.resume();
    logErr(`client ${id} connected`);
  }

  async function serveAdmin(sock: net.Socket, cmd: string, args: string[]): Promise<void> {
    const out = (line: string) => sock.write(JSON.stringify({ out: line }) + "\n");
    let code = 2;
    try {
      if (cmd === "status") code = await statusReport(out, shared);
      else if (cmd === "pair") code = await statusReport(out, shared, true);
      else if (cmd === "link") {
        code = await linkPhone(args[0], args[1], out, out);
        if (code === 0) shared.bridge.close(); // reconnect with the new link
      } else if (cmd === "unlink") { code = unlinkPhone(out); shared.bridge.close(); }
      else out(`unknown command: ${cmd}`);
    } catch (e) {
      out(`error: ${(e as Error).message}`);
      code = 1;
    }
    sock.end(JSON.stringify({ exit: code }) + "\n");
  }

  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(socketPath, () => resolve());
  });
  // Any local user may connect: the socket only offers the consent-gated tools, never raw adb.
  fs.chmodSync(socketPath, 0o666);
  logErr(`daemon listening on ${socketPath}`);

  const stop = () => {
    logErr("daemon stopping");
    server.close();
    shared.bridge.close();
    setTimeout(() => process.exit(0), 300).unref();
  };
  process.on("SIGTERM", stop);
  process.on("SIGINT", stop);
}

/** Client side: connect to the daemon socket and send a header line. */
export function connectDaemon(socketPath: string, header: object): Promise<net.Socket> {
  return new Promise((resolve, reject) => {
    const sock = net.createConnection(socketPath);
    sock.once("connect", () => {
      sock.write(JSON.stringify(header) + "\n");
      resolve(sock);
    });
    sock.once("error", reject);
  });
}

/** Runs an admin command in the daemon and prints its output. */
export async function daemonAdmin(socketPath: string, cmd: string, args: string[]): Promise<number> {
  const sock = await connectDaemon(socketPath, { mode: "admin", cmd, args });
  return new Promise((resolve) => {
    let buf = "";
    let code = 1;
    sock.on("data", (d) => {
      buf += d.toString("utf8");
      let nl: number;
      while ((nl = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, nl);
        buf = buf.slice(nl + 1);
        try {
          const m = JSON.parse(line);
          if (typeof m.out === "string") console.log(m.out);
          if (typeof m.exit === "number") code = m.exit;
        } catch { /* ignore */ }
      }
    });
    sock.on("close", () => resolve(code));
    sock.on("error", () => resolve(1));
  });
}

/** `serve` in secure mode: a byte pipe between the MCP client (stdio) and the daemon. */
export async function pipeToDaemon(socketPath: string): Promise<void> {
  const sock = await connectDaemon(socketPath, { mode: "mcp" });
  process.stdin.pipe(sock);
  sock.pipe(process.stdout);
  const end = () => process.exit(0);
  sock.on("close", end);
  process.stdin.on("end", () => sock.end());
}

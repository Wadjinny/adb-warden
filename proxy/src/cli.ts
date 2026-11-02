#!/usr/bin/env node
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import path from "node:path";
import { linkPhone, statusReport, unlinkPhone } from "./admin.js";
import { daemonAdmin, pipeToDaemon, runDaemon } from "./daemon.js";
import { buildMcpServer } from "./mcp.js";
import { secureInstall, secureMarker, securePair, secureStatus, secureUninstall } from "./secure.js";
import { auditPath, followAudit, formatAudit, readAuditTail } from "./audit.js";
import { configPath, logErr, VERSION } from "./config.js";

const HELP = `adb-warden ${VERSION} — MCP proxy for adb with on-phone consent

Usage:
  adb-warden [serve]                 Run the MCP server on stdio (what MCP clients launch)
  adb-warden link <code> [host[:port]]
                                     Link this computer to the phone. Get the 6-digit code in
                                     ADB Warden → Setup → Link a computer. Without host, the
                                     phone is found on the network (mDNS).
  adb-warden status                  Check the connection to the phone app and adb
  adb-warden log [-n N] [-f]         Show the audit log (default last 50 entries; -f follows)
  adb-warden unlink                  Forget the linked phone
  adb-warden secure install          Optional secure mode: a system daemon becomes the only holder of
                                     the adb key (asks for your password in the desktop's own dialog)
  adb-warden secure pair|status|uninstall

Files:
  config ${configPath()}
  audit  ${auditPath()}

Environment:
  ADB_WARDEN_ADB   path to adb (default: adb on PATH)
  ADB_WARDEN_HOST  host[:port] of the phone app, overrides the stored address`;

async function serve(): Promise<void> {
  const m = secureMarker();
  if (m) {
    // Secure mode: the daemon owns adb and the phone link. Never fall back to direct adb.
    try {
      await pipeToDaemon(m.socket);
    } catch (e) {
      logErr(`secure mode is installed but the daemon is not reachable at ${m.socket} (${(e as Error).message}). Check: systemctl status adb-warden`);
      process.exit(1);
    }
    return;
  }
  const { server, ctx } = buildMcpServer();

  let exiting = false;
  const shutdown = async (why: string) => {
    if (exiting) return;
    exiting = true;
    logErr(`shutting down (${why})`);
    const s = ctx.sessions.finish("proxy_exit", null, true);
    if (s) await s.conn.flush().catch(() => {});
    ctx.bridge.close();
    setTimeout(() => process.exit(0), 200).unref();
  };
  process.on("SIGINT", () => void shutdown("SIGINT"));
  process.on("SIGTERM", () => void shutdown("SIGTERM"));
  process.stdin.on("end", () => void shutdown("stdin closed"));
  process.stdin.on("close", () => void shutdown("stdin closed"));

  await server.connect(new StdioServerTransport());
  logErr(`MCP server ready (config ${configPath()})`);
}

const out = (l: string) => console.log(l);
const err = (l: string) => console.error(l);

async function link(code: string | undefined, hostArg: string | undefined): Promise<number> {
  const m = secureMarker();
  if (m) return daemonAdmin(m.socket, "link", [code ?? "", ...(hostArg ? [hostArg] : [])]);
  const r = await linkPhone(code, hostArg, out, err);
  if (r === 0) out(`Saved ${configPath()}.\n\nAdd the MCP server to your client, e.g. Claude Code:\n  claude mcp add adb-warden -- adb-warden serve`);
  return r;
}

async function status(): Promise<number> {
  const m = secureMarker();
  if (m) return secureStatus();
  return statusReport(out);
}

function log(args: string[]): number {
  const m = secureMarker();
  if (m) process.env.XDG_STATE_HOME = path.dirname(path.dirname(m.audit));
  let n = 50;
  const ni = args.indexOf("-n");
  if (ni >= 0) n = Math.max(1, Number(args[ni + 1]) || 50);
  for (const e of readAuditTail(n)) console.log(formatAudit(e));
  if (args.includes("-f") || args.includes("--follow")) {
    followAudit((e) => console.log(formatAudit(e)));
    return -1; // keep running
  }
  return 0;
}

async function secureCommand(sub: string | undefined): Promise<number> {
  try {
    return await secureCommandInner(sub);
  } catch (e) {
    console.error((e as Error).message);
    return 1;
  }
}

async function secureCommandInner(sub: string | undefined): Promise<number> {
  switch (sub) {
    case "install":
      return secureInstall(process.argv.includes("--dry-run"));
    case "uninstall":
      return secureUninstall();
    case "status":
      return secureStatus();
    case "pair":
      return securePair();
    default:
      console.error("Usage: adb-warden secure install | uninstall | status | pair");
      return 2;
  }
}

async function main(): Promise<void> {
  const [cmd, ...rest] = process.argv.slice(2);
  let code: number;
  switch (cmd) {
    case undefined:
    case "serve":
      await serve();
      return;
    case "link":
      code = await link(rest[0], rest[1]);
      break;
    case "status":
      code = await status();
      break;
    case "log":
    case "logs":
      code = log(rest);
      if (code < 0) return;
      break;
    case "unlink": {
      const m = secureMarker();
      code = m ? await daemonAdmin(m.socket, "unlink", []) : unlinkPhone(out);
      break;
    }
    case "daemon":
      await runDaemon();
      return;
    case "secure":
      code = await secureCommand(rest[0]);
      break;
    case "-h":
    case "--help":
    case "help":
      console.log(HELP);
      code = 0;
      break;
    case "-v":
    case "--version":
      console.log(VERSION);
      code = 0;
      break;
    default:
      console.error(`Unknown command: ${cmd}\n\n${HELP}`);
      code = 2;
  }
  process.exit(code);
}

main().catch((e) => {
  logErr((e as Error).stack || String(e));
  process.exit(1);
});

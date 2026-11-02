import fs from "node:fs";
import path from "node:path";
import { stateDir } from "./config.js";

export type AuditEntry = Record<string, unknown> & { ts?: number; kind: string };

export function auditPath(): string {
  return path.join(stateDir(), "audit.jsonl");
}

export function audit(entry: AuditEntry): void {
  try {
    fs.mkdirSync(stateDir(), { recursive: true, mode: 0o700 });
    const line = JSON.stringify({ ts: Date.now(), ...entry }) + "\n";
    fs.appendFileSync(auditPath(), line, { mode: 0o600 });
  } catch (e) {
    process.stderr.write(`[adb-warden] audit write failed: ${(e as Error).message}\n`);
  }
}

export function formatAudit(e: AuditEntry): string {
  const t = new Date(Number(e.ts) || 0).toISOString().replace("T", " ").slice(0, 19);
  const sid = typeof e.sessionId === "string" ? e.sessionId.slice(0, 8) : "--------";
  switch (e.kind) {
    case "session_start":
      return `${t} ${sid} SESSION START agent=${e.agent} reason=${JSON.stringify(e.reason)}`;
    case "session_denied":
      return `${t} ${sid} SESSION DENIED agent=${e.agent} (${e.reason})`;
    case "session_end":
      return `${t} ${sid} SESSION END (${e.reason})${e.summary ? " " + JSON.stringify(e.summary) : ""}`;
    case "action":
      return `${t} ${sid} #${e.seq} ${String(e.status).toUpperCase().padEnd(6)} ${e.summary}${e.detail ? " — " + e.detail : ""}${
        e.durationMs !== undefined ? ` (${e.durationMs} ms)` : ""
      }`;
    default:
      return `${t} ${sid} ${e.kind} ${JSON.stringify({ ...e, ts: undefined, kind: undefined })}`;
  }
}

export function readAuditTail(n: number): AuditEntry[] {
  let text: string;
  try {
    text = fs.readFileSync(auditPath(), "utf8");
  } catch {
    return [];
  }
  const lines = text.split("\n").filter(Boolean);
  return lines.slice(-n).flatMap((l) => {
    try {
      return [JSON.parse(l) as AuditEntry];
    } catch {
      return [];
    }
  });
}

export function followAudit(onEntry: (e: AuditEntry) => void): void {
  const file = auditPath();
  let pos = 0;
  try {
    pos = fs.statSync(file).size;
  } catch {
    pos = 0;
  }
  let buf = "";
  fs.watchFile(file, { interval: 500 }, (cur) => {
    if (cur.size < pos) pos = 0; // truncated
    if (cur.size === pos) return;
    const fd = fs.openSync(file, "r");
    const chunk = Buffer.alloc(cur.size - pos);
    fs.readSync(fd, chunk, 0, chunk.length, pos);
    fs.closeSync(fd);
    pos = cur.size;
    buf += chunk.toString("utf8");
    const parts = buf.split("\n");
    buf = parts.pop() ?? "";
    for (const l of parts) {
      if (!l) continue;
      try {
        onEntry(JSON.parse(l));
      } catch {
        /* ignore */
      }
    }
  });
}

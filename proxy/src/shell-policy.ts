/**
 * Read-only shell policy. Pure functions — no I/O.
 *
 * Rules (docs/PROTOCOL.md):
 *  - no `; & > < $ \` ( )` and no newlines
 *  - `|` allowed; every pipe segment must start with an allowlisted command
 *  - some commands have extra argument rules (find, top, logcat, dumpsys, ip, wm, pm, settings, date)
 *  - any mention of the ADB Warden package is denied in every shell mode (see mentionsSelf)
 */

export type ShellCheck = { ok: true } | { ok: false; reason: string };

const FORBIDDEN_CHARS = /[;&><$`()\n\r\\]/;

/** Commands allowed with any arguments (after the generic metacharacter check). */
const SIMPLE = new Set([
  "ls", "cat", "head", "tail", "wc", "grep", "egrep", "fgrep", "stat", "du", "df", "ps", "uptime",
  "id", "whoami", "uname", "getprop", "echo", "sort", "uniq", "cut", "tr", "basename", "dirname",
  "pwd", "true", "false", "nproc", "free", "printenv", "env", "which", "file", "md5sum", "sha1sum", "sha256sum",
]);

const FIND_FORBIDDEN = new Set(["-exec", "-execdir", "-delete", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls"]);
const LOGCAT_FORBIDDEN = new Set(["-c", "--clear", "-f", "--file", "-G", "--buffer-size", "-P", "--prune", "-r", "--rotate-kbytes", "-n", "--rotate-count"]);
const DUMPSYS_FORBIDDEN = new Set([
  "set", "unplug", "reset", "enable", "disable", "force-idle", "unforce", "step", "whitelist", "tempwhitelist",
  "except-idle-whitelist", "sys-whitelist", "put", "delete", "clear", "kill", "force", "start", "stop", "light", "deep",
]);
const IP_FORBIDDEN = new Set(["add", "del", "delete", "flush", "change", "replace", "set", "append", "prepend", "save", "restore"]);
const IP_OBJECTS = new Set(["addr", "address", "a", "route", "r", "ro", "link", "l", "neigh", "n", "rule"]);

/** Split a command line into tokens, honouring simple single/double quotes. */
export function tokenize(segment: string): string[] | null {
  const out: string[] = [];
  let cur = "";
  let quote: '"' | "'" | null = null;
  let has = false;
  for (const ch of segment) {
    if (quote) {
      if (ch === quote) quote = null;
      else cur += ch;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      has = true;
      continue;
    }
    if (ch === " " || ch === "\t") {
      if (has || cur) out.push(cur);
      cur = "";
      has = false;
      continue;
    }
    cur += ch;
    has = true;
  }
  if (quote) return null;
  if (has || cur) out.push(cur);
  return out;
}

/** Split on `|` outside quotes. Returns null when quotes are unbalanced. */
export function splitPipes(cmd: string): string[] | null {
  const parts: string[] = [];
  let cur = "";
  let quote: string | null = null;
  for (const ch of cmd) {
    if (quote) {
      if (ch === quote) quote = null;
      cur += ch;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      cur += ch;
      continue;
    }
    if (ch === "|") {
      parts.push(cur);
      cur = "";
      continue;
    }
    cur += ch;
  }
  if (quote) return null;
  parts.push(cur);
  return parts;
}

export function mentionsSelf(cmd: string): boolean {
  return cmd.toLowerCase().replace(/\s+/g, "").includes("adbwarden");
}

function checkSegment(tokens: string[]): ShellCheck {
  const [cmd, ...args] = tokens;
  const deny = (why: string): ShellCheck => ({ ok: false, reason: why });
  if (!cmd) return deny("empty command in pipeline");
  if (cmd.includes("/")) return deny(`"${cmd}": use the bare command name, paths are not allowed in read-only mode`);

  if (SIMPLE.has(cmd)) return { ok: true };

  switch (cmd) {
    case "find": {
      const bad = args.find((a) => FIND_FORBIDDEN.has(a));
      return bad ? deny(`find ${bad} is not read-only`) : { ok: true };
    }
    case "top":
      return args.some((a) => a === "-n" || /^-n\d+$/.test(a) || /^-[a-zA-Z]*n$/.test(a))
        ? { ok: true }
        : deny("top needs -n <count> (otherwise it never ends)");
    case "date":
      return args.some((a) => a === "-s" || a.startsWith("--set")) ? deny("date -s changes the clock") : { ok: true };
    case "logcat": {
      if (!args.includes("-d")) return deny("logcat needs -d (dump and exit)");
      const bad = args.find((a) => LOGCAT_FORBIDDEN.has(a) || /^--(clear|file|buffer-size|prune)=/.test(a));
      return bad ? deny(`logcat ${bad} is not read-only`) : { ok: true };
    }
    case "dumpsys": {
      const bad = args.find((a) => DUMPSYS_FORBIDDEN.has(a.toLowerCase()));
      return bad ? deny(`dumpsys ... ${bad} can change device state`) : { ok: true };
    }
    case "pm":
      return args[0] === "list" || args[0] === "path" ? { ok: true } : deny(`only "pm list" and "pm path" are read-only`);
    case "settings":
      return args[0] === "get" || args[0] === "list" ? { ok: true } : deny(`only "settings get" and "settings list" are read-only`);
    case "wm":
      return (args[0] === "size" || args[0] === "density") && args.length === 1
        ? { ok: true }
        : deny(`only "wm size" and "wm density" without arguments are read-only`);
    case "ip": {
      const words = args.filter((a) => !a.startsWith("-"));
      if (!words.length || !IP_OBJECTS.has(words[0])) return deny("only ip addr / route / link / neigh / rule (show) are allowed");
      const bad = words.find((w) => IP_FORBIDDEN.has(w));
      return bad ? deny(`ip ... ${bad} changes network state`) : { ok: true };
    }
    case "cmd":
      return args[0] === "battery" && args[1] === "get" ? { ok: true } : deny(`only "cmd battery get" is read-only among cmd services`);
    default:
      return deny(`"${cmd}" is not on the read-only allowlist`);
  }
}

export function checkReadonlyShell(command: string): ShellCheck {
  const cmd = command.trim();
  if (!cmd) return { ok: false, reason: "empty command" };
  const bad = cmd.match(FORBIDDEN_CHARS);
  if (bad) {
    const shown = bad[0] === "\n" || bad[0] === "\r" ? "newline" : `"${bad[0]}"`;
    return { ok: false, reason: `${shown} is not allowed in read-only mode (no ; & > < $ \` ( ) \\ or newlines)` };
  }
  const segments = splitPipes(cmd);
  if (!segments) return { ok: false, reason: "unbalanced quotes" };
  for (const seg of segments) {
    const tokens = tokenize(seg.trim());
    if (!tokens) return { ok: false, reason: "unbalanced quotes" };
    const r = checkSegment(tokens);
    if (!r.ok) return r;
  }
  return { ok: true };
}

import { SELF_PACKAGE } from "./config.js";

export type ShellMode = "off" | "readonly" | "any";
export type AppFilter = "off" | "blocklist" | "allowlist";

export interface Policy {
  v: number;
  screen: boolean;
  input: boolean;
  apps: boolean;
  install: boolean;
  shell: ShellMode;
  appFilter: AppFilter;
  packages: string[];
  idleTimeoutSec: number;
  /** The phone keeps wireless debugging off outside sessions and turns it on when a session is approved. */
  adbLock: boolean;
}

/** Fail-closed policy used when nothing has been received from the phone. */
export const LOCKED_POLICY: Policy = {
  v: 1,
  screen: false,
  input: false,
  apps: false,
  install: false,
  shell: "off",
  appFilter: "allowlist",
  packages: [],
  idleTimeoutSec: 300,
  adbLock: false,
};

export function normalizePolicy(raw: unknown): Policy {
  const p = (raw && typeof raw === "object" ? raw : {}) as Record<string, unknown>;
  const shell: ShellMode = p.shell === "readonly" || p.shell === "any" ? p.shell : "off";
  const appFilter: AppFilter = p.appFilter === "blocklist" || p.appFilter === "allowlist" || p.appFilter === "off" ? p.appFilter : "allowlist";
  const idle = Number(p.idleTimeoutSec);
  return {
    v: Number(p.v) || 1,
    screen: p.screen === true,
    input: p.input === true,
    apps: p.apps === true,
    install: p.install === true,
    shell,
    appFilter,
    packages: Array.isArray(p.packages) ? p.packages.filter((x): x is string => typeof x === "string") : [],
    idleTimeoutSec: Number.isFinite(idle) ? Math.min(3600, Math.max(60, Math.round(idle))) : 300,
    adbLock: p.adbLock === true,
  };
}

export type Category = "screen" | "input" | "apps" | "install" | "shell" | "none";

export const TOOL_CATEGORY: Record<string, Category> = {
  start_session: "none",
  end_session: "none",
  status: "none",
  screenshot: "screen",
  ui_tree: "screen",
  current_app: "screen",
  tap: "input",
  long_press: "input",
  swipe: "input",
  type_text: "input",
  press_key: "input",
  launch_app: "apps",
  list_apps: "apps",
  install_app: "install",
  shell: "shell",
};

const CATEGORY_LABEL: Record<Category, string> = {
  screen: "Screen (screenshots and reading the screen)",
  input: "Input (tapping, swiping, typing, keys)",
  apps: "Apps (launching and listing apps)",
  install: "Install apps",
  shell: "Shell",
  none: "",
};

export const RULES_HINT = "The phone's owner controls these rules in the ADB Warden app (Rules tab).";

export function denial(rule: string): string {
  return `Denied by user policy: ${rule}. ${RULES_HINT}`;
}

/** Returns a denial message, or null if the category is allowed. */
export function checkCategory(policy: Policy, tool: string): string | null {
  const cat = TOOL_CATEGORY[tool] ?? "none";
  switch (cat) {
    case "none":
      return null;
    case "shell":
      return policy.shell === "off" ? denial(`"${CATEGORY_LABEL.shell}" is turned off`) : null;
    default:
      return policy[cat] ? null : denial(`"${CATEGORY_LABEL[cat]}" is turned off`);
  }
}

export interface PackageContext {
  /** Home launcher package, implicitly allowed in allowlist mode. */
  homePackage?: string | null;
  /** "foreground" = checking the app on screen, "target" = an app the agent wants to open. */
  role: "foreground" | "target";
}

const ALWAYS_ALLOWED_IN_ALLOWLIST = new Set(["com.android.systemui"]);

/** Returns a denial message, or null if the package may be used. */
export function checkPackage(policy: Policy, pkg: string | null, ctx: PackageContext): string | null {
  if (pkg === SELF_PACKAGE) {
    return ctx.role === "foreground"
      ? denial("the ADB Warden app is on screen, and agents may never see or touch it (only press_key home works here)")
      : denial("agents may never open the ADB Warden app");
  }
  if (!pkg) {
    // Unknown foreground: only block when an allowlist is active.
    return policy.appFilter === "allowlist" && ctx.role === "foreground"
      ? denial("the app on screen could not be identified and an app allowlist is active")
      : null;
  }
  const listed = policy.packages.includes(pkg);
  const what = ctx.role === "foreground" ? `the app on screen (${pkg})` : `the app ${pkg}`;
  if (policy.appFilter === "blocklist" && listed) return denial(`${what} is on the blocklist`);
  if (policy.appFilter === "allowlist" && !listed) {
    if (ALWAYS_ALLOWED_IN_ALLOWLIST.has(pkg)) return null;
    if (ctx.homePackage && pkg === ctx.homePackage) return null;
    return denial(`${what} is not on the allowlist`);
  }
  return null;
}

// ---- parsing helpers for adb output (pure, unit-tested) ----

const COMPONENT_RE = /\b(?:u\d+\s+)([A-Za-z][\w.]*)\/([\w.$]+)/;

export interface Foreground {
  pkg: string;
  activity: string;
}

/** Parse `dumpsys activity activities` (topResumedActivity / mResumedActivity / ResumedActivity). */
export function parseResumedActivity(text: string): Foreground | null {
  const lines = text.split("\n");
  const keys = ["topResumedActivity", "mResumedActivity", "ResumedActivity"];
  for (const key of keys) {
    for (const line of lines) {
      if (!line.includes(key)) continue;
      const m = line.match(COMPONENT_RE);
      if (m) return { pkg: m[1], activity: m[2] };
    }
  }
  return null;
}

/** Parse `dumpsys window` mCurrentFocus / mFocusedApp. */
export function parseWindowFocus(text: string): Foreground | null {
  for (const key of ["mCurrentFocus", "mFocusedApp"]) {
    for (const line of text.split("\n")) {
      if (!line.includes(key)) continue;
      const m = line.match(COMPONENT_RE);
      if (m) return { pkg: m[1], activity: m[2] };
      const m2 = line.match(/\s([A-Za-z][\w]*(?:\.[\w]+)+)\/([\w.$]+)/);
      if (m2) return { pkg: m2[1], activity: m2[2] };
    }
  }
  return null;
}

/** Parse `cmd package resolve-activity --brief ... HOME` → package of the last "pkg/activity" line. */
export function parseHomePackage(text: string): string | null {
  const lines = text
    .split("\n")
    .map((l) => l.trim())
    .filter(Boolean)
    .reverse();
  for (const l of lines) {
    const m = l.match(/^([A-Za-z][\w]*(?:\.[\w]+)+)\/[\w.$]+$/);
    if (m) return m[1];
  }
  return null;
}

export const PACKAGE_RE = /^[A-Za-z][\w]*(\.[A-Za-z_][\w]*)+$/;

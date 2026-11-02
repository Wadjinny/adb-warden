import { audit } from "./audit.js";
import { BridgeConnection, BridgeError, BridgeManager } from "./bridge.js";
import { logErr } from "./config.js";
import { RULES_HINT } from "./policy.js";

export class SessionDenied extends Error {}

const DENY_MESSAGES: Record<string, string> = {
  denied: "The user denied control of the phone.",
  busy: "Another agent is already controlling the phone. Try again when it has finished.",
  timeout: "Nobody answered the consent prompt on the phone within 60 seconds.",
  cooldown: "The user recently denied or stopped a session from this computer. Wait at least 20 seconds before asking again, and only if the user wants you to.",
};

export interface ActiveSession {
  id: string;
  agent: string;
  reason: string;
  startedAt: number;
  seq: number;
  actions: number;
  denied: number;
  conn: BridgeConnection;
  /** App on screen when the session started (undefined = not checked yet, null = unknown). */
  startApp?: { pkg: string; activity: string } | null;
  /** Packages opened with launch_app during this session. */
  opened: string[];
  /** end_session already refused once because the phone was not back where it started. */
  restoreWarned?: boolean;
  /** The start app was the home launcher. */
  startHome?: boolean;
}

export type EndReason = "agent_ended" | "idle_timeout" | "proxy_exit" | "stopped_by_user" | "proxy_disconnected";

export class SessionManager {
  private active: ActiveSession | null = null;
  private starting: Promise<ActiveSession> | null = null;
  private idleTimer?: NodeJS.Timeout;
  lastEnd: { reason: EndReason; at: number } | null = null;

  private onStop = (sessionId: string) => {
    if (this.active && this.active.id === sessionId) this.finish("stopped_by_user", null, false);
  };
  private onDisconnected = () => {
    if (this.active) this.finish("proxy_disconnected", null, false);
  };
  private onPolicy = () => this.touch();

  constructor(private bridge: BridgeManager, private agentName: () => string) {
    bridge.on("stop", this.onStop);
    bridge.on("disconnected", this.onDisconnected);
    bridge.on("policy", this.onPolicy);
  }

  /** Detach from a shared bridge (daemon mode, one SessionManager per MCP client). */
  dispose(): void {
    clearTimeout(this.idleTimer);
    this.bridge.off("stop", this.onStop);
    this.bridge.off("disconnected", this.onDisconnected);
    this.bridge.off("policy", this.onPolicy);
  }

  get current(): ActiveSession | null {
    return this.active;
  }

  /** Returns the active session, asking the phone for consent if needed. */
  async ensure(reason?: string): Promise<ActiveSession> {
    if (this.active && this.active.conn.isOpen) return this.active;
    if (!this.starting) {
      this.starting = this.request(reason || "(no reason given)").finally(() => {
        this.starting = null;
      });
    }
    return this.starting;
  }

  private async request(reason: string): Promise<ActiveSession> {
    const conn = await this.bridge.ensure();
    const agent = this.agentName();
    let reply: Record<string, unknown>;
    try {
      reply = await conn.request("session_request", { agent, reason }, 75_000);
    } catch (e) {
      if (e instanceof BridgeError && e.code === "timeout") {
        audit({ kind: "session_denied", agent, reason: "timeout", requestReason: reason });
        throw new SessionDenied(DENY_MESSAGES.timeout);
      }
      throw e;
    }
    if (reply.approved !== true || typeof reply.sessionId !== "string") {
      const why = String(reply.reason ?? "denied");
      audit({ kind: "session_denied", agent, reason: why, requestReason: reason });
      throw new SessionDenied(`${DENY_MESSAGES[why] ?? `The phone refused the session (${why}).`} ${RULES_HINT.replace("these rules", "access")}`);
    }
    const s: ActiveSession = {
      id: reply.sessionId,
      agent,
      reason,
      startedAt: Date.now(),
      seq: 0,
      actions: 0,
      denied: 0,
      conn,
      opened: [],
    };
    this.active = s;
    audit({ kind: "session_start", sessionId: s.id, agent, reason, host: conn.host, device: conn.hello?.deviceName });
    logErr(`session ${s.id} approved`);
    this.touch();
    return s;
  }

  /** Reset the idle timer (called on each tool call and on policy change). */
  touch(): void {
    if (this.idleTimer) clearTimeout(this.idleTimer);
    if (!this.active) return;
    const ms = this.bridge.policy.idleTimeoutSec * 1000;
    this.idleTimer = setTimeout(() => {
      logErr("session idle timeout");
      this.finish("idle_timeout", null, true);
    }, ms);
    this.idleTimer.unref();
  }

  record(tool: string, summary: string, status: "ok" | "denied" | "error", detail: string | null, durationMs: number): void {
    const s = this.active;
    const entry = { tool, summary, status, detail, durationMs };
    if (!s) {
      audit({ kind: "action", sessionId: null, seq: 0, ...entry });
      return;
    }
    s.seq += 1;
    s.actions += 1;
    if (status === "denied") s.denied += 1;
    audit({ kind: "action", sessionId: s.id, seq: s.seq, ...entry });
    s.conn.send({ t: "action", sessionId: s.id, seq: s.seq, ts: Date.now(), tool, summary, status, detail, durationMs });
  }

  /** End the session. `notify` = send session_end to the phone (not needed when the phone ended it). */
  finish(reason: EndReason, summary: string | null, notify = true): ActiveSession | null {
    const s = this.active;
    if (!s) return null;
    this.active = null;
    if (this.idleTimer) clearTimeout(this.idleTimer);
    if (notify && (reason === "agent_ended" || reason === "idle_timeout" || reason === "proxy_exit")) {
      s.conn.send({ t: "session_end", sessionId: s.id, reason, summary });
    }
    this.lastEnd = { reason, at: Date.now() };
    audit({ kind: "session_end", sessionId: s.id, reason, summary, actions: s.actions, denied: s.denied, durationMs: Date.now() - s.startedAt });
    logErr(`session ${s.id} ended (${reason})`);
    return s;
  }

  /** Message for a call that arrives right after the user pressed Stop. */
  stoppedHint(): string | null {
    if (this.lastEnd?.reason === "stopped_by_user" && Date.now() - this.lastEnd.at < 20_000) {
      return "The user pressed Stop on the phone. Do not continue unless the user asks you to.";
    }
    return null;
  }
}

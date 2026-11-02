import { test } from "node:test";
import assert from "node:assert/strict";
import net from "node:net";
import os from "node:os";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";

process.env.XDG_STATE_HOME = fs.mkdtempSync(path.join(os.tmpdir(), "adbw-state-"));

const { BridgeConnection, BridgeManager } = await import("../src/bridge.js");
const { fakeApp } = await import("./fake-app.js");
const { SessionManager } = await import("../src/session.js");

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

test("link then auth handshake", async () => {
  const app = await fakeApp({ token: "tok-abc", computerId: "comp-1", linkCode: "123456" });
  try {
    await assert.rejects(BridgeConnection.link("127.0.0.1", app.port, "000000"), (e: any) => e.code === "bad_link_code");
    const info = await BridgeConnection.link("127.0.0.1", app.port, "123456");
    assert.deepEqual(info, { computerId: "comp-1", token: "tok-abc", deviceId: "dev-1", deviceName: "Fake Phone" });
    const conn = await BridgeConnection.connectAuth("127.0.0.1", app.port, info);
    assert.equal(conn.hello?.deviceName, "Fake Phone");
    assert.deepEqual(conn.device?.ips, ["10.0.0.5"]);
    assert.equal(conn.policy.shell, "readonly");
    conn.close();
    await assert.rejects(BridgeConnection.connectAuth("127.0.0.1", app.port, { computerId: "comp-1", token: "wrong" }), (e: any) => e.code === "bad_auth");
  } finally {
    app.close();
  }
});

test("session request, actions, policy push, stop", async () => {
  const app = await fakeApp({ token: "tok", computerId: "c1" });
  const cfg = { computerId: "c1", token: "tok", deviceId: "dev-1", deviceName: "Fake Phone", host: "127.0.0.1", port: app.port };
  const bridge = new BridgeManager(() => cfg);
  const sessions = new SessionManager(bridge, () => "test-agent 1.0");
  try {
    const s = await sessions.ensure("testing");
    assert.equal(s.id, "sess-1");
    const req = app.received.find((m) => m.t === "session_request")!;
    assert.equal(req.agent, "test-agent 1.0");
    assert.equal(req.reason, "testing");
    assert.equal(typeof req.id, "number");

    sessions.record("tap", "Tap (1, 2)", "ok", null, 5);
    sessions.record("shell", "Shell: rm", "denied", "nope", 1);
    await wait(50);
    const actions = app.received.filter((m) => m.t === "action");
    assert.equal(actions.length, 2);
    assert.deepEqual([actions[0].seq, actions[0].status, actions[0].sessionId], [1, "ok", "sess-1"]);
    assert.equal(actions[1].status, "denied");

    app.push({ t: "policy", policy: { v: 1, screen: false, input: false, apps: false, install: false, shell: "off", appFilter: "off", packages: [], idleTimeoutSec: 120 } });
    await wait(50);
    assert.equal(bridge.policy.screen, false);
    assert.equal(bridge.policy.idleTimeoutSec, 120);

    app.push({ t: "stop", sessionId: "sess-1" });
    await wait(50);
    assert.equal(sessions.current, null);
    assert.match(sessions.stoppedHint()!, /pressed Stop/);

    // A fresh session after stop, then an agent-ended session sends session_end.
    await sessions.ensure("again");
    sessions.finish("agent_ended", "all done");
    await wait(50);
    const end = app.received.find((m) => m.t === "session_end")!;
    assert.deepEqual([end.sessionId, end.reason, end.summary], ["sess-1", "agent_ended", "all done"]);
  } finally {
    bridge.close();
    app.close();
  }
});

test("denied session", async () => {
  const app = await fakeApp({ token: "tok", computerId: "c1", approve: false });
  const bridge = new BridgeManager(() => ({ computerId: "c1", token: "tok", deviceId: "dev-1", deviceName: "", host: "127.0.0.1", port: app.port }));
  const sessions = new SessionManager(bridge, () => "a");
  try {
    await assert.rejects(sessions.ensure("x"), /denied control/);
  } finally {
    bridge.close();
    app.close();
  }
});

test("audit log was written", () => {
  const file = path.join(process.env.XDG_STATE_HOME!, "adb-warden", "audit.jsonl");
  const kinds = fs.readFileSync(file, "utf8").trim().split("\n").map((l) => JSON.parse(l).kind);
  assert.ok(kinds.includes("session_start"));
  assert.ok(kinds.includes("action"));
  assert.ok(kinds.includes("session_end"));
  assert.ok(kinds.includes("session_denied"));
});

test("parseAvahi reads resolved IPv4 entries", async () => {
  const { parseAvahi } = await import("../src/bridge.js");
  const out = [
    '+;wlo1;IPv4;ADB\\032Warden\\032SM-A305F;_adbwarden._tcp;local',
    '=;wlo1;IPv6;ADB\\032Warden\\032SM-A305F;_adbwarden._tcp;local;Android.local;fe80::1;47800;"id=abc" "v=1"',
    '=;wlo1;IPv4;ADB\\032Warden\\032SM-A305F;_adbwarden._tcp;local;Android.local;192.168.0.31;47800;"id=abc" "v=1"',
  ].join("\n");
  assert.deepEqual(parseAvahi(out), [{ host: "192.168.0.31", port: 47800, id: "abc", name: "ADB Warden SM-A305F" }]);
});

import { test } from "node:test";
import assert from "node:assert/strict";
import os from "node:os";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "adbw-tools-"));
process.env.XDG_STATE_HOME = tmp;
process.env.XDG_CONFIG_HOME = tmp;
const here = path.dirname(fileURLToPath(import.meta.url));
process.env.ADB_WARDEN_ADB = path.resolve(here, "../../test/fake-adb.sh");
process.env.FAKE_ADB_LOG = path.join(tmp, "adb.log");

const { McpServer } = await import("@modelcontextprotocol/sdk/server/mcp.js");
const { Client } = await import("@modelcontextprotocol/sdk/client/index.js");
const { InMemoryTransport } = await import("@modelcontextprotocol/sdk/inMemory.js");
const { createContext, registerTools } = await import("../src/tools.js");
const { saveConfig } = await import("../src/config.js");
const { fakeApp } = await import("./fake-app.js");

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

test("tool calls go through consent, policy and the event stream", async () => {
  const app = await fakeApp({
    token: "tok",
    computerId: "c1",
    policy: { v: 1, screen: true, input: true, apps: true, install: false, shell: "readonly", appFilter: "blocklist", packages: ["com.bank"], idleTimeoutSec: 300 },
  });
  saveConfig({ computerId: "c1", token: "tok", deviceId: "dev-1", deviceName: "Fake Phone", host: "127.0.0.1", port: app.port });
  const server = new McpServer({ name: "adb-warden", version: "test" });
  const ctx = createContext(() => {
    const v = server.server.getClientVersion();
    return v ? `${v.name} ${v.version}` : "?";
  });
  registerTools(server, ctx);
  const [a, b] = InMemoryTransport.createLinkedPair();
  await server.connect(a);
  const client = new Client({ name: "itest", version: "9.9" });
  await client.connect(b);
  const call = async (name: string, args: Record<string, unknown> = {}) => {
    const r: any = await client.callTool({ name, arguments: args });
    return { err: r.isError === true, text: r.content?.[0]?.text as string };
  };
  try {
    let r = await call("tap", { x: 10, y: 20 });
    assert.equal(r.err, false, r.text);
    assert.equal(app.received.find((m) => m.t === "session_request")!.agent, "itest 9.9");
    assert.ok(fs.readFileSync(process.env.FAKE_ADB_LOG!, "utf8").includes("-s 10.0.0.5:5555 shell input tap 10 20"));

    r = await call("install_app", { apkPath: "/tmp/x.apk" });
    assert.equal(r.err, true);
    assert.match(r.text, /Install apps.*turned off.*ADB Warden app/);

    r = await call("shell", { command: "rm -rf /sdcard" });
    assert.match(r.text, /read-only/);
    r = await call("shell", { command: "ls /sdcard" });
    assert.equal(r.err, false, r.text);
    assert.equal(r.text, "Download");
    r = await call("shell", { command: "am force-stop com.wadjinny.adbwarden" });
    assert.match(r.text, /may not touch the ADB Warden app/);

    r = await call("launch_app", { package: "com.bank" });
    assert.match(r.text, /blocklist/);
    r = await call("launch_app", { package: "com.wadjinny.adbwarden" });
    assert.match(r.text, /never open/);

    process.env.FAKE_FG = "com.bank";
    r = await call("tap", { x: 1, y: 1 });
    assert.match(r.text, /app on screen \(com\.bank\) is on the blocklist/);
    process.env.FAKE_FG = "com.wadjinny.adbwarden";
    r = await call("screenshot");
    assert.match(r.text, /ADB Warden app is on screen/);
    r = await call("press_key", { key: "home" });
    assert.equal(r.err, false, r.text);
    delete process.env.FAKE_FG;

    r = await call("list_apps");
    assert.equal(r.text, "com.android.chrome\ncom.bank (blocked by user rules)");

    r = await call("launch_app", { package: "com.android.chrome" });
    assert.match(r.text, /Opened com\.android\.chrome\. .*return to com\.android\.settings \(launch_app com\.android\.settings\)/);

    // The phone is still on the app the agent opened: the first end_session is refused with instructions.
    process.env.FAKE_FG = "com.android.chrome";
    r = await call("end_session", { summary: "done" });
    assert.equal(r.err, true);
    assert.match(r.text, /Not ended yet: the phone is on com\.android\.chrome.*was on com\.android\.settings.*You opened: com\.android\.chrome/);
    // A second call ends it anyway.
    r = await call("end_session", { summary: "done" });
    assert.match(r.text, /Session ended/);
    delete process.env.FAKE_FG;
    await wait(50);
    const actions = app.received.filter((m) => m.t === "action");
    assert.deepEqual(
      actions.map((m) => `${m.seq}:${m.tool}:${m.status}`),
      ["1:tap:ok", "2:install_app:denied", "3:shell:denied", "4:shell:ok", "5:shell:denied", "6:launch_app:denied", "7:launch_app:denied", "8:tap:denied", "9:screenshot:denied", "10:press_key:ok", "11:list_apps:ok", "12:launch_app:ok"],
    );
    assert.equal(app.received.filter((m) => m.t === "session_end").length, 1);
  } finally {
    await client.close();
    ctx.bridge.close();
    app.close();
  }
});

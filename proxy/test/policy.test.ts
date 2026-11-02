import { test } from "node:test";
import assert from "node:assert/strict";
import {
  checkCategory, checkPackage, LOCKED_POLICY, normalizePolicy, parseHomePackage, parseResumedActivity, parseWindowFocus, Policy,
} from "../src/policy.js";
import { parseDevices, parseIpv4s, parseMdnsServices } from "../src/adb.js";

const base: Policy = { v: 1, screen: true, input: true, apps: true, install: false, shell: "readonly", appFilter: "blocklist", packages: ["com.bank"], idleTimeoutSec: 300, adbLock: false };

test("normalizePolicy fails closed", () => {
  assert.deepEqual(normalizePolicy(undefined), LOCKED_POLICY);
  const p = normalizePolicy({ screen: "yes", shell: "root", idleTimeoutSec: 5, packages: ["a.b", 3] });
  assert.equal(p.screen, false);
  assert.equal(p.shell, "off");
  assert.equal(p.idleTimeoutSec, 60);
  assert.deepEqual(p.packages, ["a.b"]);
  assert.deepEqual(normalizePolicy(base), base);
});

test("category checks", () => {
  assert.equal(checkCategory(base, "tap"), null);
  assert.equal(checkCategory(base, "status"), null);
  assert.match(checkCategory(base, "install_app")!, /Install apps.*turned off.*ADB Warden app/);
  assert.match(checkCategory({ ...base, shell: "off" }, "shell")!, /Shell/);
  assert.equal(checkCategory({ ...base, shell: "any" }, "shell"), null);
  assert.match(checkCategory({ ...base, screen: false }, "screenshot")!, /Screen/);
});

test("package checks", () => {
  const fg = { role: "foreground" as const, homePackage: "com.sec.android.app.launcher" };
  const tgt = { role: "target" as const, homePackage: "com.sec.android.app.launcher" };
  assert.match(checkPackage(base, "com.wadjinny.adbwarden", fg)!, /ADB Warden app is on screen/);
  assert.match(checkPackage({ ...base, appFilter: "off" }, "com.wadjinny.adbwarden", tgt)!, /never open/);
  assert.match(checkPackage(base, "com.bank", fg)!, /blocklist/);
  assert.equal(checkPackage(base, "com.other", fg), null);
  assert.equal(checkPackage(base, null, fg), null);
  const allow: Policy = { ...base, appFilter: "allowlist", packages: ["com.android.chrome"] };
  assert.equal(checkPackage(allow, "com.android.chrome", tgt), null);
  assert.match(checkPackage(allow, "com.other", tgt)!, /not on the allowlist/);
  assert.equal(checkPackage(allow, "com.sec.android.app.launcher", fg), null);
  assert.equal(checkPackage(allow, "com.android.systemui", fg), null);
  assert.match(checkPackage(allow, null, fg)!, /could not be identified/);
});

test("foreground and home parsers", () => {
  assert.deepEqual(
    parseResumedActivity("  topResumedActivity=ActivityRecord{c0ffee u0 com.android.settings/.Settings t42}\n  ResumedActivity: ActivityRecord{x u0 com.other/.A t1}"),
    { pkg: "com.android.settings", activity: ".Settings" },
  );
  assert.deepEqual(parseResumedActivity("    mResumedActivity: ActivityRecord{1a2b u0 com.android.chrome/com.google.android.apps.chrome.Main t9}"), {
    pkg: "com.android.chrome",
    activity: "com.google.android.apps.chrome.Main",
  });
  assert.equal(parseResumedActivity("nothing"), null);
  assert.deepEqual(parseWindowFocus("  mCurrentFocus=Window{abc u0 com.whatsapp/com.whatsapp.Main}"), { pkg: "com.whatsapp", activity: "com.whatsapp.Main" });
  assert.equal(parseHomePackage("priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\ncom.sec.android.app.launcher/.activities.LauncherActivity\n"), "com.sec.android.app.launcher");
  assert.equal(parseHomePackage("No activity found"), null);
});

test("adb output parsers", () => {
  const devs = parseDevices(`List of devices attached
R58M3837YTE            device usb:1-4 product:a30dd model:SM_A305F device:a30 transport_id:1
192.168.0.16:42279     device product:e3quew model:SM_S928U1 device:e3q transport_id:4
emulator-5554 offline
`);
  assert.equal(devs.length, 3);
  assert.deepEqual(devs[0], { serial: "R58M3837YTE", state: "device", model: "SM_A305F" });
  assert.equal(devs[2].state, "offline");
  const svcs = parseMdnsServices(`List of discovered mdns services
adb-R5CX71QXP9V-ridjwY	_adb-tls-connect._tcp	192.168.0.16:42279
adb-R5CX71QXP9V-ridjwY	_adb-tls-pairing._tcp.	192.168.0.16:37155
`);
  assert.deepEqual(svcs, [
    { name: "adb-R5CX71QXP9V-ridjwY", type: "_adb-tls-connect._tcp", host: "192.168.0.16", port: 42279 },
    { name: "adb-R5CX71QXP9V-ridjwY", type: "_adb-tls-pairing._tcp", host: "192.168.0.16", port: 37155 },
  ]);
  assert.deepEqual(parseIpv4s("1: lo    inet 127.0.0.1/8 scope host lo\n30: wlan0    inet 192.168.0.12/24 brd 192.168.0.255 scope global wlan0"), ["192.168.0.12"]);
});

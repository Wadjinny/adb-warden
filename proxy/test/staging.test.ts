import { test } from "node:test";
import assert from "node:assert/strict";
import path from "node:path";

// The staging helpers live in tools.ts so install_app and status can use them directly.
const { missingApkMessage, stageDir } = await import("../src/tools.js");

test("missing APK explains invisibility and names the staging recipe", () => {
  const abs = "/home/user/Downloads/movy-debug.apk";

  const daemon = missingApkMessage(abs, { secureDaemon: true, stage: "/dev/shm/adb-warden" });
  assert.match(daemon, /^No such file: \/home\/user\/Downloads\/movy-debug\.apk\n/);
  assert.match(daemon, /secure-mode daemon|daemon user in a hardened sandbox/);
  assert.match(daemon, /\/home, \/tmp and \/var\/tmp/);
  assert.match(daemon, /cp \/home\/user\/Downloads\/movy-debug\.apk \/dev\/shm\/adb-warden\/\n/);
  assert.match(daemon, /install_app \/dev\/shm\/adb-warden\/movy-debug\.apk/);

  const remote = missingApkMessage(abs, { secureDaemon: false, stage: "/dev/shm/adb-warden" });
  assert.match(remote, /different machine or container/);
  assert.match(remote, /install_app \/dev\/shm\/adb-warden\/movy-debug\.apk/);

  const noDir = missingApkMessage(abs, { secureDaemon: false, stage: null });
  assert.match(noDir, /No shared staging directory|a location ADB Warden can read/);
  assert.doesNotMatch(noDir, /^ {2}cp /);

  const noDirDaemon = missingApkMessage(abs, { secureDaemon: true, stage: null });
  assert.match(noDirDaemon, /hardened sandbox/);
});

test("stageDir answers with a shared staging path or null", () => {
  const dir = stageDir();
  assert.ok(dir === null || dir === "/dev/shm" || dir === path.join("/dev/shm", "adb-warden"), String(dir));
});

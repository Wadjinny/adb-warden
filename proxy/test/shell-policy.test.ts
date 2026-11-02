import { test } from "node:test";
import assert from "node:assert/strict";
import { checkReadonlyShell, mentionsSelf, tokenize } from "../src/shell-policy.js";

const ok = (c: string) => assert.equal(checkReadonlyShell(c).ok, true, `expected allowed: ${c}`);
const no = (c: string) => assert.equal(checkReadonlyShell(c).ok, false, `expected denied: ${c}`);

test("allows read-only commands and pipes", () => {
  ok("ls -la /sdcard");
  ok("getprop ro.build.version.release");
  ok("dumpsys battery");
  ok("dumpsys battery | grep level");
  ok("pm list packages -3");
  ok("pm path com.android.chrome");
  ok("settings get global airplane_mode_on");
  ok("wm size");
  ok("logcat -d -t 100");
  ok("top -n 1");
  ok("ip addr show wlan0");
  ok("ip -4 route");
  ok("find /sdcard/Download -name '*.pdf'");
  ok("cat /proc/meminfo | head -5 | tail -2");
  ok('echo "a b c"');
  ok("cmd battery get level");
});

test("denies writes, metacharacters and unknown commands", () => {
  no("rm -rf /sdcard");
  no("ls; rm x");
  no("ls && rm x");
  no("echo hi > /sdcard/x");
  no("cat < /sdcard/x");
  no("echo $HOME");
  no("echo `id`");
  no("echo $(id)");
  no("ls\nrm x");
  no("ls | sh");
  no("ls |");
  no("input tap 1 1");
  no("am start -n x/y");
  no("pm uninstall com.x");
  no("pm clear com.x");
  no("settings put global x 1");
  no("wm size 100x100");
  no("wm density 200");
  no("logcat");
  no("logcat -d -c");
  no("logcat -d -f /sdcard/x");
  no("top");
  no("find / -delete");
  no("find / -exec rm {} \;");
  no("dumpsys battery set level 5");
  no("dumpsys deviceidle force-idle");
  no("ip link set wlan0 down");
  no("ip route add 1.2.3.4 dev wlan0");
  no("/system/bin/rm x");
  no("date -s 20200101");
  no("cmd package uninstall x");
  no("echo 'unbalanced");
  no("");
});

test("self mention detection", () => {
  assert.equal(mentionsSelf("am force-stop com.wadjinny.adbwarden"), true);
  assert.equal(mentionsSelf("pm list packages | grep ADB Warden"), true); // whitespace-insensitive on purpose
  assert.equal(mentionsSelf("pm list packages | grep adbwarden"), true);
  assert.equal(mentionsSelf("ls /sdcard"), false);
});

test("tokenize honours quotes", () => {
  assert.deepEqual(tokenize(`grep "a b" 'c d' e`), ["grep", "a b", "c d", "e"]);
  assert.deepEqual(tokenize(`echo ""`), ["echo", ""]);
  assert.equal(tokenize(`echo "x`), null);
});

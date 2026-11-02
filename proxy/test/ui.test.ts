import { test } from "node:test";
import assert from "node:assert/strict";
import { escapeInputText, formatUiTree, parseUiNodes } from "../src/ui.js";

const XML = `<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">
<node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.android.settings" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1080,2400]">
<node index="0" text="Wi-Fi &amp; network" resource-id="android:id/title" class="android.widget.TextView" package="com.android.settings" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,200][1080,400]" />
<node index="1" text="" resource-id="com.android.settings:id/switch_widget" class="android.widget.Switch" package="com.android.settings" content-desc="Bluetooth" checkable="true" checked="true" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[900,450][1000,550]" />
<node index="2" text="" resource-id="" class="android.widget.LinearLayout" package="com.android.settings" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,600][1080,700]" />
<node index="3" text="Hidden" resource-id="" class="android.widget.TextView" package="com.android.settings" content-desc="" clickable="false" bounds="[0,0][0,0]" />
<node index="4" text="" resource-id="" class="android.widget.ScrollView" package="com.android.settings" content-desc="" clickable="false" scrollable="true" bounds="[0,100][1080,2300]" />
</node></hierarchy>`;

test("parses uiautomator xml into compact nodes", () => {
  const nodes = parseUiNodes(XML);
  assert.equal(nodes.length, 3);
  assert.equal(nodes[0].text, "Wi-Fi & network");
  assert.equal(nodes[0].id, "title");
  assert.deepEqual(nodes[0].center, [540, 300]);
  assert.deepEqual(nodes[1].flags, ["clickable", "checked"]);
  assert.equal(nodes[1].desc, "Bluetooth");
  const out = formatUiTree(XML);
  assert.match(out, /^\[1\] TextView "Wi-Fi & network" id=title center=\(540,300\) clickable$/m);
  assert.match(out, /^\[2\] Switch id=switch_widget desc="Bluetooth" center=\(950,500\) clickable checked$/m);
  assert.match(out, /^\[3\] ScrollView center=\(540,1200\) scrollable$/m);
});

test("escapes input text", () => {
  assert.equal(escapeInputText("hello world"), "hello%sworld");
  assert.equal(escapeInputText("a&b;c"), "a\\&b\\;c");
  assert.equal(escapeInputText(`it's "x" $5 (ok)`), `it\\'s%s\\"x\\"%s\\$5%s\\(ok\\)`);
  assert.equal(escapeInputText("user@mail.com"), "user@mail.com");
  assert.throws(() => escapeInputText("café"), /ASCII/);
  assert.throws(() => escapeInputText("a\nb"), /enter/);
  assert.throws(() => escapeInputText(""), /empty/);
});

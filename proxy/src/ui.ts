/** Pure helpers for UI tools: uiautomator XML → compact text, `input text` escaping, key codes. */

function decodeXml(s: string): string {
  return s
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
    .replace(/&#x([0-9a-fA-F]+);/g, (_, n) => String.fromCodePoint(parseInt(n, 16)))
    .replace(/&amp;/g, "&");
}

export interface UiNode {
  index: number;
  cls: string;
  text: string;
  id: string;
  desc: string;
  center: [number, number] | null;
  bounds: [number, number, number, number] | null;
  flags: string[];
  pkg: string;
}

export function parseUiNodes(xml: string): UiNode[] {
  const nodes: UiNode[] = [];
  const nodeRe = /<node\b([^>]*?)\/?>/g;
  const attrRe = /([\w:-]+)="([^"]*)"/g;
  let m: RegExpExecArray | null;
  while ((m = nodeRe.exec(xml))) {
    const attrs: Record<string, string> = {};
    let a: RegExpExecArray | null;
    attrRe.lastIndex = 0;
    while ((a = attrRe.exec(m[1]))) attrs[a[1]] = decodeXml(a[2]);
    const text = (attrs["text"] ?? "").trim();
    const desc = (attrs["content-desc"] ?? "").trim();
    const rawId = attrs["resource-id"] ?? "";
    const id = rawId.includes(":id/") ? rawId.split(":id/")[1] : rawId;
    const clickable = attrs["clickable"] === "true" || attrs["long-clickable"] === "true";
    const flags: string[] = [];
    if (attrs["clickable"] === "true") flags.push("clickable");
    if (attrs["long-clickable"] === "true") flags.push("long-clickable");
    if (attrs["scrollable"] === "true") flags.push("scrollable");
    if (attrs["checkable"] === "true") flags.push(attrs["checked"] === "true" ? "checked" : "unchecked");
    if (attrs["focused"] === "true") flags.push("focused");
    if (attrs["selected"] === "true") flags.push("selected");
    if (attrs["password"] === "true") flags.push("password");
    if (attrs["enabled"] === "false") flags.push("disabled");
    if (!text && !desc && !id && !clickable && attrs["scrollable"] !== "true") continue;
    const b = (attrs["bounds"] ?? "").match(/\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/);
    const bounds = b ? ([Number(b[1]), Number(b[2]), Number(b[3]), Number(b[4])] as [number, number, number, number]) : null;
    if (bounds && (bounds[2] <= bounds[0] || bounds[3] <= bounds[1])) continue; // zero-size: off screen
    const center = bounds ? ([Math.round((bounds[0] + bounds[2]) / 2), Math.round((bounds[1] + bounds[3]) / 2)] as [number, number]) : null;
    const cls = (attrs["class"] ?? "View").split(".").pop() || "View";
    nodes.push({ index: nodes.length + 1, cls, text, id, desc, center, bounds, flags, pkg: attrs["package"] ?? "" });
  }
  return nodes;
}

function clip(s: string, n = 120): string {
  const one = s.replace(/\s+/g, " ");
  return one.length > n ? one.slice(0, n - 1) + "…" : one;
}

export function formatUiNode(n: UiNode): string {
  const parts = [`[${n.index}]`, n.cls];
  if (n.text) parts.push(JSON.stringify(clip(n.text)));
  if (n.id) parts.push(`id=${n.id}`);
  if (n.desc) parts.push(`desc=${JSON.stringify(clip(n.desc))}`);
  if (n.center) parts.push(`center=(${n.center[0]},${n.center[1]})`);
  parts.push(...n.flags);
  return parts.join(" ");
}

export function formatUiTree(xml: string): string {
  const nodes = parseUiNodes(xml);
  if (!nodes.length) return "(no interesting elements on screen)";
  return nodes.map(formatUiNode).join("\n");
}

/**
 * Escape text for `adb shell input text <arg>`. The arg goes through the device's /system/bin/sh,
 * so shell metacharacters are backslash-escaped; spaces become %s (input's own space escape).
 */
export function escapeInputText(text: string): string {
  if (!text.length) throw new Error("text is empty");
  for (const ch of text) {
    const c = ch.codePointAt(0)!;
    if (c < 0x20 || c > 0x7e) {
      throw new Error(
        `type_text only supports printable ASCII (found ${c === 10 ? "a newline — use press_key enter" : JSON.stringify(ch)})).`,
      );
    }
  }
  let out = "";
  for (const ch of text) {
    if (ch === " ") out += "%s";
    else if (/[\\'"`$&|;<>()\[\]{}*?!~#^%]/.test(ch)) out += "\\" + ch;
    else out += ch;
  }
  return out;
}

export const KEYCODES: Record<string, string> = {
  back: "KEYCODE_BACK",
  home: "KEYCODE_HOME",
  recents: "KEYCODE_APP_SWITCH",
  enter: "KEYCODE_ENTER",
  delete: "KEYCODE_DEL",
  forward_delete: "KEYCODE_FORWARD_DEL",
  tab: "KEYCODE_TAB",
  escape: "KEYCODE_ESCAPE",
  space: "KEYCODE_SPACE",
  menu: "KEYCODE_MENU",
  search: "KEYCODE_SEARCH",
  volume_up: "KEYCODE_VOLUME_UP",
  volume_down: "KEYCODE_VOLUME_DOWN",
  volume_mute: "KEYCODE_VOLUME_MUTE",
  dpad_up: "KEYCODE_DPAD_UP",
  dpad_down: "KEYCODE_DPAD_DOWN",
  dpad_left: "KEYCODE_DPAD_LEFT",
  dpad_right: "KEYCODE_DPAD_RIGHT",
  dpad_center: "KEYCODE_DPAD_CENTER",
  move_home: "KEYCODE_MOVE_HOME",
  move_end: "KEYCODE_MOVE_END",
  page_up: "KEYCODE_PAGE_UP",
  page_down: "KEYCODE_PAGE_DOWN",
  wakeup: "KEYCODE_WAKEUP",
};

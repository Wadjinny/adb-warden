import net from "node:net";
import crypto from "node:crypto";
import { hmacHex } from "../src/bridge.js";

export type Msg = Record<string, any>;

/** Tiny fake of the phone app's bridge server. */
export function fakeApp(opts: { token: string; computerId: string; linkCode?: string; approve?: boolean; policy?: Msg }) {
  const received: Msg[] = [];
  const sockets: net.Socket[] = [];
  const server = net.createServer((sock) => {
    sockets.push(sock);
    sock.setEncoding("utf8");
    const nonce = crypto.randomBytes(16).toString("hex");
    let authed = false;
    const send = (m: Msg) => sock.write(JSON.stringify(m) + "\n");
    send({ t: "hello", v: 1, deviceId: "dev-1", deviceName: "Fake Phone", nonce });
    let buf = "";
    sock.on("data", (d: string) => {
      buf += d;
      let i;
      while ((i = buf.indexOf("\n")) >= 0) {
        const m = JSON.parse(buf.slice(0, i));
        buf = buf.slice(i + 1);
        received.push(m);
        if (!authed) {
          if (m.t === "link") {
            if (m.code === opts.linkCode) send({ t: "linked", computerId: opts.computerId, token: opts.token, deviceId: "dev-1", deviceName: "Fake Phone" });
            else send({ t: "error", code: "bad_link_code", message: "wrong code" });
            sock.end();
          } else if (m.t === "auth") {
            if (m.computerId === opts.computerId && m.mac === hmacHex(opts.token, nonce)) {
              authed = true;
              send({
                t: "ready",
                policy: opts.policy ?? { v: 1, screen: true, input: true, apps: true, install: false, shell: "readonly", appFilter: "blocklist", packages: [], idleTimeoutSec: 300 },
                device: { manufacturer: "fake", model: "F1", androidVersion: "14", sdk: 34, ips: ["10.0.0.5"] },
              });
            } else {
              send({ t: "error", code: "bad_auth", message: "bad mac" });
              sock.end();
            }
          }
          continue;
        }
        if (m.t === "session_request") {
          send(opts.approve === false ? { t: "reply", id: m.id, approved: false, reason: "denied" } : { t: "reply", id: m.id, approved: true, sessionId: "sess-1" });
        } else if (m.t === "ping") send({ t: "pong" });
      }
    });
  });
  return new Promise<{ port: number; received: Msg[]; push: (m: Msg) => void; close: () => void }>((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      const port = (server.address() as net.AddressInfo).port;
      resolve({
        port,
        received,
        push: (m) => sockets.forEach((s) => !s.destroyed && s.write(JSON.stringify(m) + "\n")),
        close: () => {
          sockets.forEach((s) => s.destroy());
          server.close();
        },
      });
    });
  });
}


import { PNG } from "pngjs";
import jpeg from "jpeg-js";

export interface ShrunkImage {
  data: Buffer;
  mimeType: "image/jpeg";
  width: number;
  height: number;
  srcWidth: number;
  srcHeight: number;
  /** Multiply image coordinates by this to get screen coordinates. */
  factor: number;
}

/** Decodes a PNG, box-downscales it so the long side is at most maxLong, and encodes JPEG. */
export function shrinkScreenshot(png: Buffer, maxLong = 1568, quality = 80): ShrunkImage {
  const src = PNG.sync.read(png);
  const sw = src.width;
  const sh = src.height;
  const scale = Math.min(1, maxLong / Math.max(sw, sh));
  const dw = Math.max(1, Math.round(sw * scale));
  const dh = Math.max(1, Math.round(sh * scale));
  const out = Buffer.alloc(dw * dh * 4);
  const s = src.data;
  for (let y = 0; y < dh; y++) {
    const y0 = Math.floor((y * sh) / dh);
    const y1 = Math.max(y0 + 1, Math.floor(((y + 1) * sh) / dh));
    for (let x = 0; x < dw; x++) {
      const x0 = Math.floor((x * sw) / dw);
      const x1 = Math.max(x0 + 1, Math.floor(((x + 1) * sw) / dw));
      let r = 0, g = 0, b = 0, n = 0;
      for (let yy = y0; yy < y1; yy++) {
        let i = (yy * sw + x0) * 4;
        for (let xx = x0; xx < x1; xx++, i += 4) {
          r += s[i]; g += s[i + 1]; b += s[i + 2]; n++;
        }
      }
      const o = (y * dw + x) * 4;
      out[o] = r / n; out[o + 1] = g / n; out[o + 2] = b / n; out[o + 3] = 255;
    }
  }
  const enc = jpeg.encode({ data: out, width: dw, height: dh }, quality);
  return { data: Buffer.from(enc.data), mimeType: "image/jpeg", width: dw, height: dh, srcWidth: sw, srcHeight: sh, factor: sw / dw };
}

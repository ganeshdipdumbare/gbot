import { useEffect, useRef, useState } from "react";
import { api } from "./api";

// The bot's screen, live. The server sends it as a multipart MJPEG stream
// (a frame each time the screen changes, up to 30 a second). It is read
// here with fetch and drawn on a canvas, not handed to <img>: WebKit
// (Safari, the macOS app's WKWebView) doesn't paint multipart images, and
// a canvas never flashes between frames. Every client gets the same live
// picture; polling single frames is only the fallback when streaming fails.

const BOUNDARY_END = [13, 10, 13, 10]; // \r\n\r\n after a part's headers

function indexOf(buf: Uint8Array, pat: number[], from: number) {
  outer: for (let i = from; i <= buf.length - pat.length; i++) {
    for (let j = 0; j < pat.length; j++) if (buf[i + j] !== pat[j]) continue outer;
    return i;
  }
  return -1;
}

/** Splits a multipart byte stream into the JPEG parts it carries. */
export function multipartFrames(onFrame: (jpeg: Uint8Array) => void) {
  let buf = new Uint8Array(0);
  return (chunk: Uint8Array) => {
    const next = new Uint8Array(buf.length + chunk.length);
    next.set(buf); next.set(chunk, buf.length);
    buf = next;
    for (;;) {
      const h = indexOf(buf, BOUNDARY_END, 0);
      if (h < 0) return;
      const head = new TextDecoder().decode(buf.subarray(0, h));
      const m = /content-length:\s*(\d+)/i.exec(head);
      if (!m) { buf = buf.slice(h + 4); continue; }
      const len = Number(m[1]), start = h + 4;
      if (buf.length < start + len) return;
      onFrame(buf.slice(start, start + len));
      buf = buf.slice(start + len);
    }
  };
}

/**
 * Draws the bot's screen on the returned canvas while `on`. `ready` turns
 * true with the first frame. Only the newest frame is decoded: a slow
 * device skips frames instead of falling behind.
 */
export function useLiveScreen(botId: string, on: boolean) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [ready, setReady] = useState(false);
  useEffect(() => {
    setReady(false);
    if (!on) return;
    let stop = false;
    let shown = false;
    let pending: Uint8Array | null = null;
    let drawing = false;
    const ac = new AbortController();

    const draw = async () => {
      if (drawing) return;
      drawing = true;
      try {
        while (pending && !stop) {
          const jpeg = pending;
          pending = null;
          if (document.visibilityState !== "visible") continue;
          const bmp = await createImageBitmap(new Blob([jpeg as BlobPart], { type: "image/jpeg" })).catch(() => null);
          const c = canvas.current;
          if (!bmp || !c || stop) { bmp?.close(); continue; }
          if (c.width !== bmp.width || c.height !== bmp.height) { c.width = bmp.width; c.height = bmp.height; }
          c.getContext("2d")?.drawImage(bmp, 0, 0);
          bmp.close();
          if (!shown) { shown = true; setReady(true); }
        }
      } finally { drawing = false; }
    };
    const frame = (jpeg: Uint8Array) => { pending = jpeg; void draw(); };

    const poll = async () => {
      while (!stop) {
        if (document.visibilityState === "visible") {
          try {
            const res = await fetch(api.screenUrl(botId), { credentials: "same-origin", signal: ac.signal });
            if (res.ok) frame(new Uint8Array(await res.arrayBuffer()));
          } catch { /* keep the last frame; retry */ }
        }
        await new Promise((r) => setTimeout(r, 125));
      }
    };

    const run = async () => {
      let streamed = false;
      // Reconnect after a dropped stream (a proxy timing out, the computer
      // waking up); poll only when streaming never worked at all.
      for (let tries = 0; !stop; tries++) {
        try {
          const res = await fetch(api.streamUrl(botId), { credentials: "same-origin", signal: ac.signal });
          if (!res.ok || !res.body) throw new Error(String(res.status));
          const feed = multipartFrames((f) => { streamed = true; frame(f); });
          const reader = res.body.getReader();
          for (;;) {
            const { value, done } = await reader.read();
            if (done) break;
            if (value) feed(value);
          }
        } catch { /* fall through to retry */ }
        if (stop) return;
        if (!streamed && tries >= 1) return poll();
        await new Promise((r) => setTimeout(r, Math.min(4000, 400 * (tries + 1))));
      }
    };
    void run();
    return () => { stop = true; ac.abort(); };
  }, [botId, on]);
  return { canvas, ready };
}

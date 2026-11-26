// gbot-fx: the bot's visible hand. A cursor that glides to what the bot is
// about to use, a ring around it, a ripple on click, and a short label that
// says what it is doing. It is drawn inside the page, so it shows up in any
// capture of the page: the computer's screen, the phone's browser, a screencast.
//
// The same file runs on the computer (injected by gbot-web) and on the phone
// (injected by the Android browser), so both look alike. It matches the
// website's pointer: a round dot in the live colour with a white ring.
//
// Everything lives in a closed shadow root under one fixed host that ignores
// the mouse, so the page can't style it, read it, or have a click land on it.
// Calls never throw: a page that is odd must not stop the bot's work.
(() => {
  if (window.__gbotFx) return;
  const LIVE = "#8b5cf6";
  const SOFT = "rgba(139, 92, 246, .14)";
  const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
  let host = null, root = null, dot = null, tag = null, ring = null, toast = null;
  let idle = 0, ringTimer = 0, toastTimer = 0, tagW = 80;

  const css = `
    :host { all: initial; position: fixed; inset: 0; z-index: 2147483647; pointer-events: none; contain: layout style; }
    * { box-sizing: border-box; }
    .dot { position: fixed; left: 0; top: 0; width: 15px; height: 15px; margin: -7.5px 0 0 -7.5px; border-radius: 50%;
      background: ${LIVE}; border: 2.5px solid #fff; box-shadow: 0 2px 8px rgba(0,0,0,.3); opacity: 0;
      transition: opacity .25s, scale .12s; will-change: transform; }
    .dot.on { opacity: 1; }
    .dot.dim { opacity: .35; }
    .dot.press { scale: .75; }
    .tag { position: fixed; left: 0; top: 0; padding: 4px 10px; border-radius: 999px; background: ${LIVE}; color: #fff;
      font: 600 12px/1.2 system-ui, -apple-system, "Segoe UI", sans-serif; letter-spacing: -.005em; white-space: nowrap;
      box-shadow: 0 2px 10px rgba(0,0,0,.25); opacity: 0; transition: opacity .2s; will-change: transform; }
    .tag.on { opacity: 1; }
    .ring { position: fixed; border: 2px solid ${LIVE}; background: ${SOFT}; border-radius: 8px; opacity: 0;
      transition: opacity .25s; }
    .ring.on { opacity: 1; }
    .pulse { position: fixed; width: 16px; height: 16px; margin: -8px 0 0 -8px; border-radius: 50%;
      border: 2px solid ${LIVE}; opacity: .9; animation: pulse .55s cubic-bezier(.22, 1, .36, 1) forwards; }
    @keyframes pulse { to { transform: scale(3.4); opacity: 0; } }
    .toast { position: fixed; left: 50%; bottom: 22px; translate: -50% 0; padding: 7px 14px; border-radius: 999px;
      background: rgba(20, 20, 24, .88); color: #fff; font: 600 13px/1.2 system-ui, -apple-system, "Segoe UI", sans-serif;
      box-shadow: 0 4px 18px rgba(0,0,0,.3); opacity: 0; transform: translateY(8px); transition: opacity .25s, transform .3s; max-width: 80vw;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .toast.on { opacity: 1; transform: none; }
  `;

  const mount = () => {
    const parent = document.documentElement;
    if (!parent) return false;
    if (host && host.isConnected) return true;
    host = document.createElement("div");
    host.setAttribute("data-gbot-fx", "");
    root = host.attachShadow({ mode: "closed" });
    const style = document.createElement("style");
    style.textContent = css;
    dot = document.createElement("div"); dot.className = "dot";
    tag = document.createElement("div"); tag.className = "tag";
    ring = document.createElement("div"); ring.className = "ring";
    toast = document.createElement("div"); toast.className = "toast";
    root.append(style, ring, dot, tag, toast);
    parent.appendChild(host);
    place(fx.x, fx.y);
    return true;
  };

  const place = (x, y) => {
    if (!dot) return;
    dot.style.transform = `translate(${x}px, ${y}px)`;
    // The label sits down and to the right of the dot, and flips at the edges.
    const w = tagW;
    const left = x + 16 + w > innerWidth - 6 ? x - 16 - w : x + 16;
    const top = y + 14 + 26 > innerHeight - 6 ? y - 14 - 26 : y + 14;
    tag.style.transform = `translate(${Math.max(6, left)}px, ${Math.max(6, top)}px)`;
  };

  // Reading a width forces layout, so it is done when the words change, not on every frame.
  const label = (text) => {
    tag.textContent = text || "";
    tag.classList.toggle("on", !!text);
    if (text) tagW = tag.offsetWidth || 80;
  };

  const wake = () => {
    if (!mount()) return false;
    dot.classList.add("on");
    dot.classList.remove("dim");
    clearTimeout(idle);
    // Between steps the cursor stays, but fades back so it doesn't cover the page.
    idle = setTimeout(() => { dot.classList.add("dim"); tag.classList.remove("on"); }, 4000);
    return true;
  };

  const ease = (t) => 1 - Math.pow(1 - t, 4); // out-quart, like the site's --out

  const fx = {
    x: -1,
    y: -1,

    /** Where the cursor was left by the last step, so the next one starts there. */
    at(x, y) { if (this.x < 0 && x >= 0) { this.x = x; this.y = y; } },

    say(text) {
      try {
        if (!wake()) return;
        label(text);
        place(this.x, this.y);
      } catch (e) {}
    },

    /** A line at the bottom, for things that have no place on the page: opening a site. */
    note(text, ms) {
      try {
        if (!mount()) return;
        toast.textContent = text;
        toast.classList.add("on");
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => toast.classList.remove("on"), ms || 2200);
      } catch (e) {}
    },

    /** Outline a box (viewport coordinates) until the next step. */
    ring(r) {
      try {
        if (!mount()) return;
        clearTimeout(ringTimer);
        if (!r) { ring.classList.remove("on"); return; }
        const pad = 3;
        Object.assign(ring.style, {
          left: r.x - pad + "px", top: r.y - pad + "px",
          width: r.width + pad * 2 + "px", height: r.height + pad * 2 + "px",
        });
        ring.classList.add("on");
        ringTimer = setTimeout(() => ring.classList.remove("on"), 2400);
      } catch (e) {}
    },

    /** Glide to a point; resolves when it arrives. */
    move(x, y, text, ms) {
      return new Promise((done) => {
        try {
          if (!wake()) return done();
          if (this.x < 0) { this.x = Math.min(innerWidth - 40, x + 220); this.y = Math.max(40, y - 140); place(this.x, this.y); }
          if (text !== undefined && text !== null) label(text);
          const fx0 = this.x, fy0 = this.y;
          const dist = Math.hypot(x - fx0, y - fy0);
          const dur = still ? 0 : ms || Math.max(220, Math.min(720, 160 + dist * 0.55));
          if (dur === 0 || dist < 1) { this.x = x; this.y = y; place(x, y); return done(); }
          // A slight arc, like a hand, not a ruler.
          const bend = Math.min(60, dist * 0.12) * (x >= fx0 ? -1 : 1);
          const cx = (fx0 + x) / 2 + (-(y - fy0) / dist) * bend;
          const cy = (fy0 + y) / 2 + ((x - fx0) / dist) * bend;
          const t0 = performance.now();
          let finished = false;
          const end = () => { if (finished) return; finished = true; this.x = x; this.y = y; place(x, y); done(); };
          const step = (now) => {
            if (finished) return;
            const k = Math.min(1, (now - t0) / dur), e = ease(k), u = 1 - e;
            this.x = u * u * fx0 + 2 * u * e * cx + e * e * x;
            this.y = u * u * fy0 + 2 * u * e * cy + e * e * y;
            place(this.x, this.y);
            k < 1 ? requestAnimationFrame(step) : end();
          };
          requestAnimationFrame(step);
          // A background tab pauses animation frames; never hold the bot's step for it.
          setTimeout(end, dur + 150);
        } catch (e) { done(); }
      });
    },

    /** Press: the dot squishes and a ring spreads from it. */
    click() {
      return new Promise((done) => {
        try {
          if (!wake()) return done();
          dot.classList.add("press");
          const p = document.createElement("div");
          p.className = "pulse";
          p.style.left = this.x + "px";
          p.style.top = this.y + "px";
          root.appendChild(p);
          setTimeout(() => p.remove(), 700);
          setTimeout(() => { dot.classList.remove("press"); done(); }, still ? 0 : 140);
        } catch (e) { done(); }
      });
    },

    /** Fade everything out, for a page the bot is done with. */
    hide() {
      try {
        if (!host) return;
        dot.classList.remove("on"); tag.classList.remove("on"); ring.classList.remove("on"); toast.classList.remove("on");
      } catch (e) {}
    },

    /** The phone's helper: go to one of the numbered elements, then ring it. */
    toElement(el, text) {
      try {
        const b = el.getBoundingClientRect();
        this.ring({ x: b.left, y: b.top, width: b.width, height: b.height });
        return this.move(b.left + b.width / 2, b.top + b.height / 2, text);
      } catch (e) { return Promise.resolve(); }
    },
  };
  window.__gbotFx = fx;
})();

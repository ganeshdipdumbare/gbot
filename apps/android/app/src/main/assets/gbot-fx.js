// gbot-fx: the bot's visible hand. A cursor that glides to what the bot is
// about to use, a ring around it, a ripple on click, and a short label that
// says what it is doing. It is drawn inside the page, so it shows up in any
// capture of the page: the computer's screen, the phone's browser, a screencast.
//
// The same file runs on the computer (injected by gbot-web) and on the phone
// (injected by the Android browser), so both look alike. It matches the
// website's pointer: a round dot in the live colour with a ring in the card colour
// (white, or dark in dark mode).
//
// Everything lives in a closed shadow root under one fixed host that ignores
// the mouse, so the page can't style it, read its insides, or have a click land
// on it. When supported, the host is promoted to the browser's top layer
// (popover="manual") so transforms, filters, or z-index layers on the page can
// never clip or displace it. The page can still see the global and the timers
// this uses, so a hostile page can switch the effects off or garble them. It
// can never hold the bot up: gbot-web starts an effect, takes the duration it
// reports (clamped), and waits that long itself instead of waiting on anything
// in the page. Calls never throw.
(() => {
  if (window.__gbotFx) return;
  const LIVE = "#8b5cf6";
  const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
  let host = null, root = null, dot = null, tag = null, ring = null, toast = null;
  let idle = 0, ringTimer = 0, toastTimer = 0, tagW = 80;

  // Sizes are in screen units, not page units: --u is how many page pixels make one on-screen
  // pixel. On a phone a page is laid out wide and shrunk to fit (scale about 0.4), so a fixed
  // 15px dot would come out about 6 on screen and a 12px label unreadable.
  // Look and feel follow the website's computer demo and the app's tokens (packages/ui-tokens):
  // the same live purple, card surface, soft highlight, "Live"-style pill with a pulsing dot.
  const css = `
    :host { all: initial; position: fixed; inset: 0; width: 100%; height: 100%; margin: 0; padding: 0; border: 0;
      background: transparent; overflow: visible; z-index: 2147483647; pointer-events: none; contain: layout style; --u: 1;
      --live: ${LIVE}; --live-soft: rgba(139, 92, 246, .12); --card: #fff;
      --pop-shadow: 0 0 0 .5px rgba(0, 0, 0, .1), 0 12px 32px -8px rgba(0, 0, 0, .18);
      --sans: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", "Helvetica Neue", Arial, sans-serif; }
    @media (prefers-color-scheme: dark) { :host { --live-soft: rgba(139, 92, 246, .18); --card: #222;
      --pop-shadow: 0 0 0 .5px rgba(255, 255, 255, .12), 0 16px 40px -8px rgba(0, 0, 0, .6); } }
    * { box-sizing: border-box; }
    .dot { position: fixed; left: 0; top: 0; width: calc(15px * var(--u)); height: calc(15px * var(--u));
      margin: calc(-7.5px * var(--u)) 0 0 calc(-7.5px * var(--u)); border-radius: 50%;
      background: var(--live); border: calc(2.5px * var(--u)) solid var(--card); box-shadow: 0 calc(2px * var(--u)) calc(8px * var(--u)) rgba(0,0,0,.25); opacity: 0;
      transition: opacity .25s, scale .12s cubic-bezier(.2, .9, .3, 1.15); will-change: transform; }
    .dot.on { opacity: 1; }
    .dot.dim { opacity: .72; }
    .dot.press { scale: .8; }
    .tag, .toast { background: var(--card); color: var(--live); font: 600 calc(12.5px * var(--u))/1.2 var(--sans); white-space: nowrap; box-shadow: var(--pop-shadow); }
    .tag::before, .toast::before { content: ""; flex: none; width: calc(7px * var(--u)); height: calc(7px * var(--u)); border-radius: 50%;
      background: var(--live); animation: live 1.8s ease-in-out infinite; }
    .tag { display: inline-flex; align-items: center; gap: calc(7px * var(--u)); max-width: min(calc(260px * var(--u)), 70vw); overflow: hidden; text-overflow: ellipsis; }
    .tag { position: fixed; left: 0; top: 0; padding: calc(4px * var(--u)) calc(10px * var(--u)) calc(4px * var(--u)) calc(9px * var(--u)); border-radius: 999px;
      opacity: 0; transition: opacity .2s; will-change: transform; }
    .tag.on { opacity: 1; }
    .ring { position: fixed; border: calc(2px * var(--u)) solid var(--live); background: var(--live-soft); border-radius: calc(8px * var(--u)); opacity: 0;
      transition: opacity .25s; }
    .ring.on { opacity: 1; }
    .pulse { position: fixed; left: 0; top: 0; width: calc(15px * var(--u)); height: calc(15px * var(--u)); margin: calc(-7.5px * var(--u)) 0 0 calc(-7.5px * var(--u)); border-radius: 50%;
      border: calc(2px * var(--u)) solid var(--live); opacity: .9; animation: ripple .55s cubic-bezier(.2, .7, .2, 1) forwards; }
    @keyframes ripple { to { scale: 3.4; opacity: 0; } }
    @keyframes live { 0%, 100% { transform: scale(1); opacity: 1; } 50% { transform: scale(.72); opacity: .75; } }
    /* A block, not a flex box, so a long note ends in an ellipsis. */
    .toast { display: block; position: fixed; left: 50%; bottom: calc(22px * var(--u)); translate: -50% 0; padding: calc(7px * var(--u)) calc(14px * var(--u)) calc(7px * var(--u)) calc(12px * var(--u));
      border-radius: 999px; opacity: 0; transform: translateY(8px); transition: opacity .25s, transform .3s; max-width: 80vw;
      overflow: hidden; text-overflow: ellipsis; }
    .toast::before { display: inline-block; vertical-align: middle; margin-right: calc(7px * var(--u)); }
    .toast.on { opacity: 1; transform: none; }
  `;

  // What the person can actually see: the visual viewport (smaller when the page is
  // zoomed or the keyboard is up), bounded by the layout viewport's client area so
  // desktop scrollbars are excluded. Everything is kept inside it, with a margin,
  // so the cursor is never somewhere nobody can see.
  const M = 14; // screen pixels for off-screen targets
  const EDGE = 9; // screen pixels so the 15px dot never clips at a viewport edge
  // A glide advances by at most this much per drawn frame (about one), in ms, so a stalled frame
  // pauses it instead of making it jump; it ends by dur + SLACK whatever happens (see move).
  const MAX_FRAME = 20, SLACK = 600;
  // Page pixels per screen pixel: 1 on a desktop, about 2.5 on a phone showing a page shrunk to fit.
  const unit = () => { const v = window.visualViewport; const k = v && v.scale > 0 ? 1 / v.scale : 1; return Math.min(4, Math.max(0.5, k)); };
  const view = () => {
    const v = window.visualViewport;
    const l = v ? v.offsetLeft : 0, t = v ? v.offsetTop : 0;
    const dw = document.documentElement ? document.documentElement.clientWidth : 0;
    const dh = document.documentElement ? document.documentElement.clientHeight : 0;
    const w = v ? (v.scale > 1.01 ? v.width : Math.min(v.width, dw || v.width)) : (dw || innerWidth);
    const h = v ? (v.scale > 1.01 ? v.height : Math.min(v.height, dh || v.height)) : (dh || innerHeight);
    return { l, t, r: l + Math.max(1, w), b: t + Math.max(1, h) };
  };
  const keep = (x, y, margin = M) => {
    const v = view(), m = margin * unit();
    return [
      Math.min(Math.max(x, v.l + m), Math.max(v.l + m, v.r - m)),
      Math.min(Math.max(y, v.t + m), Math.max(v.t + m, v.b - m)),
    ];
  };
  const inside = (x, y) => { const v = view(); return x >= v.l && x <= v.r && y >= v.t && y <= v.b; };
  const home = () => { const v = view(); return [v.l + (v.r - v.l) * 0.5, v.t + (v.b - v.t) * 0.45]; };
  // Which way a point that is out of view lies, to say so in the label: both
  // directions when it is off diagonally.
  const where = (x, y) => {
    const v = view();
    return (y > v.b ? "↓" : y < v.t ? "↑" : "") + (x > v.r ? "→" : x < v.l ? "←" : "");
  };

  const ensureTopLayer = () => {
    try {
      if (host && typeof host.showPopover === "function" && !host.matches(":popover-open")) {
        host.showPopover();
      }
    } catch (e) {}
  };

  const mount = () => {
    const parent = document.documentElement;
    if (!parent) return false;
    if (host && host.isConnected) { ensureTopLayer(); return true; }
    // A step run earlier left its own cursor in the page; this one takes over from it.
    document.querySelectorAll("[data-gbot-fx]").forEach((h) => h.remove());
    host = document.createElement("div");
    host.setAttribute("data-gbot-fx", "");
    if (typeof host.showPopover === "function") host.setAttribute("popover", "manual");
    root = host.attachShadow({ mode: "closed" });
    const style = document.createElement("style");
    style.textContent = css;
    dot = document.createElement("div"); dot.className = "dot";
    tag = document.createElement("div"); tag.className = "tag";
    ring = document.createElement("div"); ring.className = "ring";
    toast = document.createElement("div"); toast.className = "toast";
    root.append(style, ring, dot, tag, toast);
    host.style.setProperty("--u", unit());
    parent.appendChild(host);
    ensureTopLayer();
    if (fx.x >= 0 && fx.y >= 0) place(fx.x, fx.y);
    return true;
  };

  const place = (px, py) => {
    if (!dot) return;
    // Keep the dot fully inside the visible viewport; targets outside pull to the margin.
    const u = unit();
    host.style.setProperty("--u", u);
    const [x, y] = keep(px, py, inside(px, py) ? EDGE : M);
    dot.style.transform = `translate(${x}px, ${y}px)`;
    // The label sits down and to the right of the dot, flips at edges, and is clamped on both axes.
    const v = view(), m = 6 * u;
    const w = Math.min(tagW, Math.max(0, (v.r - v.l) - m * 2));
    const gx = 16 * u, gy = 14 * u, th = 28 * u;
    const rawLeft = x + gx + w > v.r - m ? x - gx - w : x + gx;
    const rawTop = y + gy + th > v.b - m ? y - gy - th : y + gy;
    const left = Math.min(Math.max(v.l + m, rawLeft), Math.max(v.l + m, v.r - m - w));
    const top = Math.min(Math.max(v.t + m, rawTop), Math.max(v.t + m, v.b - m - th));
    tag.style.transform = `translate(${left}px, ${top}px)`;
  };
  // Zooming, resizing or the keyboard moves what is visible: pull the cursor back in.
  let settling = 0;
  const resettle = () => {
    if (settling) return;
    settling = requestAnimationFrame(() => { settling = 0; if (fx.x >= 0 && fx.y >= 0) place(fx.x, fx.y); });
  };
  addEventListener("resize", resettle);
  if (window.visualViewport) { visualViewport.addEventListener("resize", resettle); visualViewport.addEventListener("scroll", resettle); }

  // Reading a width forces layout, so it is done when the words change, not on every frame.
  const label = (text) => {
    tag.textContent = text || "";
    tag.classList.toggle("on", !!text);
    if (text) {
      const u = unit();
      host.style.setProperty("--u", u);
      tagW = Math.ceil(tag.getBoundingClientRect().width) || Math.round(80 * u);
    }
  };

  const wake = () => {
    const fresh = !host || !host.isConnected;
    if (!mount()) return false;
    if (fx.x < 0 || fx.y < 0) {
      const h = home();
      fx.x = h[0]; fx.y = h[1];
      place(fx.x, fx.y);
    }
    if (fresh) {
      // Taking over from an earlier cursor: appear at once instead of fading in.
      dot.style.transition = "none";
      dot.classList.add("on");
      requestAnimationFrame(() => requestAnimationFrame(() => { dot.style.transition = ""; }));
    }
    dot.classList.add("on");
    dot.classList.remove("dim");
    clearTimeout(idle);
    // Between steps the cursor stays visible; after a pause it softens slightly and clears the label.
    idle = setTimeout(() => { dot.classList.add("dim"); tag.classList.remove("on"); }, 12000);
    return true;
  };

  // The website's --out easing, cubic-bezier(.2, .7, .2, 1), solved for time.
  const ease = (t) => {
    const x1 = 0.2, y1 = 0.7, x2 = 0.2, y2 = 1;
    const bez = (a, b, k) => 3 * a * (1 - k) * (1 - k) * k + 3 * b * (1 - k) * k * k + k * k * k;
    let lo = 0, hi = 1, k = t;
    for (let i = 0; i < 18; i++) { k = (lo + hi) / 2; bez(x1, x2, k) < t ? (lo = k) : (hi = k); }
    return bez(y1, y2, k);
  };

  const fx = {
    x: -1,
    y: -1,
    tx: -1, // clamped destination of the last move, so callers can persist an in-view coordinate
    ty: -1,
    dur: 0, // how long the last move or click takes, in ms, for a caller that waits on its own clock
    moving: false, // a glide is under way; it can run past dur when frames stall, by SLACK at most

    /** Where the cursor was left by the last step, so the next one starts there. */
    at(x, y) { if (this.x < 0 && x >= 0 && y >= 0) { const k = keep(x, y, EDGE); this.x = k[0]; this.y = k[1]; this.tx = k[0]; this.ty = k[1]; } },

    /** Ensure the cursor is mounted and visible on the page, with an optional status label. */
    show(text) {
      try {
        if (!wake()) return;
        if (text !== undefined && text !== null) label(text);
        place(this.x, this.y);
        this.tx = this.x; this.ty = this.y;
      } catch (e) {}
    },

    say(text) {
      try {
        if (!wake()) return;
        label(text);
        place(this.x, this.y);
        this.tx = this.x; this.ty = this.y;
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
        // Only the part that is in view is outlined.
        const v = view(), pad = 3 * unit();
        const l = Math.max(r.x - pad, v.l + 2), t = Math.max(r.y - pad, v.t + 2);
        const rt = Math.min(r.x + r.width + pad, v.r - 2), bt = Math.min(r.y + r.height + pad, v.b - 2);
        if (rt <= l || bt <= t) { ring.classList.remove("on"); return; }
        Object.assign(ring.style, { left: l + "px", top: t + "px", width: rt - l + "px", height: bt - t + "px" });
        ring.classList.add("on");
        ringTimer = setTimeout(() => ring.classList.remove("on"), 2400);
      } catch (e) {}
    },

    /** Glide to a point; resolves when it arrives. */
    move(x, y, text, ms) {
      this.dur = 0;
      return new Promise((done) => {
        try {
          if (!mount()) return done();
          // A target out of view sends the cursor to the nearest edge, labelled with the way to it.
          const out = where(x, y);
          [x, y] = keep(x, y, out ? M : EDGE);
          if (this.x < 0 || this.y < 0) { const k = keep(x + 220, y - 140); this.x = k[0]; this.y = k[1]; place(this.x, this.y); }
          if (!wake()) return done();
          if (text !== undefined && text !== null) label(text && out ? text + " " + out : text);
          this.tx = x; this.ty = y;
          const fx0 = this.x, fy0 = this.y;
          const dist = Math.hypot(x - fx0, y - fy0);
          const dur = still ? 0 : ms || Math.max(220, Math.min(720, 160 + dist * 0.55));
          if (dur === 0 || dist < 1) { this.x = x; this.y = y; place(x, y); return done(); }
          this.dur = dur;
          // A slight arc, like a hand, not a ruler.
          const bend = Math.min(60, dist * 0.12) * (x >= fx0 ? -1 : 1);
          const cx = (fx0 + x) / 2 + (-(y - fy0) / dist) * bend;
          const cy = (fy0 + y) / 2 + ((x - fx0) / dist) * bend;
          // The glide plays by the frames that are drawn, not by the wall clock. A page busy
          // laying out (the first drawing of the cursor, a big page, a slow phone) can stall a
          // frame for 100 ms or more; on a wall clock the cursor would cover most of its path
          // unseen and appear to jump. Here a stalled frame advances it by about one frame,
          // so a stall only pauses it. `moving` says when it has really arrived.
          let played = 0, last = 0;
          let finished = false;
          this.moving = true;
          const end = () => { if (finished) return; finished = true; this.moving = false; this.x = x; this.y = y; place(x, y); done(); };
          const step = (now) => {
            if (finished) return;
            played += last ? Math.min(now - last, MAX_FRAME) : 0;
            last = now;
            const k = Math.min(1, played / dur), e = ease(k), u = 1 - e;
            this.x = u * u * fx0 + 2 * u * e * cx + e * e * x;
            this.y = u * u * fy0 + 2 * u * e * cy + e * e * y;
            place(this.x, this.y);
            k < 1 ? requestAnimationFrame(step) : end();
          };
          requestAnimationFrame(step);
          // A background tab pauses animation frames; never hold the bot's step for it.
          setTimeout(end, dur + SLACK);
        } catch (e) { done(); }
      });
    },

    /** Press: the dot squishes and a ring spreads from it. */
    click() {
      this.dur = still ? 0 : 140;
      return new Promise((done) => {
        try {
          if (!wake()) return done();
          dot.classList.add("press");
          const [px, py] = keep(this.x, this.y, EDGE);
          const p = document.createElement("div");
          p.className = "pulse";
          p.style.transform = `translate(${px}px, ${py}px)`;
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

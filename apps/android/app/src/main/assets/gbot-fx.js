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
// on it. The page can still see the global and the timers this uses, so a hostile
// page can switch the effects off or garble them. It can never hold the bot up:
// gbot-web starts an effect, takes the duration it reports (clamped), and waits
// that long itself instead of waiting on anything in the page. Calls never throw.
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
    :host { all: initial; position: fixed; inset: 0; z-index: 2147483647; pointer-events: none; contain: layout style; --u: 1;
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
    .dot.dim { opacity: .35; }
    .dot.press { scale: .8; }
    .tag, .toast { background: var(--card); color: var(--live); font: 600 calc(12.5px * var(--u))/1.2 var(--sans); white-space: nowrap; box-shadow: var(--pop-shadow); }
    .tag::before, .toast::before { content: ""; flex: none; width: calc(7px * var(--u)); height: calc(7px * var(--u)); border-radius: 50%;
      background: var(--live); animation: live 1.8s ease-in-out infinite; }
    .tag { display: inline-flex; align-items: center; gap: calc(7px * var(--u)); }
    .tag { position: fixed; left: 0; top: 0; padding: calc(4px * var(--u)) calc(10px * var(--u)) calc(4px * var(--u)) calc(9px * var(--u)); border-radius: 999px;
      opacity: 0; transition: opacity .2s; will-change: transform; }
    .tag.on { opacity: 1; }
    .ring { position: fixed; border: calc(2px * var(--u)) solid var(--live); background: var(--live-soft); border-radius: calc(8px * var(--u)); opacity: 0;
      transition: opacity .25s; }
    .ring.on { opacity: 1; }
    .pulse { position: fixed; width: calc(15px * var(--u)); height: calc(15px * var(--u)); margin: calc(-7.5px * var(--u)) 0 0 calc(-7.5px * var(--u)); border-radius: 50%;
      border: calc(2px * var(--u)) solid var(--live); opacity: .9; animation: ripple .55s cubic-bezier(.2, .7, .2, 1) forwards; }
    @keyframes ripple { to { transform: scale(3.4); opacity: 0; } }
    @keyframes live { 0%, 100% { transform: scale(1); opacity: 1; } 50% { transform: scale(.72); opacity: .75; } }
    /* A block, not a flex box, so a long note ends in an ellipsis. */
    .toast { display: block; position: fixed; left: 50%; bottom: calc(22px * var(--u)); translate: -50% 0; padding: calc(7px * var(--u)) calc(14px * var(--u)) calc(7px * var(--u)) calc(12px * var(--u));
      border-radius: 999px; opacity: 0; transform: translateY(8px); transition: opacity .25s, transform .3s; max-width: 80vw;
      overflow: hidden; text-overflow: ellipsis; }
    .toast::before { display: inline-block; vertical-align: middle; margin-right: calc(7px * var(--u)); }
    .toast.on { opacity: 1; transform: none; }
  `;

  // What the person can actually see: the visual viewport (smaller when the page is
  // zoomed or the keyboard is up), else the window. Everything is kept inside it, with
  // a margin, so the cursor is never somewhere nobody can see.
  const M = 14; // screen pixels
  // Page pixels per screen pixel: 1 on a desktop, about 2.5 on a phone showing a page shrunk to fit.
  const unit = () => { const v = window.visualViewport; const k = v && v.scale > 0 ? 1 / v.scale : 1; return Math.min(4, Math.max(0.5, k)); };
  const view = () => {
    const v = window.visualViewport;
    const l = v ? v.offsetLeft : 0, t = v ? v.offsetTop : 0;
    return { l, t, r: l + (v ? v.width : innerWidth), b: t + (v ? v.height : innerHeight) };
  };
  const keep = (x, y) => {
    const v = view();
    return [
      Math.min(Math.max(x, v.l + M * unit()), Math.max(v.l + M * unit(), v.r - M * unit())),
      Math.min(Math.max(y, v.t + M * unit()), Math.max(v.t + M * unit(), v.b - M * unit())),
    ];
  };
  const inside = (x, y) => { const v = view(); return x >= v.l && x <= v.r && y >= v.t && y <= v.b; };
  // Which way a point that is out of view lies, to say so in the label: both
  // directions when it is off diagonally.
  const where = (x, y) => {
    const v = view();
    return (y > v.b ? "↓" : y < v.t ? "↑" : "") + (x > v.r ? "→" : x < v.l ? "←" : "");
  };

  const mount = () => {
    const parent = document.documentElement;
    if (!parent) return false;
    if (host && host.isConnected) return true;
    // A step run earlier left its own cursor in the page; this one takes over from it.
    document.querySelectorAll("[data-gbot-fx]").forEach((h) => h.remove());
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
    host.style.setProperty("--u", unit());
    parent.appendChild(host);
    place(fx.x, fx.y);
    return true;
  };

  const place = (px, py) => {
    if (!dot) return;
    // A point in view is drawn exactly there, even right at an edge; only one out of view is pulled in.
    const u = unit();
    host.style.setProperty("--u", u);
    const [x, y] = inside(px, py) ? [px, py] : keep(px, py);
    dot.style.transform = `translate(${x}px, ${y}px)`;
    // The label sits down and to the right of the dot, and flips at the edges.
    const v = view(), w = tagW;
    const gx = 16 * u, gy = 14 * u, th = 28 * u, m = 6 * u;
    const left = x + gx + w > v.r - m ? x - gx - w : x + gx;
    const top = y + gy + th > v.b - m ? y - gy - th : y + gy;
    tag.style.transform = `translate(${Math.max(v.l + m, left)}px, ${Math.max(v.t + m, top)}px)`;
  };
  // Zooming, resizing or the keyboard moves what is visible: pull the cursor back in.
  let settling = 0;
  const resettle = () => {
    if (settling) return;
    settling = requestAnimationFrame(() => { settling = 0; place(fx.x, fx.y); });
  };
  addEventListener("resize", resettle);
  if (window.visualViewport) { visualViewport.addEventListener("resize", resettle); visualViewport.addEventListener("scroll", resettle); }

  // Reading a width forces layout, so it is done when the words change, not on every frame.
  const label = (text) => {
    tag.textContent = text || "";
    tag.classList.toggle("on", !!text);
    if (text) { host.style.setProperty("--u", unit()); tagW = tag.offsetWidth || 80 * unit(); }
  };

  const wake = () => {
    const fresh = !host || !host.isConnected;
    if (!mount()) return false;
    if (fresh) {
      // Taking over from an earlier cursor: appear at once instead of fading in.
      dot.style.transition = "none";
      dot.classList.add("on");
      requestAnimationFrame(() => requestAnimationFrame(() => { dot.style.transition = ""; }));
    }
    dot.classList.add("on");
    dot.classList.remove("dim");
    clearTimeout(idle);
    // Between steps the cursor stays, but fades back so it doesn't cover the page.
    idle = setTimeout(() => { dot.classList.add("dim"); tag.classList.remove("on"); }, 4000);
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
    dur: 0, // how long the last move or click takes, in ms, for a caller that waits on its own clock

    /** Where the cursor was left by the last step, so the next one starts there. */
    at(x, y) { if (this.x < 0 && x >= 0) { const k = keep(x, y); this.x = k[0]; this.y = k[1]; } },

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
          if (!wake()) return done();
          // A target out of view sends the cursor to the nearest edge, labelled with the way to it.
          const out = where(x, y);
          if (out) [x, y] = keep(x, y);
          if (this.x < 0) { const k = keep(x + 220, y - 140); this.x = k[0]; this.y = k[1]; place(this.x, this.y); }
          if (text !== undefined && text !== null) label(text && out ? text + " " + out : text);
          const fx0 = this.x, fy0 = this.y;
          const dist = Math.hypot(x - fx0, y - fy0);
          const dur = still ? 0 : ms || Math.max(220, Math.min(720, 160 + dist * 0.55));
          if (dur === 0 || dist < 1) { this.x = x; this.y = y; place(x, y); return done(); }
          this.dur = dur;
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
      this.dur = still ? 0 : 140;
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

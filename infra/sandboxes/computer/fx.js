// gbot-fx: the bot's visible hand. A cursor that glides to what the bot is
// about to use (with a soft glow and a short light trail while it moves), a
// ring that locks onto the target, a double ripple on click, a glow while it
// types, a scan line when it reads a page, and a short label that says what
// it is doing. It is drawn inside the page, so it shows up in any
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
  let host = null, root = null, dot = null, halo = null, tag = null, ring = null, toast = null, scanner = null;
  let trail = [], newHost = false;
  let idle = 0, ringTimer = 0, toastTimer = 0, scanTimer = 0, tagW = 80, ringOn = false;
  const TRAIL = still ? 0 : 8;

  // Sizes are in screen units, not page units: --u is how many page pixels make one on-screen
  // pixel. On a phone a page is laid out wide and shrunk to fit (scale about 0.4), so a fixed
  // 15px dot would come out about 6 on screen and a 12px label unreadable.
  // Look and feel follow the website's computer demo and the app's tokens (packages/ui-tokens):
  // the same live purple, card surface, soft highlight, "Live"-style pill with a pulsing dot.
  const css = `
    :host { all: initial; position: fixed; inset: 0; width: 100%; height: 100%; margin: 0; padding: 0; border: 0;
      background: transparent; overflow: visible; z-index: 2147483647; pointer-events: none; contain: layout style; --u: 1;
      --live: ${LIVE}; --live-soft: rgba(139, 92, 246, .12); --live-glow: rgba(139, 92, 246, .42); --live-flash: rgba(139, 92, 246, .26);
      --live-line: rgba(139, 92, 246, .5); --card: #fff;
      --pop-shadow: 0 0 0 .5px rgba(0, 0, 0, .1), 0 12px 32px -8px rgba(0, 0, 0, .18);
      --sans: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", "Helvetica Neue", Arial, sans-serif; }
    @media (prefers-color-scheme: dark) { :host { --live-soft: rgba(139, 92, 246, .18); --live-glow: rgba(167, 139, 250, .5); --card: #222;
      --pop-shadow: 0 0 0 .5px rgba(255, 255, 255, .12), 0 16px 40px -8px rgba(0, 0, 0, .6); } }
    * { box-sizing: border-box; }
    .dot { position: fixed; left: 0; top: 0; width: calc(15px * var(--u)); height: calc(15px * var(--u));
      margin: calc(-7.5px * var(--u)) 0 0 calc(-7.5px * var(--u)); border-radius: 50%;
      background: var(--live); border: calc(2.5px * var(--u)) solid var(--card);
      box-shadow: 0 calc(2px * var(--u)) calc(8px * var(--u)) rgba(0,0,0,.25), 0 0 0 calc(3px * var(--u)) var(--live-soft); opacity: 0;
      transition: opacity .2s, scale .12s cubic-bezier(.2, .9, .3, 1.15), box-shadow .2s; will-change: transform; }
    .dot.on { opacity: 1; }
    .dot.dim { opacity: .72; }
    .dot.press { scale: .78; }
    .dot.moving { box-shadow: 0 calc(3px * var(--u)) calc(12px * var(--u)) rgba(0,0,0,.28), 0 0 0 calc(5px * var(--u)) var(--live-soft); }
    /* A soft glow under the dot that breathes while the bot works and swells as it moves. */
    .halo { position: fixed; left: 0; top: 0; width: calc(46px * var(--u)); height: calc(46px * var(--u));
      margin: calc(-23px * var(--u)) 0 0 calc(-23px * var(--u)); border-radius: 50%; opacity: 0;
      background: radial-gradient(closest-side, var(--live-glow), transparent); transition: opacity .3s; will-change: transform; }
    .halo.on { opacity: .55; }
    .halo.moving { opacity: .9; }
    .halo > i { display: block; width: 100%; height: 100%; border-radius: 50%; background: inherit; animation: breathe 2.4s ease-in-out infinite; }
    /* The light trail: a few fading dots that follow the cursor's path while it glides. */
    .trail { position: fixed; left: 0; top: 0; width: calc(11px * var(--u)); height: calc(11px * var(--u));
      margin: calc(-5.5px * var(--u)) 0 0 calc(-5.5px * var(--u)); border-radius: 50%; background: var(--live);
      opacity: 0; transition: opacity .18s; will-change: transform; }
    .tag, .toast { background: var(--card); color: var(--live); font: 600 calc(12.5px * var(--u))/1.2 var(--sans); white-space: nowrap; box-shadow: var(--pop-shadow); }
    .tag::before, .toast::before { content: ""; display: inline-block; vertical-align: middle; margin-right: calc(7px * var(--u)); width: calc(7px * var(--u)); height: calc(7px * var(--u)); border-radius: 50%;
      background: var(--live); animation: live 1.8s ease-in-out infinite; }
    /* Blocks, not flex boxes, so long labels and notes end in an ellipsis. */
    .tag { display: block; position: fixed; left: 0; top: 0; max-width: min(calc(260px * var(--u)), 70vw); overflow: hidden; text-overflow: ellipsis;
      padding: calc(4px * var(--u)) calc(10px * var(--u)) calc(4px * var(--u)) calc(9px * var(--u)); border-radius: 999px;
      opacity: 0; scale: .92; transform-origin: 0 0; transition: opacity .16s, scale .22s cubic-bezier(.2, .9, .3, 1.2); will-change: transform; }
    .tag.on { opacity: 1; scale: 1; }
    .tag.swap { animation: swap .26s cubic-bezier(.2, .9, .3, 1.2); }
    /* The ring locks onto a target: it starts a little wide and snaps in, a light sweeps across it once,
       and from one target to the next it slides instead of blinking. */
    .ring { position: fixed; left: 0; top: 0; border: calc(2px * var(--u)) solid var(--live); background: var(--live-soft); border-radius: calc(8px * var(--u)); opacity: 0;
      overflow: hidden; transition: opacity .2s, transform .26s cubic-bezier(.2, .8, .2, 1), width .26s cubic-bezier(.2, .8, .2, 1), height .26s cubic-bezier(.2, .8, .2, 1), box-shadow .2s, background-color .2s; }
    .ring.on { opacity: 1; }
    .ring.lock { animation: lock .32s cubic-bezier(.2, .9, .3, 1.1); }
    .ring.lock::after { content: ""; position: absolute; inset: 0; background: linear-gradient(105deg, transparent 30%, rgba(255,255,255,.55) 50%, transparent 70%);
      translate: -110% 0; animation: sweep .62s .08s cubic-bezier(.3, .6, .2, 1) forwards; }
    .ring.flash { background-color: var(--live-flash); box-shadow: 0 0 0 calc(4px * var(--u)) var(--live-soft); }
    .ring.typing { animation: typing 1.1s ease-in-out infinite; }
    .pulse { position: fixed; left: 0; top: 0; width: calc(15px * var(--u)); height: calc(15px * var(--u)); margin: calc(-7.5px * var(--u)) 0 0 calc(-7.5px * var(--u)); border-radius: 50%;
      border: calc(2px * var(--u)) solid var(--live); opacity: .9; animation: ripple .5s cubic-bezier(.2, .7, .2, 1) forwards; }
    .pulse.late { animation: ripple .62s .09s cubic-bezier(.2, .7, .2, 1) forwards; opacity: 0; border-width: calc(1.5px * var(--u)); }
    .pulse.fill { border: 0; background: var(--live-soft); animation: ripple-fill .4s cubic-bezier(.2, .7, .2, 1) forwards; }
    /* Reading a page: one soft band of light runs down what is in view. */
    .scan { position: fixed; left: 0; right: 0; top: 0; height: calc(120px * var(--u)); opacity: 0; pointer-events: none;
      background: linear-gradient(to bottom, transparent, var(--live-soft) 70%, var(--live-line) 98%, transparent);
      will-change: transform, opacity; }
    .scan.run { animation: scan .72s cubic-bezier(.4, .1, .3, 1) forwards; }
    @keyframes ripple { from { scale: 1; opacity: .9; } to { scale: 3.6; opacity: 0; } }
    @keyframes ripple-fill { from { scale: .6; opacity: 1; } to { scale: 2.6; opacity: 0; } }
    @keyframes live { 0%, 100% { transform: scale(1); opacity: 1; } 50% { transform: scale(.72); opacity: .75; } }
    @keyframes breathe { 0%, 100% { transform: scale(.86); } 50% { transform: scale(1.08); } }
    @keyframes lock { from { scale: 1.14; opacity: 0; } to { scale: 1; opacity: 1; } }
    @keyframes sweep { to { translate: 110% 0; } }
    @keyframes swap { from { scale: .94; } to { scale: 1; } }
    @keyframes typing { 0%, 100% { box-shadow: 0 0 0 0 var(--live-soft); } 50% { box-shadow: 0 0 0 calc(5px * var(--u)) var(--live-soft); } }
    @keyframes scan { 0% { translate: 0 calc(-120px * var(--u)); opacity: 0; } 12% { opacity: 1; } 85% { opacity: .9; } 100% { translate: 0 var(--scan-h, 100vh); opacity: 0; } }
    .toast { display: block; position: fixed; left: 50%; bottom: calc(22px * var(--u)); translate: -50% 0; padding: calc(7px * var(--u)) calc(14px * var(--u)) calc(7px * var(--u)) calc(12px * var(--u));
      border-radius: 999px; opacity: 0; transform: translateY(8px) scale(.96); transition: opacity .22s, transform .3s cubic-bezier(.2, .9, .3, 1.15); max-width: 80vw;
      overflow: hidden; text-overflow: ellipsis; }
    .toast.on { opacity: 1; transform: none; }
    @media (prefers-reduced-motion: reduce) { .halo > i, .ring.lock, .ring.lock::after, .ring.typing, .tag.swap { animation: none; } }
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
  const keep = (x, y, margin = M, v = view()) => {
    const m = margin * unit();
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

  const ensureTopLayer = (promote = false) => {
    try {
      if (!host || typeof host.showPopover !== "function") return;
      if (host.matches(":popover-open")) {
        if (!promote) return;
        host.hidePopover();
      }
      host.showPopover();
    } catch (e) {}
  };

  const mount = (promote = false) => {
    const parent = document.documentElement;
    if (!parent) return false;
    if (host && host.isConnected) { ensureTopLayer(promote); return true; }
    // A step run earlier left its own cursor in the page; this one takes over from it.
    document.querySelectorAll("[data-gbot-fx]").forEach((h) => h.remove());
    host = document.createElement("div");
    host.setAttribute("data-gbot-fx", "");
    if (typeof host.showPopover === "function") host.setAttribute("popover", "manual");
    root = host.attachShadow({ mode: "closed" });
    shownUnit = 0;
    newHost = true;
    const style = document.createElement("style");
    style.textContent = css;
    dot = document.createElement("div"); dot.className = "dot";
    halo = document.createElement("div"); halo.className = "halo"; halo.appendChild(document.createElement("i"));
    tag = document.createElement("div"); tag.className = "tag";
    ring = document.createElement("div"); ring.className = "ring"; ringOn = false;
    toast = document.createElement("div"); toast.className = "toast";
    scanner = document.createElement("div"); scanner.className = "scan";
    trail = [];
    for (let i = 0; i < TRAIL; i++) {
      const t = document.createElement("div"); t.className = "trail";
      t.style.scale = String(1 - i * 0.09);
      trail.push(t);
    }
    root.append(style, scanner, ring, halo, ...trail.slice().reverse(), dot, tag, toast);
    setUnit(unit());
    parent.appendChild(host);
    ensureTopLayer();
    if (fx.x >= 0 && fx.y >= 0) place(fx.x, fx.y);
    return true;
  };

  // Set only when it changes: a style write on the host every frame would look like the page changing.
  let shownUnit = 0;
  const setUnit = (u) => { if (u !== shownUnit) { shownUnit = u; host.style.setProperty("--u", u); } };
  const place = (px, py) => {
    if (!dot) return;
    // Keep the dot fully inside the visible viewport; targets outside pull to the margin.
    const u = unit(), v = view();
    setUnit(u);
    const [x, y] = keep(px, py, inside(px, py) ? EDGE : M, v);
    const at = `translate(${x}px, ${y}px)`;
    dot.style.transform = at;
    halo.style.transform = at;
    // The label sits down and to the right of the dot, flips at edges, and is clamped on both axes.
    const m = 6 * u;
    const w = Math.min(tagW, Math.max(0, (v.r - v.l) - m * 2));
    const gx = 16 * u, gy = 14 * u, th = 28 * u;
    const rawLeft = x + gx + w > v.r - m ? x - gx - w : x + gx;
    const rawTop = y + gy + th > v.b - m ? y - gy - th : y + gy;
    const left = Math.min(Math.max(v.l + m, rawLeft), Math.max(v.l + m, v.r - m - w));
    const top = Math.min(Math.max(v.t + m, rawTop), Math.max(v.t + m, v.b - m - th));
    tag.style.transform = `translate(${left}px, ${top}px)`;
    return [x, y];
  };
  // The light trail: dots on the glide's own curve, a few milliseconds behind the cursor each,
  // so they make one tapered streak whatever the frame rate, and shrink into the dot as it lands.
  const trace = (pts, on) => {
    trail.forEach((t, i) => {
      const p = pts && pts[i];
      if (p) t.style.transform = `translate(${p[0]}px, ${p[1]}px)`;
      t.style.opacity = on && p ? String((0.5 - i * 0.055).toFixed(3)) : "0";
    });
  };
  const moving = (on) => {
    dot.classList.toggle("moving", on);
    halo.classList.toggle("moving", on);
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
    const changed = (text || "") !== tag.textContent;
    tag.textContent = text || "";
    tag.classList.toggle("on", !!text);
    if (changed && text && !still) { tag.classList.remove("swap"); void tag.offsetWidth; tag.classList.add("swap"); }
    if (text) {
      const u = unit();
      setUnit(u);
      tagW = Math.ceil(tag.getBoundingClientRect().width) || Math.round(80 * u);
    }
  };

  const wake = () => {
    if (!mount(true)) return false;
    const fresh = newHost;
    newHost = false;
    if (fx.x < 0 || fx.y < 0) {
      const h = home();
      fx.x = h[0]; fx.y = h[1];
      place(fx.x, fx.y);
    }
    if (fresh) {
      // Taking over from an earlier cursor: appear at once instead of fading in.
      for (const el of [dot, halo, tag]) el.style.transition = "none";
      dot.classList.add("on");
      halo.classList.add("on");
      requestAnimationFrame(() => requestAnimationFrame(() => { for (const el of [dot, halo, tag]) el.style.transition = ""; }));
    }
    dot.classList.add("on");
    dot.classList.remove("dim");
    halo.classList.add("on");
    clearTimeout(idle);
    // Between steps the cursor stays visible; after a pause it softens slightly and clears the label.
    idle = setTimeout(() => { dot.classList.add("dim"); halo.classList.remove("on"); tag.classList.remove("on"); }, 12000);
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

  // Moves scroll boxes from their positions to new ones over a short eased glide, played by
  // drawn frames like the cursor. A page's own `scroll-behavior: smooth` is held off meanwhile
  // so it doesn't fight each step. Returns the planned length in ms.
  let scrollRun = 0;
  const glideScroll = (boxes, from, to) => {
    const set = (n, p) => { n.scrollLeft = p[0]; n.scrollTop = p[1]; };
    const dist = Math.max(0, ...boxes.map((n, i) => Math.hypot(to[i][0] - from[i][0], to[i][1] - from[i][1])));
    const run = ++scrollRun;
    if (dist < 1 || still) { boxes.forEach((n, i) => set(n, to[i])); return 0; }
    const saved = boxes.map((n) => n.style.scrollBehavior);
    boxes.forEach((n, i) => { n.style.scrollBehavior = "auto"; set(n, from[i]); });
    const dur = Math.max(260, Math.min(620, 200 + dist * 0.28));
    let played = 0, last = 0, over = false;
    const finish = () => {
      if (over) return;
      over = true;
      if (run === scrollRun) boxes.forEach((n, i) => set(n, to[i]));
      boxes.forEach((n, i) => { n.style.scrollBehavior = saved[i]; });
    };
    const inOut = (k) => (k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2);
    const step = (now) => {
      if (over || run !== scrollRun) return finish();
      played += last ? Math.min(now - last, MAX_FRAME) : 0;
      last = now;
      const k = Math.min(1, played / dur), e = inOut(k);
      boxes.forEach((n, i) => set(n, [from[i][0] + (to[i][0] - from[i][0]) * e, from[i][1] + (to[i][1] - from[i][1]) * e]));
      k < 1 ? requestAnimationFrame(step) : finish();
    };
    requestAnimationFrame(step);
    setTimeout(finish, dur + SLACK);
    return dur;
  };

  // The ring waiting to lock on (set by ring(), shown during or after a glide).
  let pendingRing = null, glides = 0;
  const showRing = () => {
    const box = pendingRing;
    if (!box || !ring) return;
    pendingRing = null;
    const fresh = !ringOn;
    if (fresh) {
      // Appearing: jump to the box with no slide, then lock on.
      ring.style.transition = "none";
      ring.style.transform = `translate(${box.l}px, ${box.t}px)`;
      ring.style.width = box.w + "px"; ring.style.height = box.h + "px";
      void ring.offsetWidth;
      ring.style.transition = "";
    } else {
      ring.style.transform = `translate(${box.l}px, ${box.t}px)`;
      ring.style.width = box.w + "px"; ring.style.height = box.h + "px";
    }
    ring.classList.add("on");
    ringOn = true;
    if (!still) { ring.classList.remove("lock"); void ring.offsetWidth; ring.classList.add("lock"); }
    clearTimeout(ringTimer);
    ringTimer = setTimeout(() => { ring.classList.remove("on", "typing"); ringOn = false; }, 2400);
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

    /** Outline a box (viewport coordinates) until the next step. During a glide it locks on
     *  as the cursor gets close; from one target to the next it slides across. */
    ring(r) {
      try {
        if (!mount()) return;
        clearTimeout(ringTimer);
        pendingRing = null;
        if (!r) { ring.classList.remove("on", "typing"); ringOn = false; return; }
        // Only the part that is in view is outlined.
        const v = view(), pad = 3 * unit();
        const l = Math.max(r.x - pad, v.l + 2), t = Math.max(r.y - pad, v.t + 2);
        const rt = Math.min(r.x + r.width + pad, v.r - 2), bt = Math.min(r.y + r.height + pad, v.b - 2);
        if (rt <= l || bt <= t) { ring.classList.remove("on", "typing"); ringOn = false; return; }
        const box = { l, t, w: rt - l, h: bt - t };
        pendingRing = box;
        // Called before a glide, the glide shows it on the way; on its own, it shows now.
        Promise.resolve().then(() => { if (pendingRing === box && !fx.moving) showRing(); });
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
          // Quick and decisive: a short hop is about a sixth of a second, crossing the screen under half.
          const dur = still ? 0 : ms || Math.max(150, Math.min(460, 120 + dist * 0.38));
          if (dur === 0 || dist < 1) { this.x = x; this.y = y; place(x, y); showRing(); return done(); }
          this.dur = dur;
          // A slight arc, like a hand, not a ruler.
          const bend = Math.min(48, dist * 0.1) * (x >= fx0 ? -1 : 1);
          const cx = (fx0 + x) / 2 + (-(y - fy0) / dist) * bend;
          const cy = (fy0 + y) / 2 + ((x - fx0) / dist) * bend;
          // The glide plays by the frames that are drawn, not by the wall clock. A page busy
          // laying out (the first drawing of the cursor, a big page, a slow phone) can stall a
          // frame for 100 ms or more; on a wall clock the cursor would cover most of its path
          // unseen and appear to jump. Here a stalled frame advances it by about one frame,
          // so a stall only pauses it. `moving` says when it has really arrived.
          const curve = (e) => { const u = 1 - e; return [u * u * fx0 + 2 * u * e * cx + e * e * x, u * u * fy0 + 2 * u * e * cy + e * e * y]; };
          // Arc length along the curve, sampled once, to space the trail by distance.
          const arcs = [0];
          for (let i = 1, p = curve(0); i <= 48; i++) { const q = curve(i / 48); arcs.push(arcs[i - 1] + Math.hypot(q[0] - p[0], q[1] - p[1])); p = q; }
          const arcAt = (e) => { const f = e * 48, i = Math.min(47, Math.floor(f)); return arcs[i] + (arcs[i + 1] - arcs[i]) * (f - i); };
          const eAt = (len) => {
            let i = 0;
            while (i < 47 && arcs[i + 1] < len) i++;
            const span = arcs[i + 1] - arcs[i];
            return (i + (span > 0 ? (len - arcs[i]) / span : 0)) / 48;
          };
          let played = 0, last = 0;
          let finished = false;
          this.moving = true;
          moving(true);
          const glide = ++glides;
          const end = () => {
            if (finished) return;
            finished = true;
            if (glide !== glides) return done(); // a newer glide took over
            this.moving = false; this.x = x; this.y = y;
            place(x, y); moving(false); trace(null, false); showRing(); done();
          };
          const step = (now) => {
            if (finished) return;
            if (glide !== glides) return end();
            played += last ? Math.min(now - last, MAX_FRAME) : 0;
            last = now;
            const k = Math.min(1, played / dur), e = ease(k);
            [this.x, this.y] = curve(e);
            place(this.x, this.y);
            if (TRAIL) {
              // Evenly spaced along the path just travelled, a few screen pixels apart, and
              // drawn in towards the dot as it lands so the streak shrinks into it.
              const v = view(), gap = 7 * unit() * (k < 0.8 ? 1 : Math.max(0, (1 - k) / 0.2));
              const here = arcAt(e), pts = [];
              for (let i = 1; i <= TRAIL; i++) { const p = curve(eAt(Math.max(0, here - i * gap))); pts.push(keep(p[0], p[1], EDGE, v)); }
              trace(pts, k < 0.97);
            }
            k < 1 ? requestAnimationFrame(step) : end();
          };
          requestAnimationFrame(step);
          // A background tab pauses animation frames; never hold the bot's step for it.
          setTimeout(end, dur + SLACK);
        } catch (e) { done(); }
      });
    },

    /** Press: the dot squishes, two rings spread from it and the target flashes. */
    click() {
      this.dur = still ? 0 : 90;
      return new Promise((done) => {
        try {
          if (!wake()) return done();
          showRing();
          dot.classList.add("press");
          const [px, py] = keep(this.x, this.y, EDGE);
          for (const kind of still ? ["pulse"] : ["pulse fill", "pulse", "pulse late"]) {
            const p = document.createElement("div");
            p.className = kind;
            p.style.transform = `translate(${px}px, ${py}px)`;
            root.appendChild(p);
            setTimeout(() => p.remove(), 800);
          }
          if (ringOn) { ring.classList.add("flash"); setTimeout(() => ring.classList.remove("flash"), 220); }
          setTimeout(() => { dot.classList.remove("press"); done(); }, still ? 0 : 110);
        } catch (e) { done(); }
      });
    },

    /** While the bot types into the ringed field, the ring glows in time and stays up. */
    typing(on) {
      try {
        if (!mount()) return;
        showRing();
        ring.classList.toggle("typing", !!on && !still);
        clearTimeout(ringTimer);
        if (!on) ringTimer = setTimeout(() => { ring.classList.remove("on"); ringOn = false; }, 1600);
      } catch (e) {}
    },

    /** Reading the page: a soft band of light runs down what is in view, once. It never waits. */
    scan() {
      try {
        if (still || !mount()) return;
        const v = view();
        Object.assign(scanner.style, { left: v.l + "px", width: v.r - v.l + "px", top: v.t + "px" });
        scanner.style.setProperty("--scan-h", v.b - v.t + 120 * unit() + "px");
        scanner.classList.remove("run");
        void scanner.offsetWidth;
        scanner.classList.add("run");
        clearTimeout(scanTimer);
        scanTimer = setTimeout(() => scanner.classList.remove("run"), 900);
      } catch (e) {}
    },

    /** Fade everything out, for a page the bot is done with. */
    hide() {
      try {
        if (!host) return;
        dot.classList.remove("on"); halo.classList.remove("on"); tag.classList.remove("on");
        ring.classList.remove("on", "typing"); ringOn = false; toast.classList.remove("on"); scanner.classList.remove("run");
        trace(null, false);
      } catch (e) {}
    },

    /** Scroll an element into view, smoothly, by the cursor's own frames rather than the
     *  browser's smooth scrolling (which some pages, browsers and WebViews skip or jump). The
     *  end positions are worked out by an instant scrollIntoView that is undone before the page
     *  paints, so nested scroll areas move too. Returns how long it takes, in ms. */
    reveal(el, block, inline) {
      try {
        const root = document.scrollingElement || document.documentElement;
        const boxes = [];
        for (let n = el.parentElement; n; n = n.parentElement) {
          if (n === root || n.scrollHeight > n.clientHeight + 1 || n.scrollWidth > n.clientWidth + 1) boxes.push(n);
        }
        if (!boxes.includes(root)) boxes.push(root);
        const from = boxes.map((n) => [n.scrollLeft, n.scrollTop]);
        el.scrollIntoView({ block: block || "center", inline: inline || "nearest", behavior: "instant" });
        const to = boxes.map((n) => [n.scrollLeft, n.scrollTop]);
        return glideScroll(boxes, from, to);
      } catch (e) {
        try { el.scrollIntoView({ block: block || "center", inline: inline || "nearest" }); } catch (e2) {}
        return 0;
      }
    },

    /** Scroll the page by dy (or to the top or bottom with "top" / "bottom"), smoothly. */
    scrollPage(dy) {
      try {
        const root = document.scrollingElement || document.documentElement;
        const max = Math.max(0, root.scrollHeight - root.clientHeight);
        const y = dy === "top" ? 0 : dy === "bottom" ? max : Math.min(max, Math.max(0, root.scrollTop + Number(dy || 0)));
        return glideScroll([root], [[root.scrollLeft, root.scrollTop]], [[root.scrollLeft, y]]);
      } catch (e) { return 0; }
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

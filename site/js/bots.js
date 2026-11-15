// The crew, in three dimensions. Each bot is the flat character from the
// app given a little thickness: the same outline inflated into a soft
// token, the same pill eyes, the same moods. Nothing here is a model
// file; every body is built from the outline in shapes.js.

import {
  BufferAttribute, BufferGeometry, CanvasTexture, CircleGeometry, Color, DirectionalLight, Group,
  HemisphereLight, Mesh, MeshBasicMaterial, MeshStandardMaterial, PerspectiveCamera, PlaneGeometry,
  Raycaster, RingGeometry, Scene, Shape, ShapeGeometry, ShapeUtils, Vector2, Vector3, WebGLRenderer,
} from "three";
import { EYE, bodies, eyeSizes, hues, pose } from "./shapes.js";

const U = 0.01;        // one unit of the 100-box, in world units
const HALF = 0.19;     // half a body's thickness
const BEVEL = 0.1;     // how round its edge is
const GRAVITY = 17;
const TAU = Math.PI * 2;
const DEG = Math.PI / 180;

const clamp = (v, a = -1, b = 1) => Math.max(a, Math.min(b, v));
const ease = (rate, dt) => 1 - Math.exp(-rate * dt);
const rand = (a, b) => a + Math.random() * (b - a);

// ---------- geometry ----------

// The outline standing upright: y up, feet on the floor, centered on x.
function outline(shape) {
  const b = bodies[shape];
  let floor = -Infinity;
  for (const p of b.pts) floor = Math.max(floor, p[1]);
  const pts = b.pts.map(([x, y]) => [(x - 50) * U, (floor - y) * U]);
  let area = 0;
  for (let i = 0; i < pts.length; i++) {
    const a = pts[i], c = pts[(i + 1) % pts.length];
    area += a[0] * c[1] - c[0] * a[1];
  }
  if (area < 0) pts.reverse();
  let height = 0;
  for (const p of pts) height = Math.max(height, p[1]);
  return { pts, height, face: [(b.face[0] - 50) * U, (floor - b.face[1]) * U], eyeScale: b.eyeScale };
}

// A flat-faced token with a fully rounded rim. Normals are analytic, so
// the rim catches light as one smooth curve and meets the face cleanly.
function pillow(pts, height) {
  const n = pts.length;
  const steps = 7;
  const dir = pts.map((p, i) => {
    const a = pts[(i - 1 + n) % n], c = pts[(i + 1) % n];
    let e1x = p[0] - a[0], e1y = p[1] - a[1], e2x = c[0] - p[0], e2y = c[1] - p[1];
    const l1 = Math.hypot(e1x, e1y) || 1, l2 = Math.hypot(e2x, e2y) || 1;
    e1x /= l1; e1y /= l1; e2x /= l2; e2y /= l2;
    let mx = e1y + e2y, my = -e1x - e2x;
    const lm = Math.hypot(mx, my) || 1;
    mx /= lm; my /= lm;
    // Keep the inset an even distance from both edges at a corner.
    const k = 1 / Math.max(0.72, mx * e1y + my * -e1x);
    return [mx, my, k];
  });

  const profile = [];
  for (let j = 0; j <= steps; j++) {
    const a = (Math.PI / 2) * (1 - j / steps);
    profile.push([BEVEL * (Math.cos(a) - 1), HALF - BEVEL + BEVEL * Math.sin(a), Math.cos(a), Math.sin(a)]);
  }
  for (let j = 0; j <= steps; j++) {
    const a = (-Math.PI / 2) * (j / steps);
    profile.push([BEVEL * (Math.cos(a) - 1), -(HALF - BEVEL) + BEVEL * Math.sin(a), Math.cos(a), Math.sin(a)]);
  }

  const rings = profile.length;
  const position = new Float32Array(rings * n * 3);
  const normal = new Float32Array(rings * n * 3);
  const color = new Float32Array(rings * n * 3);
  for (let j = 0; j < rings; j++) {
    const [r, z, nr, nz] = profile[j];
    for (let i = 0; i < n; i++) {
      const o = (j * n + i) * 3;
      const [mx, my, k] = dir[i];
      const x = pts[i][0] + mx * k * r, y = pts[i][1] + my * k * r;
      position[o] = x; position[o + 1] = y; position[o + 2] = z;
      normal[o] = mx * nr; normal[o + 1] = my * nr; normal[o + 2] = nz;
      // Light from above, easing into shade below, as on the flat rig.
      const t = clamp(y / height, 0, 1);
      const v = 0.8 + 0.2 * t * (2 - t);
      color[o] = v; color[o + 1] = v; color[o + 2] = v;
    }
  }

  const index = [];
  for (let j = 0; j < rings - 1; j++) {
    for (let i = 0; i < n; i++) {
      const a = j * n + i, b = j * n + ((i + 1) % n), c = (j + 1) * n + i, d = (j + 1) * n + ((i + 1) % n);
      index.push(a, c, d, a, d, b);
    }
  }
  const front = [];
  for (let i = 0; i < n; i++) front.push(new Vector2(position[i * 3], position[i * 3 + 1]));
  const back = (rings - 1) * n;
  for (const [a, b, c] of ShapeUtils.triangulateShape(front, [])) {
    const ccw = (front[b].x - front[a].x) * (front[c].y - front[a].y) - (front[b].y - front[a].y) * (front[c].x - front[a].x) > 0;
    const [p, q, r] = ccw ? [a, b, c] : [a, c, b];
    index.push(p, q, r, back + p, back + r, back + q);
  }

  const g = new BufferGeometry();
  g.setAttribute("position", new BufferAttribute(position, 3));
  g.setAttribute("normal", new BufferAttribute(normal, 3));
  g.setAttribute("color", new BufferAttribute(color, 3));
  g.setIndex(index);
  g.computeBoundingSphere();
  return g;
}

function eyeGeometry(style) {
  const [w, h] = eyeSizes[style].map((v) => v * U);
  const r = Math.min(w, h) / 2, x = w / 2, y = h / 2;
  const s = new Shape();
  s.moveTo(-x + r, -y);
  s.lineTo(x - r, -y);
  s.absarc(x - r, -y + r, r, -Math.PI / 2, 0, false);
  s.lineTo(x, y - r);
  s.absarc(x - r, y - r, r, 0, Math.PI / 2, false);
  s.lineTo(-x + r, y);
  s.absarc(-x + r, y - r, r, Math.PI / 2, Math.PI, false);
  s.lineTo(-x, -y + r);
  s.absarc(-x + r, -y + r, r, Math.PI, Math.PI * 1.5, false);
  return new ShapeGeometry(s, 10);
}

// The closed, smiling eye of a bot that has just finished something.
const SMILE_R = 7.4 * U, SMILE_T = 3 * U, SMILE_A = 19 * DEG;
let smileParts;
function smile(material) {
  smileParts ||= {
    ring: new RingGeometry(SMILE_R - SMILE_T, SMILE_R + SMILE_T, 24, 1, SMILE_A, Math.PI - 2 * SMILE_A),
    cap: new CircleGeometry(SMILE_T, 16),
  };
  const g = new Group();
  g.add(new Mesh(smileParts.ring, material));
  for (const a of [SMILE_A, Math.PI - SMILE_A]) {
    const cap = new Mesh(smileParts.cap, material);
    cap.position.set(Math.cos(a) * SMILE_R, Math.sin(a) * SMILE_R, 0);
    g.add(cap);
  }
  // Sit the arc where the open eye was.
  for (const m of g.children) m.position.y -= 5.4 * U;
  return g;
}

let shadowTexture;
function shadowMap() {
  if (shadowTexture) return shadowTexture;
  const c = document.createElement("canvas");
  c.width = c.height = 128;
  const ctx = c.getContext("2d");
  const g = ctx.createRadialGradient(64, 64, 0, 64, 64, 64);
  g.addColorStop(0, "rgba(0,0,0,1)");
  g.addColorStop(0.55, "rgba(0,0,0,.38)");
  g.addColorStop(1, "rgba(0,0,0,0)");
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 128, 128);
  shadowTexture = new CanvasTexture(c);
  return shadowTexture;
}

const geometryCache = new Map();
const cached = (key, make) => {
  if (!geometryCache.has(key)) geometryCache.set(key, make());
  return geometryCache.get(key);
};

// ---------- one bot ----------

class Spring {
  constructor(k, c) { this.k = k; this.c = c; this.x = 0; this.v = 0; this.to = 0; }
  step(dt) {
    this.v += (-this.k * (this.x - this.to) - this.c * this.v) * dt;
    this.x += this.v * dt;
  }
}

const eyeMaterial = new MeshBasicMaterial({ color: EYE });
const shadowGeometry = new PlaneGeometry(1, 1);

export class Bot {
  constructor({ name = "", shape, hue, eyes, size = 1 }) {
    this.name = name;
    this.size = size;
    this.mood = "idle";
    this.phase = Math.random() * 10;
    this.gaze = { x: 0, y: 0 };
    this.gazeTo = { x: 0, y: 0 };
    this.gazeRate = rand(5, 9);
    this.y = 0;
    this.vy = 0;
    this.air = false;
    this.squash = new Spring(210, 13);
    this.lean = 0;
    this.lift = 0;
    this.hover = 0;
    this.hovered = false;
    this.blink = 0;
    this.nextBlink = rand(1, 4);
    this.happy = 0;
    this.jumpAt = -1;
    this.eye = [-1, 1].map(() => ({ tx: 0, ty: 0, sx: 1, sy: 1, rot: 0 }));
    this.tint = new Color(hues[hue]);

    this.root = new Group();
    this.root.scale.setScalar(size);
    this.shadow = new Mesh(
      shadowGeometry,
      new MeshBasicMaterial({ map: shadowMap(), color: 0x000000, transparent: true, opacity: 0.2, depthWrite: false }),
    );
    this.shadow.rotation.x = -Math.PI / 2;
    this.shadow.position.y = 0.002;
    this.shadow.renderOrder = -1;
    this.pivot = new Group();
    this.material = new MeshStandardMaterial({ color: this.tint.clone(), vertexColors: true, roughness: 0.52, metalness: 0 });
    this.body = new Mesh(new BufferGeometry(), this.material);
    this.body.userData.bot = this;
    this.face = new Group();
    this.lids = new Group();
    this.face.add(this.lids);
    this.pivot.add(this.body, this.face);
    this.root.add(this.shadow, this.pivot);
    this.setLook({ shape, hue, eyes });
  }

  setLook({ shape = this.shape, hue = this.hue, eyes = this.eyes }) {
    if (shape !== this.shape) {
      this.shape = shape;
      this.outline = cached(`o:${shape}`, () => outline(shape));
      this.body.geometry = cached(`b:${shape}`, () => pillow(this.outline.pts, this.outline.height));
      this.eyes = null;
    }
    if (eyes !== this.eyes) {
      this.eyes = eyes;
      this.lids.clear();
      const s = this.outline.eyeScale;
      const gap = 12 * s * U;
      this.open = [];
      this.shut = [];
      for (const side of [-1, 1]) {
        const open = new Mesh(cached(`e:${eyes}`, () => eyeGeometry(eyes)), eyeMaterial);
        const shut = smile(eyeMaterial);
        open.userData.x = shut.userData.x = side * gap;
        shut.position.x = side * gap;
        shut.scale.setScalar(s);
        shut.visible = false;
        this.open.push(open);
        this.shut.push(shut);
        this.lids.add(open, shut);
      }
      this.face.position.set(this.outline.face[0], this.outline.face[1], HALF + 0.003);
    }
    if (hue !== this.hue) {
      this.hue = hue;
      this.tint.set(hues[hue]);
    }
    this.shadow.userData.w = this.shape === "pill" ? 0.86 : this.shape === "triangle" || this.shape === "arch" ? 0.92 : 0.78;
  }

  setMood(mood) {
    if (mood === this.mood) return;
    this.mood = mood;
    if (mood === "done") this.hop();
  }

  // A drop from above, for the entrance.
  drop(height, delay) {
    this.y = height;
    this.vy = 0;
    this.air = true;
    this.hold = delay;
    this.root.visible = false;
  }

  hop(power = 3.2) {
    if (this.air || this.jumpAt >= 0) return;
    this.squash.v += 2.4;      // crouch first
    this.jumpAt = 0.1;
    this.jumpPower = power;
  }

  // A pop of squash and stretch, for when its look changes.
  pop() {
    this.squash.v -= 3.2;
  }

  update(dt, t, still) {
    if (this.hold > 0) {
      this.hold -= dt;
      if (this.hold > 0) return;
      this.root.visible = true;
    }

    // Up and down.
    if (this.jumpAt >= 0) {
      this.jumpAt -= dt;
      if (this.jumpAt < 0) {
        this.vy = this.jumpPower;
        this.air = true;
        this.squash.v -= 3;
      }
    }
    if (this.air) {
      this.vy -= GRAVITY * dt;
      this.y += this.vy * dt;
      if (this.y <= 0) {
        this.squash.v += Math.min(-this.vy, 9) * 0.5;
        this.y = 0;
        this.vy = 0;
        this.air = false;
      }
    }
    this.squash.step(dt);

    // Where it looks.
    const g = ease(this.gazeRate, dt);
    this.gaze.x += (this.gazeTo.x - this.gaze.x) * g;
    this.gaze.y += (this.gazeTo.y - this.gaze.y) * g;

    // How the mood moves the body.
    const mood = this.mood;
    const p = t + this.phase;
    let lean = 0, lift = 0, breathe = 0, scan = 0;
    if (!still) {
      if (mood === "idle" || mood === "done") breathe = Math.sin((p * TAU) / 4.2);
      else if (mood === "sleepy") breathe = Math.sin((p * TAU) / 6) * 1.3;
      else if (mood === "thinking") { lean = Math.sin((p * TAU) / 3) * 4 * DEG; scan = Math.sin((p * TAU) / 3) * 2 * U; }
      else if (mood === "working") lift = Math.pow(Math.sin((p * Math.PI) / 1.15), 2);
      else if (mood === "waiting") lean = (5.5 + 1.5 * Math.sin((p * TAU) / 2.4)) * DEG;
      else if (mood === "blocked") {
        const c = (p % 2.6) / 2.6;
        if (c > 0.7 && c < 0.92) lean = Math.sin(((c - 0.7) / 0.22) * TAU * 2.5) * 5 * DEG * (1 - (c - 0.7) / 0.22);
      }
    } else if (mood === "waiting") lean = 6 * DEG;
    this.lean += (lean - this.lean) * ease(mood === "blocked" ? 40 : 7, dt);
    this.lift += (lift - this.lift) * ease(18, dt);
    this.hover += ((this.hovered ? 1 : 0) - this.hover) * ease(12, dt);

    const q = this.squash.x - this.lift * 0.014 + this.hover * -0.03;
    const sy = 1 - q - breathe * 0.012;
    const sx = 1 + q * 0.62 + breathe * 0.009;
    this.pivot.scale.set(sx, sy, sx);
    this.pivot.position.y = this.y + this.lift * 0.035 * this.outline.height;
    this.pivot.rotation.set(-this.gaze.y * 0.2, this.gaze.x * 0.3, this.lean - this.gaze.x * 0.03);

    // Colour eases when the hue changes.
    this.material.color.lerp(this.tint, ease(10, dt));

    // The shadow thins as the bot leaves the floor.
    const h = clamp(this.y / 1.6, 0, 1);
    const w = this.shadow.userData.w * (1 - h * 0.35) * sx;
    this.shadow.scale.set(w, w * 0.8, 1);
    this.shadow.material.opacity = 0.2 * (1 - h * 0.75);

    // Face: slides with the gaze, so depth reads by parallax.
    const [fx, fy] = this.outline.face;
    this.face.position.x = fx + this.gaze.x * 3 * U + scan;
    this.face.position.y = fy + this.gaze.y * 2 * U;

    // Blink.
    if (!still && mood !== "sleepy") {
      this.nextBlink -= dt;
      if (this.nextBlink < 0) {
        this.blink = 0.11;
        this.nextBlink = Math.random() < 0.2 ? 0.26 : rand(2.4, 6.5);
      }
    }
    this.blink = Math.max(0, this.blink - dt);
    this.lids.scale.y += ((this.blink > 0 ? 0.1 : 1) - this.lids.scale.y) * ease(60, dt);

    // Eyes take the mood's shape.
    const happy = mood === "done";
    this.happy += ((happy ? 1 : 0) - this.happy) * ease(16, dt);
    const s = this.outline.eyeScale;
    const k = ease(still ? 1000 : 15, dt);
    [-1, 1].forEach((side, i) => {
      const to = pose(mood, side);
      const e = this.eye[i];
      e.tx += (to.tx - e.tx) * k;
      e.ty += (to.ty - e.ty) * k;
      e.sx += (to.sx - e.sx) * k;
      e.sy += (to.sy - e.sy) * k;
      e.rot += (to.rot - e.rot) * k;
      const open = this.open[i], shut = this.shut[i];
      const wide = 1 + this.hover * 0.1;
      open.position.set(open.userData.x + (e.tx + this.gaze.x * 2.4) * U, -(e.ty - this.gaze.y * 1.6) * U, 0);
      open.rotation.z = -e.rot * DEG;
      open.scale.set(e.sx * s * wide, e.sy * s * wide * (1 - this.happy), 1);
      open.visible = this.happy < 0.96;
      shut.visible = this.happy > 0.5;
    });
  }

  dispose() {
    this.material.dispose();
    this.shadow.material.dispose();
  }
}

// ---------- a stage ----------

class Stage {
  constructor(el, { still = false } = {}) {
    this.el = el;
    this.still = still;
    this.bots = [];
    this.canvas = document.createElement("canvas");
    this.canvas.setAttribute("aria-hidden", "true");
    this.renderer = new WebGLRenderer({ canvas: this.canvas, antialias: true, alpha: true, powerPreference: "high-performance" });
    this.renderer.setClearColor(0x000000, 0);
    this.scene = new Scene();
    this.camera = new PerspectiveCamera(13, 1, 0.1, 200);
    this.scene.add(new HemisphereLight(0xffffff, 0xcfc8bd, 2.35));
    const key = new DirectionalLight(0xffffff, 1.55);
    key.position.set(-2.5, 6, 5);
    this.scene.add(key);
    const rim = new DirectionalLight(0xffffff, 0.5);
    rim.position.set(4, 2, -3);
    this.scene.add(rim);

    this.pointer = { x: 0, y: 0, at: -1e9, inside: false };
    this.ray = new Raycaster();
    this.ndc = new Vector2();
    this.tmp = new Vector3();
    this.time = 0;
    this.last = 0;
    this.visible = false;
    this.dirty = true;
    this.frame = this.frame.bind(this);

    el.prepend(this.canvas);
    this.ro = new ResizeObserver(() => this.resize());
    this.ro.observe(el);
    this.io = new IntersectionObserver(([e]) => { this.visible = e.isIntersecting; this.wake(); }, { rootMargin: "80px" });
    this.io.observe(el);
    this.onVisibility = () => this.wake();
    document.addEventListener("visibilitychange", this.onVisibility);
    this.onMove = (e) => {
      this.pointer.x = e.clientX;
      this.pointer.y = e.clientY;
      this.pointer.at = performance.now();
      this.pointer.type = e.pointerType;
      this.dirty = true;
      // Wake the loop: with motion reduced, a dirty frame is only drawn when it runs.
      this.wake();
    };
    window.addEventListener("pointermove", this.onMove, { passive: true });
    window.addEventListener("pointerdown", this.onMove, { passive: true });
    this.canvas.addEventListener("webglcontextlost", (e) => e.preventDefault());
    this.canvas.addEventListener("webglcontextrestored", () => { this.dirty = true; this.wake(); });

    // A tap, not a scroll: the stage never swallows touch gestures.
    let down = null;
    this.canvas.addEventListener("pointerdown", (e) => { down = { x: e.clientX, y: e.clientY, t: performance.now() }; });
    this.canvas.addEventListener("pointerup", (e) => {
      if (!down) return;
      const moved = Math.hypot(e.clientX - down.x, e.clientY - down.y);
      if (moved < 10 && performance.now() - down.t < 500) {
        const bot = this.pick(e.clientX, e.clientY);
        if (bot) { this.onTap(bot); this.dirty = true; this.wake(); }
      }
      down = null;
    });
    this.canvas.addEventListener("pointerleave", () => this.setHover(null));
  }

  // Stops everything the stage started: the frame loop, observers, document
  // and window listeners, and the WebGL context. Safe to call once.
  dispose() {
    if (this.disposed) return;
    this.disposed = true;
    cancelAnimationFrame(this.raf);
    this.raf = 0;
    this.ro.disconnect();
    this.io.disconnect();
    document.removeEventListener("visibilitychange", this.onVisibility);
    window.removeEventListener("pointermove", this.onMove);
    window.removeEventListener("pointerdown", this.onMove);
    for (const bot of this.bots) bot.dispose();
    this.renderer.dispose();
    this.renderer.forceContextLoss();
    this.canvas.remove();
    this.el.classList.remove("is-live");
  }

  add(bot) {
    this.bots.push(bot);
    this.scene.add(bot.root);
    return bot;
  }

  onTap() {}
  tick() {}
  fit() {}

  resize() {
    const w = this.el.clientWidth, h = this.el.clientHeight;
    if (!w || !h) return;
    this.width = w;
    this.height = h;
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    this.renderer.setSize(w, h, false);
    this.camera.aspect = w / h;
    this.fit(w / h);
    this.camera.updateProjectionMatrix();
    this.dirty = true;
    // Draw now, so a resize never shows a stretched frame.
    if (this.ready) this.renderer.render(this.scene, this.camera);
    this.wake();
  }

  pick(clientX, clientY) {
    const r = this.canvas.getBoundingClientRect();
    this.ndc.set(((clientX - r.left) / r.width) * 2 - 1, -((clientY - r.top) / r.height) * 2 + 1);
    this.ray.setFromCamera(this.ndc, this.camera);
    const hit = this.ray.intersectObjects(this.bots.map((b) => b.body), false)[0];
    return hit ? hit.object.userData.bot : null;
  }

  setHover(bot) {
    if (bot === this.hoverBot) return;
    if (this.hoverBot) this.hoverBot.hovered = false;
    this.hoverBot = bot;
    if (bot) bot.hovered = true;
    this.canvas.style.cursor = bot ? "pointer" : "";
    this.dirty = true;
    this.wake();
  }

  // Where a point in the scene lands on the stage, in CSS pixels.
  project(x, y, z) {
    this.tmp.set(x, y, z).project(this.camera);
    return [(this.tmp.x * 0.5 + 0.5) * this.width, (-this.tmp.y * 0.5 + 0.5) * this.height];
  }

  wake() {
    if (this.raf || !this.visible || document.hidden || !this.width) return;
    this.last = 0;
    this.raf = requestAnimationFrame(this.frame);
  }

  frame(now) {
    this.raf = 0;
    if (!this.visible || document.hidden) return;
    const dt = this.last ? Math.min((now - this.last) / 1000, 0.05) : 1 / 60;
    this.last = now;
    this.time += dt;

    const rect = this.canvas.getBoundingClientRect();
    const fresh = now - this.pointer.at < 3500;
    const mouse = this.pointer.type === "mouse";
    if (mouse && fresh && this.pointer.y > rect.top && this.pointer.y < rect.bottom && this.pointer.x > rect.left && this.pointer.x < rect.right) {
      this.setHover(this.pick(this.pointer.x, this.pointer.y));
    } else if (this.hoverBot && !mouse) this.setHover(null);

    this.tick(dt, this.time, rect, fresh);
    // Two half steps keep the springs steady when a frame runs long.
    for (const bot of this.bots) {
      bot.update(dt / 2, this.time - dt / 2, this.still);
      bot.update(dt / 2, this.time, this.still);
    }
    this.renderer.render(this.scene, this.camera);
    if (!this.ready) {
      this.ready = true;
      this.el.classList.add("is-live");
    }

    // With motion reduced, draw only when something changed.
    if (this.still) {
      const settling = this.bots.some((b) => b.air || Math.abs(b.squash.v) > 0.02 || Math.abs(b.squash.x) > 0.002);
      if (!this.dirty && !settling && this.time - this.dirtyAt > 0.6) return;
      if (this.dirty) { this.dirty = false; this.dirtyAt = this.time; }
    }
    this.raf = requestAnimationFrame(this.frame);
  }

  // Each bot looks at the pointer from where it stands.
  follow(rect, strength = 1) {
    const px = ((this.pointer.x - rect.left) / rect.width) * 2 - 1;
    const py = -(((this.pointer.y - rect.top) / rect.height) * 2 - 1);
    for (const bot of this.bots) {
      this.tmp.set(bot.root.position.x, bot.outline.face[1] * bot.size, bot.root.position.z).project(this.camera);
      bot.gazeTo.x = clamp((px - this.tmp.x) * 1.5 * strength);
      bot.gazeTo.y = clamp((py - this.tmp.y) * 1.1 * strength);
    }
  }
}

// ---------- the hero: the whole crew ----------

const crew = [
  { name: "Bolt", shape: "circle", hue: "cobalt", eyes: "dot" },
  { name: "Pixel", shape: "square", hue: "mint", eyes: "pill" },
  { name: "Tau", shape: "triangle", hue: "sky", eyes: "dot" },
  { name: "Juno", shape: "hexagon", hue: "coral", eyes: "wide" },
  { name: "Mochi", shape: "cloud", hue: "bubblegum", eyes: "dot" },
  { name: "Nori", shape: "arch", hue: "sun", eyes: "pill" },
  { name: "Otto", shape: "pill", hue: "grape", eyes: "wide" },
  { name: "Dot", shape: "diamond", hue: "cream", eyes: "dot" },
];

// What the crew is busy with. One bot at a time works, then asks.
const jobs = [
  { bot: "Pixel", work: "Reading three flight results", ask: "Open the booking page?", done: "Booking page is open", no: "Okay, just the list then" },
  { bot: "Juno", work: "Reading the diff", ask: "Push the fix to main?", done: "Pushed", no: "Leaving it on the branch" },
  { bot: "Nori", work: "Writing the morning brief", ask: "Post it to Slack?", done: "Posted to #team", no: "Kept as a draft" },
  { bot: "Dot", work: "Filling in the form", ask: "Submit it?", done: "Submitted", no: "Saved, not sent" },
];

class Hero extends Stage {
  constructor(el, opts) {
    super(el, opts);
    this.chip = el.querySelector("[data-chip]");
    this.chipText = this.chip?.querySelector("[data-chip-text]");
    this.chipName = this.chip?.querySelector("[data-chip-name]");
    this.chipActions = this.chip?.querySelector("[data-chip-actions]");
    this.chipFace = this.chip?.querySelector("[data-chip-state]");
    crew.forEach((c, i) => {
      const bot = this.add(new Bot(c));
      bot.index = i;
      bot.wander = rand(0.5, 3);
    });
    this.job = -1;
    this.story = { phase: "rest", until: this.still ? Infinity : 2.6 };
    this.chip?.addEventListener("click", (e) => {
      const act = e.target.closest("[data-act]")?.dataset.act;
      if (act && this.story.phase === "ask") this.answer(act === "approve");
    });
    this.resize();
    if (!this.still) this.bots.forEach((b, i) => b.drop(3.4 + (i % 3) * 0.3, 0.15 + i * 0.085));
  }

  fit(aspect) {
    const wide = aspect >= 2.15;
    const n = this.bots.length;
    let extent;
    if (wide) {
      const gap = 1.2;
      this.bots.forEach((b, i) => {
        b.size = 1;
        b.root.position.set((i - (n - 1) / 2) * gap, 0, i % 2 ? -0.3 : 0.12);
      });
      extent = (n - 1) * gap + 1.5;
    } else {
      // Narrow stages get a huddle, seen from a little higher so the
      // back row clears the front. The bots with jobs stand behind,
      // where a word above their heads covers nobody.
      const gap = 0.98;
      this.bots.forEach((b, i) => {
        const backRow = i % 2 === 1, col = Math.floor(i / 2);
        b.size = backRow ? 1.04 : 0.94;
        b.root.position.set((col - 1.5) * gap + (backRow ? -0.25 : 0.25), 0, backRow ? -1.15 : 0.15);
      });
      extent = 3 * gap + 0.5 + 1.05;
    }
    for (const b of this.bots) b.root.scale.setScalar(b.size);
    const tan = Math.tan((this.camera.fov * DEG) / 2);
    const tall = 2.05; // bots, the hop above them and room for a word
    const d = Math.max(extent / 2 / (tan * aspect), tall / 2 / tan);
    const view = 2 * d * tan;
    const lookY = view / 2 - Math.max(0.3, view * 0.1);
    const lift = (wide ? 9 : 21) * DEG;
    this.camera.position.set(0, lookY + Math.sin(lift) * d, Math.cos(lift) * d);
    this.camera.lookAt(0, lookY, 0);
  }

  onTap(bot) {
    if (this.story.bot === bot && this.story.phase === "ask") return this.answer(true);
    bot.hop();
    if (bot.mood === "idle") {
      bot.mood = "done";
      bot.cheerUntil = this.time + 1.3;
    }
    this.dirty = true;
  }

  say(bot, text, state, actions) {
    if (!this.chip) return;
    this.chipName.textContent = bot.name;
    this.chipText.textContent = text;
    this.chipFace.dataset.chipState = state;
    this.chipActions.hidden = !actions;
    this.chip.style.setProperty("--tint", hues[bot.hue]);
    this.chip.hidden = false;
    this.chip.inert = false;
    requestAnimationFrame(() => this.chip.classList.add("is-in"));
  }

  hush() {
    if (!this.chip) return;
    this.chip.classList.remove("is-in");
    this.chip.inert = true;
  }

  answer(yes) {
    const { bot, job } = this.story;
    if (yes) {
      bot.setMood("done");
      this.say(bot, job.done, "done", false);
    } else {
      bot.setMood("blocked");
      this.say(bot, job.no, "idle", false);
    }
    this.story = { phase: "after", bot, job, until: this.time + (yes ? 2.4 : 2.2) };
    this.dirty = true;
  }

  tick(dt, t, rect, fresh) {
    // Eyes on the pointer while it moves; otherwise they drift, and now
    // and then glance at whoever is working.
    if (fresh && !this.still) this.follow(rect);
    else if (!this.still) {
      for (const bot of this.bots) {
        bot.wander -= dt;
        if (bot.wander > 0) continue;
        bot.wander = rand(1.6, 4.2);
        const other = this.story.bot && this.story.bot !== bot && Math.random() < 0.5 ? this.story.bot : null;
        if (other) {
          bot.gazeTo.x = clamp((other.root.position.x - bot.root.position.x) * 0.5);
          bot.gazeTo.y = 0;
        } else if (Math.random() < 0.4) bot.gazeTo.x = bot.gazeTo.y = 0;
        else { bot.gazeTo.x = rand(-0.7, 0.7); bot.gazeTo.y = rand(-0.3, 0.4); }
      }
    }
    for (const bot of this.bots) {
      if (bot.cheerUntil && t > bot.cheerUntil) {
        bot.cheerUntil = 0;
        if (bot.mood === "done" && this.story.bot !== bot) bot.mood = "idle";
      }
    }

    const s = this.story;
    if (t > s.until) {
      if (s.phase === "rest") {
        this.job = (this.job + 1) % jobs.length;
        const job = jobs[this.job];
        const bot = this.bots.find((b) => b.name === job.bot);
        bot.cheerUntil = 0;
        bot.setMood("working");
        this.say(bot, job.work, "working", false);
        this.story = { phase: "work", bot, job, until: t + 3.4 };
      } else if (s.phase === "work") {
        s.bot.setMood("waiting");
        this.say(s.bot, s.job.ask, "waiting", true);
        this.story = { ...s, phase: "ask", until: t + 15 };
      } else if (s.phase === "ask" || s.phase === "after") {
        this.hush();
        s.bot.setMood("idle");
        this.story = { phase: "rest", until: t + (s.phase === "ask" ? 1.5 : 2.6) };
      }
    }

    // The word floats above whoever is speaking.
    const b = this.story.bot;
    if (b && this.chip && !this.chip.hidden) {
      const [x, y] = this.project(b.root.position.x, (b.outline.height + 0.16) * b.size + b.pivot.position.y * b.size, b.root.position.z);
      const half = this.chip.offsetWidth / 2 + 10;
      const cx = clamp(x, half, Math.max(half, this.width - half));
      this.chip.style.transform = `translate3d(${cx.toFixed(1)}px, ${Math.max(y, this.chip.offsetHeight + 4).toFixed(1)}px, 0) translate(-50%, -100%)`;
      this.chip.style.setProperty("--tail", `${(x - cx).toFixed(1)}px`);
    }
  }
}

// ---------- the studio: one bot, yours to shape ----------

class Studio extends Stage {
  constructor(el, opts) {
    super(el, opts);
    this.bot = this.add(new Bot({ shape: "arch", hue: "mint", eyes: "dot" }));
    this.resize();
    if (!this.still) this.bot.drop(1.2, 0.1);
  }

  fit(aspect) {
    const tan = Math.tan((this.camera.fov * DEG) / 2);
    const d = Math.max(1.62 / 2 / tan, 1.4 / 2 / (tan * aspect));
    const lift = 7 * DEG;
    this.camera.position.set(0, 0.5 + Math.sin(lift) * d, Math.cos(lift) * d);
    this.camera.lookAt(0, 0.5, 0);
  }

  set(look) {
    const reshaped = (look.shape && look.shape !== this.bot.shape) || (look.eyes && look.eyes !== this.bot.eyes);
    this.bot.setLook(look);
    if (reshaped && !this.still) this.bot.pop();
    this.dirty = true;
    this.wake();
  }

  setMood(mood) {
    this.bot.mood = "idle";
    this.bot.setMood(mood);
    this.dirty = true;
    this.wake();
  }

  onTap(bot) {
    bot.hop();
    this.dirty = true;
  }

  tick(dt, t, rect, fresh) {
    if (this.still) return;
    if (fresh) this.follow(rect, 0.8);
    else {
      this.drift = (this.drift ?? 1) - dt;
      if (this.drift < 0) {
        this.drift = rand(1.4, 3.2);
        const rest = Math.random() < 0.35;
        this.bot.gazeTo.x = rest ? 0 : rand(-0.8, 0.8);
        this.bot.gazeTo.y = rest ? 0 : rand(-0.3, 0.4);
      }
    }
  }
}

// Mounting twice on one element would stack two canvases and loops; the
// second call gets the stage that is already there.
export function mountHero(el, opts) { return el.__gbotStage ??= new Hero(el, opts); }
export function mountStudio(el, opts) { return el.__gbotStage ??= new Studio(el, opts); }

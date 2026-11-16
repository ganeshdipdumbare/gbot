// The character in three dimensions: the flat body from parts.ts given a
// little thickness, lit from above, painted into ordinary 2D canvases.
//
// One WebGL context serves every avatar on the page. Each paint renders a
// single bot into a square corner of the shared drawing buffer and copies
// it out with drawImage, so a hundred avatars never mean a hundred
// contexts.
import {
  BufferAttribute, BufferGeometry, CanvasTexture, CircleGeometry, DirectionalLight,
  Group, HemisphereLight, Mesh, MeshBasicMaterial, MeshStandardMaterial, PerspectiveCamera,
  PlaneGeometry, RingGeometry, Scene, Shape, ShapeGeometry, ShapeUtils, Vector2, WebGLRenderer,
} from "three";
import { hues } from "@gbot/ui-tokens";
import { eyeSizes } from "./parts";
import { pose, type Mood } from "./pose";
import type { EyeStyle, Shape as ShapeName, Traits } from "./traits";

const U = 0.01;      // one unit of the 100-box, in world units
const HALF = 0.19;   // half a body's thickness
const BEVEL = 0.1;   // how round its edge is
const GRAVITY = 17;
const TAU = Math.PI * 2;
const DEG = Math.PI / 180;
const EYE = "#16131C";
const ATLAS = 512;   // the shared buffer, in device pixels
const LIFT = 14 * DEG;

const clamp = (v: number, a = -1, b = 1) => Math.max(a, Math.min(b, v));
const ease = (rate: number, dt: number) => 1 - Math.exp(-rate * dt);
const rand = (a: number, b: number) => a + Math.random() * (b - a);

type Pt = [number, number];

// ---------- outlines (mirrors the paths in parts.ts) ----------

function arc(cx: number, cy: number, r: number, from: number, to: number, steps: number): Pt[] {
  return Array.from({ length: steps + 1 }, (_, i) => {
    const a = from + ((to - from) * i) / steps;
    return [cx + r * Math.cos(a), cy + r * Math.sin(a)];
  });
}

function quad(a: Pt, c: Pt, b: Pt, steps: number): Pt[] {
  return Array.from({ length: steps + 1 }, (_, i) => {
    const t = i / steps, u = 1 - t;
    return [u * u * a[0] + 2 * u * t * c[0] + t * t * b[0], u * u * a[1] + 2 * u * t * c[1] + t * t * b[1]];
  });
}

function cubic(a: Pt, c1: Pt, c2: Pt, b: Pt, steps: number): Pt[] {
  return Array.from({ length: steps + 1 }, (_, i) => {
    const t = i / steps, u = 1 - t;
    return [
      u * u * u * a[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t * t * t * b[0],
      u * u * u * a[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t * t * t * b[1],
    ];
  });
}

function straight(a: Pt, b: Pt, steps = 6): Pt[] {
  return Array.from({ length: steps + 1 }, (_, i) => [a[0] + ((b[0] - a[0]) * i) / steps, a[1] + ((b[1] - a[1]) * i) / steps]);
}

function rounded(points: Pt[], r: number): Pt[] {
  const n = points.length;
  const toward = (a: Pt, b: Pt): Pt => {
    const dx = a[0] - b[0], dy = a[1] - b[1];
    const t = Math.min(r / Math.hypot(dx, dy), 0.5);
    return [b[0] + dx * t, b[1] + dy * t];
  };
  const out: Pt[] = [];
  let prev: Pt | null = null, first: Pt | null = null;
  for (let i = 0; i < n; i++) {
    const p0 = points[(i - 1 + n) % n], p1 = points[i], p2 = points[(i + 1) % n];
    const a = toward(p0, p1), b = toward(p2, p1);
    if (prev) out.push(...straight(prev, a));
    else first = a;
    out.push(...quad(a, p1, b, 12));
    prev = b;
  }
  return out.concat(straight(prev!, first!));
}

function regular(sides: number, radius: number, cx: number, cy: number, rot: number): Pt[] {
  return Array.from({ length: sides }, (_, i) => {
    const a = rot + (i * TAU) / sides;
    return [cx + radius * Math.cos(a), cy + radius * Math.sin(a)];
  });
}

function flower(petals: number, cx: number, cy: number, ring: number): Pt[] {
  const pts = regular(petals, ring, cx, cy, -Math.PI / 2);
  const out: Pt[] = [];
  for (let i = 0; i < petals; i++) {
    const a = pts[i], b = pts[(i + 1) % petals];
    const mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2;
    const start = Math.atan2(a[1] - my, a[0] - mx);
    out.push(...arc(mx, my, Math.hypot(a[0] - b[0], a[1] - b[1]) / 2, start, start + Math.PI, 16));
  }
  return out;
}

// Outlines in the 100×100 box, plus where each face sits.
const raw: Record<ShapeName, { pts: Pt[]; face: Pt; eyeScale: number }> = {
  circle: { pts: arc(50, 50, 41, 0, TAU, 72), face: [50, 48], eyeScale: 1 },
  square: {
    pts: [
      ...cubic([50, 10], [80, 10], [90, 20], [90, 50], 20),
      ...cubic([90, 50], [90, 80], [80, 90], [50, 90], 20),
      ...cubic([50, 90], [20, 90], [10, 80], [10, 50], 20),
      ...cubic([10, 50], [10, 20], [20, 10], [50, 10], 20),
    ],
    face: [50, 48],
    eyeScale: 1,
  },
  triangle: { pts: rounded([[50, 10], [93, 86], [7, 86]], 15), face: [50, 62], eyeScale: 0.9 },
  hexagon: { pts: rounded(regular(6, 44, 50, 50, -Math.PI / 2), 11), face: [50, 49], eyeScale: 1 },
  cloud: { pts: flower(7, 50, 51, 30), face: [50, 50], eyeScale: 0.95 },
  pill: {
    pts: [
      ...straight([30, 22], [70, 22]),
      ...arc(70, 50, 28, -Math.PI / 2, Math.PI / 2, 28),
      ...straight([70, 78], [30, 78]),
      ...arc(30, 50, 28, Math.PI / 2, (3 * Math.PI) / 2, 28),
    ],
    face: [50, 49],
    eyeScale: 0.95,
  },
  arch: {
    pts: [
      ...arc(50, 52, 38, Math.PI, TAU, 40),
      ...straight([88, 52], [88, 80]),
      ...quad([88, 80], [88, 90], [78, 90], 10),
      ...straight([78, 90], [22, 90]),
      ...quad([22, 90], [12, 90], [12, 80], 10),
      ...straight([12, 80], [12, 52]),
    ],
    face: [50, 54],
    eyeScale: 1,
  },
  diamond: { pts: rounded([[50, 7], [93, 50], [50, 93], [7, 50]], 13), face: [50, 49], eyeScale: 0.85 },
};

function dedupe(pts: Pt[]): Pt[] {
  const out: Pt[] = [];
  for (const p of pts) {
    const q = out[out.length - 1];
    if (!q || Math.hypot(p[0] - q[0], p[1] - q[1]) > 0.05) out.push(p);
  }
  const a = out[0], b = out[out.length - 1];
  if (Math.hypot(a[0] - b[0], a[1] - b[1]) <= 0.05) out.pop();
  return out;
}

// The outline standing upright: y up, feet on the floor, centered on x.
function outline(shape: string) {
  const b = raw[shape as ShapeName]!;
  const pts = dedupe(b.pts);
  let floor = -Infinity;
  for (const p of pts) floor = Math.max(floor, p[1]);
  const flat = pts.map(([x, y]) => [(x - 50) * U, (floor - y) * U] as Pt);
  let area = 0;
  for (let i = 0; i < flat.length; i++) {
    const a = flat[i], c = flat[(i + 1) % flat.length];
    area += a[0] * c[1] - c[0] * a[1];
  }
  if (area < 0) flat.reverse();
  let height = 0, width = 0;
  for (const p of flat) { height = Math.max(height, p[1]); width = Math.max(width, Math.abs(p[0])); }
  return {
    pts: flat,
    height,
    width: width * 2,
    face: [(b.face[0] - 50) * U, (floor - b.face[1]) * U] as Pt,
    eyeScale: b.eyeScale,
  };
}

// A flat-faced token with a fully rounded rim. Normals are analytic, so
// the rim catches light as one smooth curve and meets the face cleanly.
function pillow(pts: Pt[], height: number): BufferGeometry {
  const n = pts.length, steps = 7;
  const dir = pts.map((p, i): [number, number, number] => {
    const a = pts[(i - 1 + n) % n], c = pts[(i + 1) % n];
    let e1x = p[0] - a[0], e1y = p[1] - a[1], e2x = c[0] - p[0], e2y = c[1] - p[1];
    const l1 = Math.hypot(e1x, e1y) || 1, l2 = Math.hypot(e2x, e2y) || 1;
    e1x /= l1; e1y /= l1; e2x /= l2; e2y /= l2;
    let mx = e1y + e2y, my = -e1x - e2x;
    const lm = Math.hypot(mx, my) || 1;
    mx /= lm; my /= lm;
    const k = 1 / Math.max(0.72, mx * e1y + my * -e1x);
    return [mx, my, k] as [number, number, number];
  });

  const profile: [number, number, number, number][] = [];
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
      position[o] = pts[i][0] + mx * k * r;
      position[o + 1] = pts[i][1] + my * k * r;
      position[o + 2] = z;
      normal[o] = mx * nr;
      normal[o + 1] = my * nr;
      normal[o + 2] = nz;
      // Light from above, easing into shade below, as on the flat rig.
      const t = clamp(position[o + 1] / height, 0, 1);
      const v = 0.8 + 0.2 * t * (2 - t);
      color[o] = color[o + 1] = color[o + 2] = v;
    }
  }

  const index: number[] = [];
  for (let j = 0; j < rings - 1; j++) {
    for (let i = 0; i < n; i++) {
      const a = j * n + i, b = j * n + ((i + 1) % n), c = (j + 1) * n + i, d = (j + 1) * n + ((i + 1) % n);
      index.push(a, c, d, a, d, b);
    }
  }
  const front = pts.map((_, i) => new Vector2(position[i * 3], position[i * 3 + 1]));
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
  g.computeBoundingBox();
  return g;
}

function eyeGeometry(style: keyof typeof eyeSizes): ShapeGeometry {
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
let smileParts: { ring: RingGeometry; cap: CircleGeometry } | null = null;
function smile(material: MeshBasicMaterial): Group {
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
  for (const m of g.children) m.position.y -= 5.4 * U;
  return g;
}

let shadowTexture: CanvasTexture | null = null;
function shadowMap(): CanvasTexture {
  if (shadowTexture) return shadowTexture;
  const c = document.createElement("canvas");
  c.width = c.height = 128;
  const ctx = c.getContext("2d")!;
  const g = ctx.createRadialGradient(64, 64, 0, 64, 64, 64);
  g.addColorStop(0, "rgba(0,0,0,1)");
  g.addColorStop(0.55, "rgba(0,0,0,.38)");
  g.addColorStop(1, "rgba(0,0,0,0)");
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 128, 128);
  shadowTexture = new CanvasTexture(c);
  return shadowTexture;
}

const cache = new Map<string, unknown>();
const cached = <T,>(key: string, make: () => T): T => {
  if (!cache.has(key)) cache.set(key, make());
  return cache.get(key) as T;
};

// ---------- the shared scene ----------

interface Rig {
  scene: Scene;
  camera: PerspectiveCamera;
  renderer: WebGLRenderer;
  canvas: HTMLCanvasElement;
  body: Mesh;
  material: MeshStandardMaterial;
  shadow: Mesh;
  pivot: Group;
  face: Group;
  lids: Group;
  open: Mesh[];
  shut: Group[];
  outline: ReturnType<typeof outline>;
  shape: string;
  hue: string;
  eyes: string;
  eyeMaterial: MeshBasicMaterial;
}

let rig: Rig | null = null;

/** Throws if this browser has no WebGL, so callers can fall back. */
function build(): Rig {
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = ATLAS;
  const renderer = new WebGLRenderer({ canvas, antialias: true, alpha: true, preserveDrawingBuffer: true });
  // A lost context takes the whole rig with it. Drop it, so the next paint
  // builds a fresh one instead of drawing into a dead renderer.
  canvas.addEventListener("webglcontextlost", (e) => {
    e.preventDefault();
    if (rig && rig.renderer === renderer) {
      // The old renderer's resources belong to the dead context; release them.
      try { renderer.dispose(); } catch { /* already gone with the context */ }
      rig = null;
    }
  });
  renderer.setPixelRatio(1);
  renderer.setSize(ATLAS, ATLAS, false);
  renderer.setClearColor(0x000000, 0);

  const scene = new Scene();
  scene.add(new HemisphereLight(0xffffff, 0xcfc8bd, 2.35));
  const key = new DirectionalLight(0xffffff, 1.55);
  key.position.set(-2.5, 6, 5);
  scene.add(key);
  const rim = new DirectionalLight(0xffffff, 0.5);
  rim.position.set(4, 2, -3);
  scene.add(rim);

  const material = new MeshStandardMaterial({ color: 0xffffff, vertexColors: true, roughness: 0.52, metalness: 0 });
  const body = new Mesh(new BufferGeometry(), material);
  const shadow = new Mesh(
    new PlaneGeometry(1, 1),
    new MeshBasicMaterial({ map: shadowMap(), color: 0x000000, transparent: true, opacity: 0.2, depthWrite: false }),
  );
  shadow.rotation.x = -Math.PI / 2;
  shadow.position.y = 0.002;
  shadow.renderOrder = -1;
  const pivot = new Group();
  const face = new Group();
  const lids = new Group();
  face.add(lids);
  pivot.add(body, face);
  scene.add(shadow, pivot);

  return {
    scene, camera: new PerspectiveCamera(13, 1, 0.1, 200), renderer, canvas, body, material, shadow, pivot, face, lids,
    open: [], shut: [], outline: outline("circle"), shape: "circle", hue: "mint", eyes: "",
    eyeMaterial: new MeshBasicMaterial({ color: EYE }),
  };
}

function setEyes(r: Rig, style: string) {
  r.eyes = style;
  r.lids.clear();
  const s = r.outline.eyeScale;
  const gap = 12 * s * U;
  r.open = [];
  r.shut = [];
  for (const side of [-1, 1]) {
    const open = new Mesh(cached(`e:${style}`, () => eyeGeometry(style as EyeStyle)), r.eyeMaterial);
    const shut = smile(r.eyeMaterial);
    open.userData.x = side * gap;
    shut.position.x = side * gap;
    shut.scale.setScalar(s);
    shut.visible = false;
    r.open.push(open);
    r.shut.push(shut);
    r.lids.add(open, shut);
  }
  r.face.position.set(r.outline.face[0], r.outline.face[1], HALF + 0.003);
}

function setShape(r: Rig, shape: string, eyes: string) {
  const fresh = r.shape !== shape;
  if (fresh) {
    r.shape = shape;
    r.outline = cached(`o:${shape}`, () => outline(shape));
    r.body.geometry = cached(`b:${shape}`, () => pillow(r.outline.pts, r.outline.height));
    r.shadow.userData.w = shape === "pill" ? 0.86 : shape === "triangle" || shape === "arch" ? 0.92 : 0.78;
  }
  // The eyes sit off the face line, so a new body moves them too.
  if (fresh || r.eyes !== eyes) setEyes(r, eyes);
}

// Fit the camera so the body fills the square, seen a little from above.
// The flat rig leaves 3 units of its 106-unit box as margin, so the body
// covers about 94% of the avatar here too.
function frame(r: Rig) {
  const { height, width } = r.outline;
  const tall = height * Math.cos(LIFT) + (2 * HALF + BEVEL) * Math.sin(LIFT);
  const extent = Math.max(tall, width + BEVEL) / 0.78;
  const dist = extent / (2 * Math.tan(6.5 * DEG));
  const cy = height * 0.53;
  r.camera.position.set(0, cy + Math.sin(LIFT) * dist, Math.cos(LIFT) * dist);
  r.camera.lookAt(0, cy, 0);
  r.camera.updateProjectionMatrix();
}

// ---------- one avatar's animation ----------

export interface Gaze { x: number; y: number }

export interface Anim {
  static?: boolean;
  mood: Mood;
  gaze: Gaze;
  gazeTo: Gaze;
  gazeRate: number;
  phase: number;
  t: number;
  lastDt?: number;
  driftAt: number;
  y: number;
  vy: number;
  air: boolean;
  squash: number;
  squashV: number;
  lean: number;
  lift: number;
  blink: number;
  nextBlink: number;
  happy: number;
  eye: { tx: number; ty: number; sx: number; sy: number; rot: number }[];
  hopAt: number;
  hopPower: number;
}

export function newAnim(): Anim {
  return {
    mood: "idle", gaze: { x: 0, y: 0 }, gazeTo: { x: 0, y: 0 }, gazeRate: rand(5, 9),
    phase: rand(0, 10), t: 0, driftAt: rand(0.4, 2.4), y: 0, vy: 0, air: false,
    squash: 0, squashV: 0, lean: 0, lift: 0, blink: 0, nextBlink: rand(1, 4), happy: 0,
    eye: [-1, 1].map(() => ({ tx: 0, ty: 0, sx: 1, sy: 1, rot: 0 })),
    hopAt: -1, hopPower: 3.2,
  };
}

/** Bring a mood change to life: a bot that just finished hops. */
export function setMood(a: Anim, mood: Mood) {
  if (mood === a.mood) return;
  a.mood = mood;
  if (mood === "done" && !a.air && a.hopAt < 0) {
    a.squashV += 2.4;
    a.hopAt = 0.1;
    a.hopPower = 3.2;
  }
}

/**
 * Advance one avatar and paint it into its canvas. `dt` is in seconds.
 * Paints at most `budget` device pixels across.
 */
export function paint(
  canvas: HTMLCanvasElement, look: Traits, a: Anim, dt: number, budget = ATLAS,
): void {
  rig = rig ?? build();
  const r = rig;
  const px = Math.max(8, Math.min(Math.round(budget), ATLAS));

  // A body change is a pop, not a cut.
  setShape(r, look.shape, look.eyes);
  // One rig serves every avatar, so its look is set outright each paint.
  if (r.hue !== look.hue) {
    r.hue = look.hue;
    r.material.color.set(hues[look.hue].body);
    r.eyeMaterial.color.set(hues[look.hue].eye);
  }

  update(a, dt, a.t, !!a.static);
  frame(r);

  const y0 = 0; // the shared buffer's bottom-left square
  r.renderer.setScissorTest(true);
  r.renderer.setScissor(0, y0, px, px);
  r.renderer.setViewport(0, y0, px, px);
  r.renderer.render(r.scene, r.camera);

  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  if (canvas.width !== px) { canvas.width = px; canvas.height = px; }
  ctx.clearRect(0, 0, px, px);
  ctx.drawImage(r.canvas, 0, ATLAS - px, px, px, 0, 0, px, px);
}

function update(a: Anim, dt: number, t: number, still: boolean) {
  const r = rig!;

  if (a.hopAt >= 0) {
    a.hopAt -= dt;
    if (a.hopAt < 0) { a.vy = a.hopPower; a.air = true; a.squashV -= 3; }
  }
  if (a.air) {
    a.vy -= GRAVITY * dt;
    a.y += a.vy * dt;
    if (a.y <= 0) { a.squashV += Math.min(-a.vy, 9) * 0.5; a.y = 0; a.vy = 0; a.air = false; }
  }
  // A steady spring, stepped in halves so a long frame cannot bounce it.
  for (let i = 0; i < 2; i++) {
    const h = dt / 2;
    a.squashV += (-210 * a.squash - 13 * a.squashV) * h;
    a.squash += a.squashV * h;
  }

  const g = ease(a.gazeRate, dt);
  a.gaze.x += (a.gazeTo.x - a.gaze.x) * g;
  a.gaze.y += (a.gazeTo.y - a.gaze.y) * g;

  const mood = a.mood;
  const p = t + a.phase;
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
  a.lean += (lean - a.lean) * ease(mood === "blocked" ? 40 : 7, dt);
  a.lift += (lift - a.lift) * ease(18, dt);

  const q = a.squash - a.lift * 0.014;
  const sy = 1 - q - breathe * 0.012;
  const sx = 1 + q * 0.62 + breathe * 0.009;
  r.pivot.scale.set(sx, sy, sx);
  r.pivot.position.y = a.y + a.lift * 0.035 * r.outline.height;
  r.pivot.rotation.set(-a.gaze.y * 0.2, a.gaze.x * 0.3, a.lean - a.gaze.x * 0.03);

  const h = clamp(a.y / 1.6, 0, 1);
  const w = (r.shadow.userData.w as number) * (1 - h * 0.35) * sx;
  r.shadow.scale.set(w, w * 0.8, 1);
  (r.shadow.material as MeshBasicMaterial).opacity = 0.2 * (1 - h * 0.75);

  const [fx, fy] = r.outline.face;
  r.face.position.x = fx + a.gaze.x * 3 * U + scan;
  r.face.position.y = fy + a.gaze.y * 2 * U;

  if (!still && mood !== "sleepy") {
    a.nextBlink -= dt;
    if (a.nextBlink < 0) {
      a.blink = 0.11;
      a.nextBlink = Math.random() < 0.2 ? 0.26 : rand(2.4, 6.5);
    }
  }
  a.blink = Math.max(0, a.blink - dt);
  r.lids.scale.y += ((a.blink > 0 ? 0.1 : 1) - r.lids.scale.y) * ease(60, dt);

  const happy = mood === "done";
  a.happy += ((happy ? 1 : 0) - a.happy) * ease(16, dt);
  const s = r.outline.eyeScale;
  const k = ease(still ? 1000 : 15, dt);
  ([-1, 1] as const).forEach((side, i) => {
    const to = pose(mood, side, a.gaze.x * 2.4, a.gaze.y * 1.6);
    const e = a.eye[i];
    e.tx += (to.tx - e.tx) * k;
    e.ty += (to.ty - e.ty) * k;
    e.sx += (to.sx - e.sx) * k;
    e.sy += (to.sy - e.sy) * k;
    e.rot += (to.rot - e.rot) * k;
    const open = r.open[i], shut = r.shut[i];
    open.position.set(open.userData.x + (e.tx + a.gaze.x * 2.4) * U, -(e.ty - a.gaze.y * 1.6) * U, 0);
    open.rotation.z = -e.rot * DEG;
    open.scale.set(e.sx * s, e.sy * s * (1 - a.happy), 1);
    open.visible = a.happy < 0.96;
    shut.visible = a.happy > 0.5;
  });
}

// ---------- the page's one animation loop ----------

export interface ViewOpts {
  look: Traits;
  mood: Mood;
  /** CSS pixels. */
  size: number;
  dpr: number;
  lookAt: Gaze | null;
  lively?: boolean;
  static?: boolean;
  live?: boolean;
}

interface View {
  canvas: HTMLCanvasElement;
  ctx: CanvasRenderingContext2D | null;
  opts: ViewOpts;
  anim: Anim;
  visible: boolean;
  painted: number;
  dirty: boolean;
  io: IntersectionObserver;
  step(now: number): void;
}

const views = new Set<View>();
let raf = 0;

function loop(now: number) {
  raf = 0;
  for (const v of views) v.step(now);
  if (views.size) raf = requestAnimationFrame(loop);
}

function wake() {
  if (!raf && views.size) raf = requestAnimationFrame(loop);
}

/** Paint one avatar: the shared loop draws only what is on screen. */
export function mount(canvas: HTMLCanvasElement, opts: ViewOpts) {
  const view: View = {
    canvas,
    ctx: canvas.getContext("2d"),
    opts,
    anim: newAnim(),
    visible: true,
    painted: 0,
    dirty: true,
    io: new IntersectionObserver(([e]) => { view.visible = e.isIntersecting; }, { rootMargin: "64px" }),
    step(now) {
      const { look, mood, size, dpr, lookAt, lively, static: still, live } = view.opts;
      if (!view.visible || document.hidden) return;
      const interval = still ? Infinity : size >= 64 ? 0 : size >= 32 ? 50 : 90;
      if (!view.dirty && now - view.painted < interval) return;

      const dt = view.anim.lastDt ? Math.min((now - view.painted) / 1000, 0.05) : 1 / 60;
      view.anim.lastDt = now;
      view.anim.t += dt;
      view.anim.static = still;

      // Where it looks: the pointer, or a slow wander of its own.
      if (lookAt) {
        view.anim.gazeTo.x = clamp(lookAt.x);
        view.anim.gazeTo.y = clamp(lookAt.y);
      } else {
        view.anim.driftAt -= dt;
        if (view.anim.driftAt < 0) {
          const spread = lively ? 2.5 : 1.6;
          view.anim.driftAt = lively ? rand(1.4, 3.1) : rand(2.8, 5.2);
          view.anim.gazeTo = Math.random() < (lively ? 0.35 : 0.45)
            ? { x: 0, y: 0 }
            : { x: (Math.random() - 0.5) * spread, y: (Math.random() - 0.5) * spread * 0.6 };
        }
      }
      setMood(view.anim, mood);

      const px = Math.round(size * dpr);
      if (view.canvas.width !== px) { view.canvas.width = px; view.canvas.height = px; view.ctx = view.canvas.getContext("2d"); }
      paint(view.canvas, look, view.anim, dt, px);

      if (live && view.ctx) {
        const r = px * 7.5 / 106, x = px * (84 + 3) / 106, y = px * (84 + 3) / 106;
        const pulse = still ? 1 : 1 - 0.18 * ((1 - Math.cos((view.anim.t * TAU) / 1.8)) / 2);
        // The ring is the page background, so the dot reads on any theme.
        const bg = getComputedStyle(view.canvas).getPropertyValue("--bg").trim() || "#fff";
        view.ctx.beginPath();
        view.ctx.arc(x, y, r * 1.9, 0, TAU);
        view.ctx.fillStyle = bg;
        view.ctx.fill();
        view.ctx.beginPath();
        view.ctx.arc(x, y, r * pulse, 0, TAU);
        view.ctx.fillStyle = "#8B5CF6";
        view.ctx.fill();
      }

      view.painted = now;
      view.dirty = false;
    },
  };
  view.io.observe(canvas);
  views.add(view);
  view.step(performance.now());
  wake();
  return {
    update(next: ViewOpts) {
      const prev = view.opts;
      view.opts = next;
      if (prev.look.shape !== next.look.shape || prev.look.hue !== next.look.hue || prev.look.eyes !== next.look.eyes) {
        // A look change is a pop, not a cut.
        view.anim.squashV -= 3.2;
      }
      if (prev.mood !== next.mood) view.dirty = true;
      view.dirty = true;
      wake();
    },
    dispose() {
      view.io.disconnect();
      views.delete(view);
    },
  };
}

/** True when this browser can draw the bots in three dimensions.
 *  Asked once: a probe canvas per avatar would eat the context budget. */
let canDraw: boolean | null = null;
export function supported(): boolean {
  if (canDraw !== null) return canDraw;
  try {
    const c = document.createElement("canvas");
    canDraw = !!(c.getContext("webgl2") || c.getContext("webgl"));
  } catch {
    canDraw = false;
  }
  return canDraw;
}

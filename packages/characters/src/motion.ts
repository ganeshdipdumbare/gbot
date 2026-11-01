import { bodies, eyeLayout, eyeSize, type Body } from "./parts";
import type { EyeStyle, Shape } from "./traits";
import type { Mood } from "./pose";

// The avatar motion system: every frame, a mood turns time into a pose.
// Modeled on Grok Bot's lifecycle. At rest a bot is calm and curious,
// its eyes glancing around the face. Working, it leans in and reads,
// eyes sweeping off one edge and back in from the other. Waiting, it
// tilts and sighs. Blocked, it becomes an exclamation mark. Thinking,
// it becomes three dots. Done, it spins and settles.
//
// All geometry is in the 100×100 head box. Eyes are in head space (they
// move with the head); dots and the bang are in world space.

export interface Eye { x: number; y: number; w: number; h: number; rot: number }
export interface Pose {
  head: { tx: number; ty: number; rot: number; s: number; sy: number; o: number };
  eyes: [Eye, Eye];
  /** The two extra "thinking" dots: mini heads either side. */
  dots: [{ x: number; y: number; s: number; o: number }, { x: number; y: number; s: number; o: number }];
  /** The bar of the "!" when blocked. */
  bang: { o: number; rot: number };
}

export interface Rig { body: Body; home: EyeStyle; seed: number }

export function rig(shape: Shape, eyes: EyeStyle, seed: string): Rig {
  let h = 2166136261;
  for (let i = 0; i < seed.length; i++) h = Math.imul(h ^ seed.charCodeAt(i), 16777619);
  return { body: bodies[shape], home: eyes, seed: h >>> 0 };
}

// --- helpers ---------------------------------------------------------------

const TAU = Math.PI * 2;
const clamp01 = (v: number) => Math.max(0, Math.min(1, v));
const lerp = (a: number, b: number, k: number) => a + (b - a) * k;
const easeOut = (k: number) => 1 - (1 - clamp01(k)) ** 3;
const easeIn = (k: number) => clamp01(k) ** 3;
const easeInOut = (k: number) => {
  k = clamp01(k);
  return k < 0.5 ? 4 * k * k * k : 1 - (-2 * k + 2) ** 3 / 2;
};
// 0 → 1 → 0 over [0, 1], smooth at both ends.
const bump = (k: number) => (k <= 0 || k >= 1 ? 0 : 0.5 - 0.5 * Math.cos(TAU * k));
// Deterministic noise per bot and step, so each bot has its own rhythm.
function rnd(seed: number, n: number) {
  let t = (seed + Math.imul(n + 1, 0x9e3779b9)) >>> 0;
  t = Math.imul(t ^ (t >>> 15), t | 1);
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}

function placement(body: Body, style: EyeStyle, scale = 1): [Eye, Eye] {
  const l = eyeLayout[style];
  const [fx, fy] = body.face[style];
  const w = eyeSize[0] * body.k * scale, h = eyeSize[1] * body.k * scale;
  return [0, 1].map((i) => {
    const side = i === 0 ? -1 : 1;
    return { x: fx + side * l.gap * body.k * scale, y: fy + side * l.lift * body.k, w, h, rot: l.rot[i] };
  }) as [Eye, Eye];
}

function lerpEye(a: Eye, b: Eye, k: number): Eye {
  return { x: lerp(a.x, b.x, k), y: lerp(a.y, b.y, k), w: lerp(a.w, b.w, k), h: lerp(a.h, b.h, k), rot: lerp(a.rot, b.rot, k) };
}
const moveEyes = (e: [Eye, Eye], dx: number, dy: number): [Eye, Eye] =>
  e.map((x) => ({ ...x, x: x.x + dx, y: x.y + dy })) as [Eye, Eye];
const squash = (e: [Eye, Eye], k: number): [Eye, Eye] => e.map((x) => ({ ...x, h: x.h * k })) as [Eye, Eye];

// A blink every few seconds, at a per-bot offset: 90ms shut, 190ms open.
function blink(r: Rig, t: number, every = 4.2) {
  const n = Math.floor(t / every);
  const at = n * every + 0.6 + rnd(r.seed, n + 77) * (every - 1.2);
  const u = t - at;
  if (u < 0 || u > 0.28) return 1;
  return u < 0.09 ? lerp(1, 0.18, u / 0.09) : lerp(0.18, 1, (u - 0.09) / 0.19);
}

const rest = (): Omit<Pose, "eyes"> => ({
  head: { tx: 0, ty: 0, rot: 0, s: 1, sy: 1, o: 1 },
  dots: [{ x: 50, y: 50, s: 0, o: 0 }, { x: 50, y: 50, s: 0, o: 0 }],
  bang: { o: 0, rot: 0 },
});

// --- moods -----------------------------------------------------------------

const others: Record<EyeStyle, EyeStyle[]> = { up: ["side", "center"], side: ["up", "center"], center: ["up", "side"] };

function idle(r: Rig, t: number, gaze?: { x: number; y: number } | null, sleepy = false): Pose {
  const p = rest();
  p.head.rot = -0.5 + 1.3 * Math.sin((TAU * t) / 9);
  p.head.ty = 0.4 * Math.sin((TAU * t) / 7);
  p.head.sy = 1 + 0.006 * Math.sin((TAU * t) / 4.5);
  if (sleepy) {
    p.head.rot = 2 + Math.sin((TAU * t) / 12);
    p.head.sy = 1 + 0.012 * Math.sin((TAU * t) / 6);
    return { ...p, eyes: moveEyes(squash(placement(r.body, r.home), 0.14), 0, 4) };
  }

  // Every few seconds the eyes glide to another spot on the face, the
  // slant swinging as they go, then come home again.
  const P = 6.5;
  const n = Math.floor(t / P);
  const target = (k: number): EyeStyle => {
    if (k <= 0) return r.home;
    const v = rnd(r.seed, k);
    return v < 0.5 ? r.home : others[r.home][v < 0.78 ? 0 : 1];
  };
  const k = easeInOut((t - n * P) / 0.9);
  const from = placement(r.body, target(n - 1)), to = placement(r.body, target(n));
  let eyes = [lerpEye(from[0], to[0], k), lerpEye(from[1], to[1], k)] as [Eye, Eye];
  const grow = 1 + 0.06 * bump(k);
  eyes = eyes.map((e) => ({ ...e, w: e.w * grow, h: e.h * grow })) as [Eye, Eye];

  const gx = gaze ? gaze.x * 6 : 1.1 * Math.sin((TAU * t) / 5.3);
  const gy = gaze ? gaze.y * 4 : 0.7 * Math.sin((TAU * t) / 3.7);
  eyes = squash(moveEyes(eyes, gx, gy), blink(r, t));
  return { ...p, eyes };
}

function working(r: Rig, t: number): Pose {
  const p = rest();
  p.head.rot = 4 + 0.55 * Math.sin((TAU * t) / 0.45);
  p.head.ty = 0.35 * Math.sin((TAU * t) / 0.45 + 1);
  const base = moveEyes(placement(r.body, "side", 1.12), 0, 6);

  // Read up and across, flick off the right edge, come back in from the
  // left, blink, repeat.
  const C = 7.5;
  const u = t % C;
  let dx = 0, dy = 0;
  if (u < 4) { const k = easeInOut(u / 4); dx = 20 * k; dy = -11 * k; }
  else if (u < 4.6) { dx = 20; dy = -11; }
  else if (u < 4.78) { dx = lerp(20, 95, easeIn((u - 4.6) / 0.18)); dy = -11; }
  else if (u < 5.05) { dx = 95; dy = -11; }
  else if (u < 5.6) { const k = easeOut((u - 5.05) / 0.55); dx = lerp(-75, 0, k); dy = lerp(-11, 0, k); }
  let eyes = moveEyes(base, dx, dy);

  // Acknowledge the task with a squeeze when work arrives, blink on each pass.
  const ack = t < 0.45 ? 1 - 0.8 * bump(t / 0.45) : 1;
  const pass = u > 5.9 && u < 6.2 ? 1 - 0.8 * bump((u - 5.9) / 0.3) : 1;
  eyes = squash(eyes, ack * pass);
  return { ...p, eyes };
}

function waiting(r: Rig, t: number): Pose {
  const p = rest();
  const settle = easeOut(t / 3);
  p.head.rot = -7 * settle + 0.4 * Math.sin((TAU * t) / 6);
  p.head.tx = -1.5 * settle;
  p.head.ty = 2.5 * settle;
  // A sigh now and then: stretch up, eyes dip.
  const S = 4.8;
  const sigh = bump(((t % S) - 1.2) / 0.8);
  p.head.sy = 1 + 0.035 * sigh;
  p.head.ty -= 1 * sigh;

  // Bored eyes: wide, heavy-lidded, a little to the right.
  const [fx, fy] = r.body.face.center;
  const k = r.body.k;
  const b = blink(r, t, 3.6);
  const eye = (side: -1 | 1): Eye => ({
    x: fx + 6 + side * 13 * k, y: fy + 6 + side * 2.5 * k + 1.2 * sigh,
    w: 21 * k, h: 10.5 * k * b, rot: -4,
  });
  return { ...p, eyes: [eye(-1), eye(1)] };
}

function blocked(r: Rig, t: number): Pose {
  // The head becomes the dot of a "!" and a bar appears above it; it
  // gives a little shake every couple of seconds.
  const p = rest();
  p.head.s = 0.115;
  p.head.ty = 25.5;
  p.bang.o = easeOut(t / 0.3);
  const u = t % 2.2;
  p.bang.rot = u > 1.6 ? 2.5 * Math.sin((TAU * (u - 1.6)) / 0.2) * (1 - (u - 1.6) / 0.6) : 0;
  return { ...p, eyes: squash(placement(r.body, r.home), 0) };
}

function thinking(r: Rig, t: number): Pose {
  // Three dots, each a tiny copy of the head, rippling in turn.
  const p = rest();
  const wave = (offset: number) => {
    const k = (((t - offset) / 1.45) % 1 + 1) % 1;
    return k < 0.42 ? bump(k / 0.42) : 0;
  };
  const right = wave(0), left = wave(0.46), mid = wave(0.92);
  p.head.s = 0.17 + 0.04 * mid;
  p.head.ty = -4 * mid;
  p.head.o = 0.5 + 0.5 * mid;
  p.dots = [
    { x: 23, y: 50 - 4 * left, s: 0.17 + 0.04 * left, o: 0.5 + 0.5 * left },
    { x: 77, y: 50 - 4 * right, s: 0.17 + 0.04 * right, o: 0.5 + 0.5 * right },
  ];
  return { ...p, eyes: squash(placement(r.body, r.home), 0) };
}

const SPIN_END = 3.6, CELEBRATE = 5.4;

function done(r: Rig, t: number): Pose {
  if (t >= CELEBRATE) return idle(r, t - CELEBRATE);
  const p = rest();
  // Pop, three full turns, a wobble, then home.
  p.head.s = 1 + 0.07 * bump(Math.min(t / 0.5, 1));
  const spin = easeInOut((t - 0.4) / (SPIN_END - 0.4));
  p.head.rot = 1080 * spin;
  const w = t - SPIN_END;
  if (w > 0) {
    const decay = Math.exp(-3 * w);
    p.head.rot += 5 * decay * Math.sin((TAU * w) / 0.42);
    p.head.tx = 1.8 * decay * Math.sin((TAU * w) / 0.36);
  }
  const home = placement(r.body, r.home);
  const big = placement(r.body, "center", 1.25);
  // Eyes grow and roll across the face as it turns.
  const roll = ((((p.head.rot / 360) * 140 + 70) % 140) + 140) % 140 - 70;
  const grow = easeOut(t / 0.4) * (1 - easeInOut((t - SPIN_END) / 1.2));
  let eyes = [lerpEye(home[0], big[0], grow), lerpEye(home[1], big[1], grow)] as [Eye, Eye];
  eyes = moveEyes(eyes, t < SPIN_END + 0.3 ? roll : 0, 0);
  const squint = w > 0.2 && w < 0.5 ? 1 - 0.8 * bump((w - 0.2) / 0.3) : 1;
  return { ...p, eyes: squash(eyes, squint) };
}

export function poseAt(r: Rig, mood: Mood, t: number, gaze?: { x: number; y: number } | null): Pose {
  switch (mood) {
    case "working": return working(r, t);
    case "waiting": return waiting(r, t);
    case "blocked": return blocked(r, t);
    case "thinking": return thinking(r, t);
    case "done":
    case "happy": return done(r, t);
    case "sleepy": return idle(r, t, null, true);
    default: return idle(r, t, gaze);
  }
}

// Moves between moods blend from wherever the bot was.
export function blend(a: Pose, b: Pose, k: number): Pose {
  if (k >= 1) return b;
  k = easeOut(k);
  const n = <T extends Record<string, number>>(x: T, y: T): T => {
    const o = {} as Record<string, number>;
    for (const key in x) o[key] = lerp(x[key], y[key], k);
    return o as T;
  };
  const rot = ((a.head.rot % 360) + 540) % 360 - 180;
  return {
    head: n({ ...a.head, rot }, b.head),
    eyes: [lerpEye(a.eyes[0], b.eyes[0], k), lerpEye(a.eyes[1], b.eyes[1], k)],
    dots: [n(a.dots[0], b.dots[0]), n(a.dots[1], b.dots[1])],
    bang: n(a.bang, b.bang),
  };
}

export const BLEND = 0.32;

export function headTransform(h: Pose["head"]) {
  return `translate(${50 + h.tx} ${50 + h.ty}) rotate(${h.rot}) scale(${h.s} ${h.s * h.sy}) translate(-50 -50)`;
}

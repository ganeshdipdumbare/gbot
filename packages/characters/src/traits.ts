import { hues, type Hue } from "@gbot/ui-tokens";

// A gbot's look is three discrete traits picked from a seed: every bot is
// recognizable, and all of them read as one family.

export const shapes = ["circle", "blob", "squircle", "pill", "triangle", "hexagon", "cloud", "drop"] as const;
// Where the two eyes sit on the head.
export const eyeStyles = ["up", "side", "center"] as const;
export const hueNames = Object.keys(hues) as Hue[];

export type Shape = (typeof shapes)[number];
export type EyeStyle = (typeof eyeStyles)[number];

export interface Traits {
  shape: Shape;
  hue: Hue;
  eyes: EyeStyle;
}

export type Look = Partial<Traits> & { seed?: string };

// cyrb53: small, well-distributed string hash.
export function hash(str: string): number {
  let h1 = 0xdeadbeef, h2 = 0x41c6ce57;
  for (let i = 0; i < str.length; i++) {
    const c = str.charCodeAt(i);
    h1 = Math.imul(h1 ^ c, 2654435761);
    h2 = Math.imul(h2 ^ c, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return 4294967296 * (2097151 & h2) + (h1 >>> 0);
}

export function rng(seed: string) {
  let a = hash(seed) >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const pick = <T,>(r: () => number, list: readonly T[]) => list[Math.floor(r() * list.length)];

// Looks saved before the Grok-style heads map onto the closest new trait.
const oldShapes: Record<string, Shape> = { square: "squircle", arch: "squircle", diamond: "drop" };
const oldHues: Record<string, Hue> = { cobalt: "blue", coral: "orange", mint: "teal", sun: "amber", grape: "purple", bubblegum: "pink", sky: "blue", cream: "brown", ink: "red" };

export function traitsFromSeed(seed: string): Traits {
  const r = rng(seed);
  return { shape: pick(r, shapes), hue: pick(r, hueNames), eyes: pick(r, eyeStyles) };
}

// Unknown values (including looks saved by older versions) fall back to
// the seed, so every stored bot still renders.
export function resolveLook(look: Look | undefined, fallbackSeed: string): Traits {
  const t = traitsFromSeed(look?.seed || fallbackSeed);
  const shape = look?.shape && (oldShapes[look.shape] ?? look.shape);
  const hue = look?.hue && (oldHues[look.hue] ?? look.hue);
  if (shape && shapes.includes(shape)) t.shape = shape;
  if (hue && hue in hues) t.hue = hue;
  if (look?.eyes && eyeStyles.includes(look.eyes)) t.eyes = look.eyes;
  return t;
}

export function randomSeed() {
  return Math.random().toString(36).slice(2, 12);
}

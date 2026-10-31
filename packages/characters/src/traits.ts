import { hues, type Hue } from "@gbot/ui-tokens";

// A gbot's look is a few discrete traits picked from a seed, so every
// bot is unique but all of them feel like one family.

export const shapes = ["mochi", "bun", "bean", "ghost", "blob", "drop"] as const;
export const eyeStyles = ["round", "tall", "sparkle"] as const;
export const accessories = ["none", "sprout", "antenna", "cat", "bear", "bow"] as const;
export const mouths = ["smile", "cat", "grin"] as const;
export const hueNames = Object.keys(hues) as Hue[];

export type Shape = (typeof shapes)[number];
export type EyeStyle = (typeof eyeStyles)[number];
export type Accessory = (typeof accessories)[number];
export type Mouth = (typeof mouths)[number];

export interface Traits {
  shape: Shape;
  hue: Hue;
  eyes: EyeStyle;
  accessory: Accessory;
  mouth: Mouth;
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

export function traitsFromSeed(seed: string): Traits {
  const r = rng(seed);
  const shape = pick(r, shapes);
  let accessory = pick(r, accessories);
  // A drop's pointy top has no room for ears.
  if (shape === "drop" && (accessory === "cat" || accessory === "bear" || accessory === "antenna")) accessory = "sprout";
  return { shape, hue: pick(r, hueNames), eyes: pick(r, eyeStyles), accessory, mouth: pick(r, mouths) };
}

export function resolveLook(look: Look | undefined, fallbackSeed: string): Traits {
  const base = traitsFromSeed(look?.seed || fallbackSeed);
  const t = { ...base };
  if (look?.shape && shapes.includes(look.shape)) t.shape = look.shape;
  if (look?.hue && look.hue in hues) t.hue = look.hue;
  if (look?.eyes && eyeStyles.includes(look.eyes)) t.eyes = look.eyes;
  if (look?.accessory && accessories.includes(look.accessory)) t.accessory = look.accessory;
  if (look?.mouth && mouths.includes(look.mouth)) t.mouth = look.mouth;
  return t;
}

export function randomSeed() {
  return Math.random().toString(36).slice(2, 12);
}

import type { EyeStyle, Shape } from "./traits";

// Flat heads in a 100×100 box, in the Grok Bot style: one solid shape,
// two slanted pill eyes cut out of it. Each head says where its face
// sits for every eye placement, so eyes stay on the shape.

type Pt = [number, number];
const f = (n: number) => n.toFixed(2);

// A polygon with every corner rounded by radius r.
function rounded(points: Pt[], r: number): string {
  const n = points.length;
  let d = "";
  for (let i = 0; i < n; i++) {
    const p0 = points[(i - 1 + n) % n], p1 = points[i], p2 = points[(i + 1) % n];
    const toward = (a: Pt, b: Pt): Pt => {
      const dx = a[0] - b[0], dy = a[1] - b[1], len = Math.hypot(dx, dy);
      const t = Math.min(r / len, 0.5);
      return [b[0] + dx * t, b[1] + dy * t];
    };
    const a = toward(p0, p1), b = toward(p2, p1);
    d += `${i === 0 ? "M" : "L"}${f(a[0])} ${f(a[1])}Q${f(p1[0])} ${f(p1[1])} ${f(b[0])} ${f(b[1])}`;
  }
  return d + "Z";
}

function regular(sides: number, radius: number, cx: number, cy: number, rot: number): Pt[] {
  return Array.from({ length: sides }, (_, i) => {
    const a = rot + (i * 2 * Math.PI) / sides;
    return [cx + radius * Math.cos(a), cy + radius * Math.sin(a)] as Pt;
  });
}

// A closed curve sampled from a polar or parametric function.
function traced(at: (t: number) => Pt, steps = 120): string {
  let d = "";
  for (let i = 0; i < steps; i++) {
    const [x, y] = at((i / steps) * 2 * Math.PI);
    d += `${i === 0 ? "M" : "L"}${f(x)} ${f(y)}`;
  }
  return d + "Z";
}

// Clockwise circle; several of these in one path fill as their union.
const circle = (cx: number, cy: number, r: number) =>
  `M${f(cx - r)} ${f(cy)}A${r} ${r} 0 1 1 ${f(cx + r)} ${f(cy)}A${r} ${r} 0 1 1 ${f(cx - r)} ${f(cy)}Z`;

const sgnPow = (v: number, p: number) => Math.sign(v) * Math.abs(v) ** p;

export interface Body {
  d: string;
  /** Eye-pair centers for each placement. */
  face: Record<EyeStyle, Pt>;
  /** Eye size multiplier for this head. */
  k: number;
}

export const bodies: Record<Shape, Body> = {
  circle: { d: circle(50, 50, 50), face: { up: [69, 27], side: [35, 50], center: [50, 42] }, k: 1 },
  blob: {
    d: traced((t) => {
      const r = 47 + 2.6 * Math.sin(2 * t + 1) + 1.4 * Math.sin(3 * t + 2);
      return [50 + r * Math.cos(t), 51 + r * Math.sin(t)];
    }),
    face: { up: [67, 30], side: [35, 52], center: [50, 44] },
    k: 1,
  },
  squircle: {
    d: traced((t) => [50 + 50 * sgnPow(Math.cos(t), 0.5), 50 + 50 * sgnPow(Math.sin(t), 0.5)]),
    face: { up: [70, 26], side: [33, 50], center: [50, 42] },
    k: 1,
  },
  pill: { d: "M32 18H68A32 32 0 0 1 68 82H32A32 32 0 0 1 32 18Z", face: { up: [66, 38], side: [33, 52], center: [50, 46] }, k: 0.92 },
  triangle: { d: rounded([[50, 2], [99, 92], [1, 92]], 22), face: { up: [58, 52], side: [44, 64], center: [50, 58] }, k: 0.86 },
  hexagon: { d: rounded(regular(6, 51, 50, 50, -Math.PI / 2), 14), face: { up: [65, 32], side: [35, 52], center: [50, 44] }, k: 1 },
  cloud: {
    d: circle(27, 60, 25) + circle(50, 39, 29) + circle(73, 57, 26) + circle(50, 68, 25),
    face: { up: [64, 38], side: [37, 57], center: [50, 50] },
    k: 0.95,
  },
  drop: {
    d: "M45.5 7.5Q50 0 54.5 7.5L78.4 33.3" + "A39 39 0 1 1 21.6 33.3Z",
    face: { up: [61, 54], side: [39, 62], center: [50, 58] },
    k: 0.9,
  },
};

// Eye pills: [width, height] before the head's k, and how each placement
// spaces and tilts them (degrees, clockwise).
export const eyeSize: [number, number] = [11.5, 24];
export const eyeLayout: Record<EyeStyle, { gap: number; lift: number; rot: [number, number] }> = {
  up: { gap: 10.5, lift: -2, rot: [-26, -26] },
  side: { gap: 11.5, lift: 3, rot: [27, -22] },
  center: { gap: 11.5, lift: -1.5, rot: [-18, -18] },
};

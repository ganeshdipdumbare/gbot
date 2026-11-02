import type { Shape } from "./traits";

// Flat geometric bodies in a 100×100 box. Each one says where its face
// sits, so eyes land in the visual center of the shape, not the box.

type Pt = [number, number];

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
    const f = (p: Pt) => `${p[0].toFixed(2)} ${p[1].toFixed(2)}`;
    d += `${i === 0 ? "M" : "L"}${f(a)}Q${f(p1)} ${f(b)}`;
  }
  return d + "Z";
}

function regular(sides: number, radius: number, cx: number, cy: number, rot: number): Pt[] {
  return Array.from({ length: sides }, (_, i) => {
    const a = rot + (i * 2 * Math.PI) / sides;
    return [cx + radius * Math.cos(a), cy + radius * Math.sin(a)] as Pt;
  });
}

function flower(petals: number, cx: number, cy: number, ring: number, petal: number): string {
  // A scalloped edge: arcs bulging outward between points on a ring.
  const pts = regular(petals, ring, cx, cy, -Math.PI / 2);
  let d = `M${pts[0][0].toFixed(2)} ${pts[0][1].toFixed(2)}`;
  for (let i = 1; i <= petals; i++) {
    const p = pts[i % petals];
    d += `A${petal} ${petal} 0 0 1 ${p[0].toFixed(2)} ${p[1].toFixed(2)}`;
  }
  return d + "Z";
}

export interface Body {
  d: string;
  face: [number, number]; // eye-line center
  eyeScale: number;
}

export const bodies: Record<Shape, Body> = {
  circle: { d: "M50 9a41 41 0 1 1 0 82a41 41 0 1 1 0-82Z", face: [50, 48], eyeScale: 1 },
  square: { d: "M50 10C80 10 90 20 90 50C90 80 80 90 50 90C20 90 10 80 10 50C10 20 20 10 50 10Z", face: [50, 48], eyeScale: 1 },
  triangle: { d: rounded([[50, 10], [93, 86], [7, 86]], 15), face: [50, 62], eyeScale: 0.9 },
  hexagon: { d: rounded(regular(6, 44, 50, 50, -Math.PI / 2), 11), face: [50, 49], eyeScale: 1 },
  cloud: { d: flower(7, 50, 51, 30, 11.5), face: [50, 50], eyeScale: 0.95 },
  pill: { d: "M30 22H70A28 28 0 0 1 70 78H30A28 28 0 0 1 30 22Z", face: [50, 49], eyeScale: 0.95 },
  arch: { d: "M12 52A38 38 0 0 1 88 52V80Q88 90 78 90H22Q12 90 12 80Z", face: [50, 54], eyeScale: 1 },
  diamond: { d: rounded([[50, 7], [93, 50], [50, 93], [7, 50]], 13), face: [50, 49], eyeScale: 0.85 },
};

// Eyes are flat, upright Grok Bot-style pills cut out of the head:
// [width, height] per style, before mood shaping.
export const eyeSizes = {
  pill: [10.5, 21],
  dot: [11, 14],
  wide: [14, 10.5],
} as const;

// Colour helper for the 3D shading: mix two hex colours.
export function mix(a: string, b: string, t: number) {
  const p = (h: string) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
  const [x, y] = [p(a), p(b)];
  return "#" + x.map((v, i) => Math.round(v + (y[i] - v) * t).toString(16).padStart(2, "0")).join("");
}

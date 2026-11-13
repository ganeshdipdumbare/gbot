// The gbot character sheet, shared by the 3D crew and its flat SVG
// stand-ins. Same bodies, hues, eyes and moods as packages/characters, so
// a bot here is the bot you get in the app.

export const hues = {
  cobalt: "#3B6CF6",
  coral: "#FF6A4D",
  sun: "#FFC233",
  mint: "#2EC78E",
  grape: "#8B5CF6",
  bubblegum: "#FF66B3",
  sky: "#3DBEF5",
  cream: "#F1E7D6",
};
export const EYE = "#16131C";

export const shapeNames = ["circle", "square", "triangle", "hexagon", "cloud", "pill", "arch", "diamond"];
export const eyeNames = ["dot", "pill", "wide"];
export const moodNames = ["idle", "thinking", "working", "waiting", "blocked", "sleepy", "done"];

// [width, height] in the 100-unit box.
export const eyeSizes = { pill: [10.5, 21], dot: [11, 14], wide: [14, 10.5] };

const TAU = Math.PI * 2;

function arc(cx, cy, r, from, to, steps) {
  const out = [];
  for (let i = 0; i <= steps; i++) {
    const a = from + ((to - from) * i) / steps;
    out.push([cx + r * Math.cos(a), cy + r * Math.sin(a)]);
  }
  return out;
}

function quad(a, c, b, steps) {
  const out = [];
  for (let i = 0; i <= steps; i++) {
    const t = i / steps, u = 1 - t;
    out.push([u * u * a[0] + 2 * u * t * c[0] + t * t * b[0], u * u * a[1] + 2 * u * t * c[1] + t * t * b[1]]);
  }
  return out;
}

function cubic(a, c1, c2, b, steps) {
  const out = [];
  for (let i = 0; i <= steps; i++) {
    const t = i / steps, u = 1 - t;
    out.push([
      u * u * u * a[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t * t * t * b[0],
      u * u * u * a[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t * t * t * b[1],
    ]);
  }
  return out;
}

// A straight run, subdivided so side walls stay evenly meshed.
function line(a, b, steps = 6) {
  const out = [];
  for (let i = 0; i <= steps; i++) out.push([a[0] + ((b[0] - a[0]) * i) / steps, a[1] + ((b[1] - a[1]) * i) / steps]);
  return out;
}

// A polygon with every corner rounded by radius r.
function rounded(points, r) {
  const n = points.length;
  const toward = (a, b) => {
    const dx = a[0] - b[0], dy = a[1] - b[1], len = Math.hypot(dx, dy);
    const t = Math.min(r / len, 0.5);
    return [b[0] + dx * t, b[1] + dy * t];
  };
  let out = [];
  let prevEnd = null;
  let first = null;
  for (let i = 0; i < n; i++) {
    const p0 = points[(i - 1 + n) % n], p1 = points[i], p2 = points[(i + 1) % n];
    const a = toward(p0, p1), b = toward(p2, p1);
    if (prevEnd) out = out.concat(line(prevEnd, a));
    else first = a;
    out = out.concat(quad(a, p1, b, 12));
    prevEnd = b;
  }
  return out.concat(line(prevEnd, first));
}

function regular(sides, radius, cx, cy, rot) {
  return Array.from({ length: sides }, (_, i) => {
    const a = rot + (i * TAU) / sides;
    return [cx + radius * Math.cos(a), cy + radius * Math.sin(a)];
  });
}

// A scalloped edge: half-circles bulging outward between points on a ring.
function flower(petals, cx, cy, ring) {
  const pts = regular(petals, ring, cx, cy, -Math.PI / 2);
  let out = [];
  for (let i = 0; i < petals; i++) {
    const a = pts[i], b = pts[(i + 1) % petals];
    const mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2;
    const r = Math.hypot(a[0] - b[0], a[1] - b[1]) / 2;
    const start = Math.atan2(a[1] - my, a[0] - mx);
    out = out.concat(arc(mx, my, r, start, start + Math.PI, 16));
  }
  return out;
}

// Outlines in the 100×100 box (y down, like the SVG rig), with the eye
// line's center and how much the eyes shrink to fit.
const raw = {
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
      ...line([30, 22], [70, 22]),
      ...arc(70, 50, 28, -Math.PI / 2, Math.PI / 2, 28),
      ...line([70, 78], [30, 78]),
      ...arc(30, 50, 28, Math.PI / 2, (3 * Math.PI) / 2, 28),
    ],
    face: [50, 49],
    eyeScale: 0.95,
  },
  arch: {
    pts: [
      ...arc(50, 52, 38, Math.PI, TAU, 40),
      ...line([88, 52], [88, 80]),
      ...quad([88, 80], [88, 90], [78, 90], 10),
      ...line([78, 90], [22, 90]),
      ...quad([22, 90], [12, 90], [12, 80], 10),
      ...line([12, 80], [12, 52]),
    ],
    face: [50, 54],
    eyeScale: 1,
  },
  diamond: { pts: rounded([[50, 7], [93, 50], [50, 93], [7, 50]], 13), face: [50, 49], eyeScale: 0.85 },
};

function dedupe(pts) {
  const out = [];
  for (const p of pts) {
    const q = out[out.length - 1];
    if (!q || Math.hypot(p[0] - q[0], p[1] - q[1]) > 0.05) out.push(p);
  }
  const a = out[0], b = out[out.length - 1];
  if (Math.hypot(a[0] - b[0], a[1] - b[1]) <= 0.05) out.pop();
  return out;
}

export const bodies = {};
for (const name of shapeNames) {
  const { pts, face, eyeScale } = raw[name];
  bodies[name] = { pts: dedupe(pts), face, eyeScale };
}

export function pathOf(shape) {
  return "M" + bodies[shape].pts.map((p) => `${p[0].toFixed(2)} ${p[1].toFixed(2)}`).join("L") + "Z";
}

// How each mood shapes an eye, in box units (y down). Everything a bot
// feels is carried by two eyes and the way its body moves.
export function pose(mood, side) {
  switch (mood) {
    case "thinking": return { tx: 3, ty: -4.5, sx: 1, sy: 0.82, rot: 0 };
    case "working": return { tx: 0, ty: 1.2, sx: 1.05, sy: 0.5, rot: 0 };
    case "waiting": return { tx: 0, ty: -0.6, sx: 1.18, sy: 1.18, rot: 0 };
    case "blocked": return { tx: 0, ty: 1.5, sx: 1.25, sy: 0.42, rot: side * 24 };
    case "sleepy": return { tx: 0, ty: 3, sx: 1.1, sy: 0.16, rot: 0 };
    default: return { tx: 0, ty: 0, sx: 1, sy: 1, rot: 0 };
  }
}

const mix = (a, b, t) => {
  const p = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
  const x = p(a), y = p(b);
  return "#" + x.map((v, i) => Math.round(v + (y[i] - v) * t).toString(16).padStart(2, "0")).join("");
};

let uid = 0;

// The flat bot, as markup: icons in the picker and the stand-in when
// WebGL is not available.
export function botSVG({ shape = "circle", hue = "mint", eyes = "dot", mood = "idle" }, { shadow = false } = {}) {
  const body = bodies[shape];
  const color = hues[hue];
  const id = `g${uid++}`;
  const [fx, fy] = body.face;
  const s = body.eyeScale;
  const [ew, eh] = eyeSizes[eyes];
  const w = ew * s, h = eh * s, gap = 12 * s;
  const happy = mood === "done";
  let face = "";
  for (const side of [-1, 1]) {
    const cx = fx + side * gap;
    if (happy) {
      face += `<path d="M${cx - 7 * s} ${fy + 3}Q${cx} ${fy - 7 * s} ${cx + 7 * s} ${fy + 3}" fill="none" stroke="${EYE}" stroke-width="${6 * s}" stroke-linecap="round"/>`;
    } else {
      const p = pose(mood, side);
      face += `<rect x="${cx - w / 2}" y="${fy - h / 2}" width="${w}" height="${h}" rx="${Math.min(w, h) / 2}" fill="${EYE}" transform="translate(${p.tx} ${p.ty}) rotate(${p.rot} ${cx} ${fy}) translate(${cx} ${fy}) scale(${p.sx} ${p.sy}) translate(${-cx} ${-fy})"/>`;
    }
  }
  return (
    `<svg viewBox="-3 -3 106 106" aria-hidden="true" focusable="false">` +
    `<defs><radialGradient id="${id}" cx="0.5" cy="0.12" r="0.95">` +
    `<stop offset="0" stop-color="${mix(color, "#ffffff", 0.2)}"/><stop offset="0.55" stop-color="${color}"/><stop offset="1" stop-color="${mix(color, "#000000", 0.1)}"/>` +
    `</radialGradient></defs>` +
    (shadow ? `<ellipse cx="50" cy="95.5" rx="27" ry="3.4" fill="#000" opacity=".12"/>` : "") +
    `<path d="${pathOf(shape)}" fill="url(#${id})"/>${face}</svg>`
  );
}

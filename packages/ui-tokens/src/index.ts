// Single source of truth for gbot's design tokens. tokens.css mirrors these
// for the web; the mobile app reads them from here.

export const live = "#8B5CF6";

export const light = {
  bg: "#FFF9F3",
  surface: "#FFFFFF",
  surface2: "#FFF1E6",
  ink: "#2B2135",
  ink2: "#6E6378",
  line: "#F0E3D8",
  bubbleMe: "#2B2135",
  bubbleMeInk: "#FFF9F3",
};

export const dark = {
  bg: "#17131F",
  surface: "#211B2B",
  surface2: "#2A2236",
  ink: "#F6EEF9",
  ink2: "#B3A7BF",
  line: "#342B42",
  bubbleMe: "#F6EEF9",
  bubbleMeInk: "#17131F",
};

// Character hues: body fill + a contrast-safe ink for faces and accents.
export const hues = {
  peach: { body: "#FFC4A8", shade: "#F7A285", ink: "#5B2A1A" },
  mint: { body: "#B8EBD3", shade: "#8FD9B8", ink: "#1E4A39" },
  lilac: { body: "#D9C8FF", shade: "#BBA2FA", ink: "#3A2770" },
  butter: { body: "#FFE7A3", shade: "#F9D46E", ink: "#5A4310" },
  sky: { body: "#BFE3FF", shade: "#93CBF7", ink: "#173C5E" },
  rose: { body: "#FFC6DA", shade: "#F9A1C1", ink: "#5E1F38" },
} as const;

export type Hue = keyof typeof hues;

export const motion = {
  fast: 0.15,
  base: 0.24,
  slow: 0.35,
  spring: { type: "spring", stiffness: 520, damping: 30, mass: 0.8 } as const,
  bouncy: { type: "spring", stiffness: 420, damping: 16, mass: 0.7 } as const,
};

export const radius = { sm: 10, md: 16, lg: 24, pill: 999 };

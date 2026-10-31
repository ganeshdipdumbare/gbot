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

// Character colors: one flat fill plus the eye color that reads on it.
export const hues = {
  cobalt: { body: "#3B6CF6", eye: "#16131C" },
  coral: { body: "#FF6A4D", eye: "#16131C" },
  sun: { body: "#FFC233", eye: "#16131C" },
  mint: { body: "#2EC78E", eye: "#16131C" },
  grape: { body: "#8B5CF6", eye: "#16131C" },
  bubblegum: { body: "#FF66B3", eye: "#16131C" },
  sky: { body: "#3DBEF5", eye: "#16131C" },
  cream: { body: "#F1E7D6", eye: "#16131C" },
  ink: { body: "#26222E", eye: "#FFFFFF" },
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

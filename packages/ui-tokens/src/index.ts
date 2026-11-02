// Single source of truth for gbot's design tokens. tokens.css mirrors these
// for the web; the mobile app reads them from here.

export const live = "#8B5CF6";

export const light = {
  chrome: "#f7f7f6",
  bg: "#fcfcfc",
  surface: "rgba(16,16,0,0.04)",
  surface2: "rgba(16,16,0,0.07)",
  ink: "#0a0a0a",
  ink2: "rgba(0,0,0,0.55)",
  ink3: "rgba(0,0,0,0.35)",
  line: "rgba(0,0,0,0.1)",
  bubbleMe: "#111110",
  bubbleMeInk: "#ffffff",
  bubbleBot: "rgba(16,16,0,0.04)",
};

export const dark = {
  chrome: "#141414",
  bg: "#1f1f1f",
  surface: "rgba(255,255,255,0.06)",
  surface2: "rgba(255,255,255,0.1)",
  ink: "#f2f2f2",
  ink2: "rgba(255,255,255,0.58)",
  ink3: "rgba(255,255,255,0.36)",
  line: "rgba(255,255,255,0.12)",
  bubbleMe: "#ecece8",
  bubbleMeInk: "#141414",
  bubbleBot: "rgba(255,255,255,0.06)",
};

// Character colors: one bold fill and an eye colour that reads on it.
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

// Snappy, like Grok Bot: 120ms hovers, a 280ms pop-in for new entries.
export const motion = {
  fast: 0.12,
  base: 0.2,
  enter: 0.28,
  ease: [0.2, 0.9, 0.3, 1.15] as const,
};

export const radius = { sm: 8, md: 10, lg: 16, pill: 999 };

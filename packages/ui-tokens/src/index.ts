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

// Character colors: one flat fill; faces are a dark visor with light eyes.
export const hues = {
  cobalt: { body: "#3B6CF6", eye: "#FFFFFF" },
  coral: { body: "#FF6A4D", eye: "#FFFFFF" },
  sun: { body: "#FFC233", eye: "#FFFFFF" },
  mint: { body: "#2EC78E", eye: "#FFFFFF" },
  grape: { body: "#8B5CF6", eye: "#FFFFFF" },
  bubblegum: { body: "#FF66B3", eye: "#FFFFFF" },
  sky: { body: "#3DBEF5", eye: "#FFFFFF" },
  cream: { body: "#F1E7D6", eye: "#FFFFFF" },
  ink: { body: "#26222E", eye: "#FFFFFF" },
} as const;

export type Hue = keyof typeof hues;

export const visor = "#15131A";

// Snappy, like Grok Bot: 120ms hovers, a 280ms pop-in for new entries.
export const motion = {
  fast: 0.12,
  base: 0.2,
  enter: 0.28,
  ease: [0.2, 0.9, 0.3, 1.15] as const,
};

export const radius = { sm: 8, md: 10, lg: 16, pill: 999 };

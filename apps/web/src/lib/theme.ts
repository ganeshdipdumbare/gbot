// Light or dark, chosen per device. The tokens in @gbot/ui-tokens already
// answer to [data-theme]; this just decides which one is set, and keeps the
// browser chrome (address bar, title bar) in step.

export type Theme = "system" | "light" | "dark";

const KEY = "gbot.theme";

export const themes: { id: Theme; label: string; blurb: string }[] = [
  { id: "system", label: "System", blurb: "Matches your computer's light or dark setting." },
  { id: "light", label: "Light", blurb: "Always light, whatever the computer does." },
  { id: "dark", label: "Dark", blurb: "Always dark, whatever the computer does." },
];

export function getTheme(): Theme {
  try {
    const v = localStorage.getItem(KEY);
    if (v === "light" || v === "dark" || v === "system") return v;
  } catch { /* storage blocked */ }
  return "system";
}

/** The theme actually on screen once "system" is resolved. */
export function resolved(t: Theme = getTheme()): "light" | "dark" {
  if (t !== "system") return t;
  return matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

export function applyTheme(t: Theme = getTheme()) {
  const root = document.documentElement;
  if (t === "system") delete root.dataset.theme;
  else root.dataset.theme = t;
  // Both meta tags carry a media query, so a fixed choice needs its own tag.
  const chrome = resolved(t) === "dark" ? "#141414" : "#f7f7f6";
  let tag = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]:not([media])');
  if (!tag) {
    tag = document.createElement("meta");
    tag.name = "theme-color";
    document.head.appendChild(tag);
  }
  tag.content = chrome;
}

export function setTheme(t: Theme) {
  try { localStorage.setItem(KEY, t); } catch { /* storage blocked */ }
  applyTheme(t);
  window.dispatchEvent(new CustomEvent("gbot:theme", { detail: t }));
}

/** Follows the OS while the choice is "system". */
export function watchSystemTheme() {
  const mq = matchMedia("(prefers-color-scheme: dark)");
  const on = () => { if (getTheme() === "system") applyTheme("system"); };
  mq.addEventListener("change", on);
  return () => mq.removeEventListener("change", on);
}

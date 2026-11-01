import { useState } from "react";
import { eyeStyles, Gbot, hueNames, shapes, type Mood } from "@gbot/characters";
import { useGaze } from "../lib/hooks";

const moods: Mood[] = ["idle", "thinking", "working", "waiting", "blocked", "done", "sleepy"];

// Character review sheet at /gallery: every shape, color, topper and mood
// at roster and profile sizes, in both themes.
export default function Gallery() {
  const [ref, gaze] = useGaze<HTMLDivElement>();
  const [theme, setTheme] = useState<"light" | "dark">(() => (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light"));
  return (
    <div className="min-h-full bg-bg text-ink p-6 space-y-8" ref={ref}>
      <div className="flex items-center gap-3">
        <h1 className="font-semibold tracking-[-0.3px] text-2xl">gbot characters</h1>
        <button className="rounded-full bg-surface-2 px-3 h-8 text-sm font-bold" onClick={() => {
          const t = theme === "light" ? "dark" : "light";
          setTheme(t);
          document.documentElement.dataset.theme = t;
        }}>{theme}</button>
      </div>
      <section className="flex flex-wrap gap-4 items-end">
        {shapes.map((shape, i) => (
          <Gbot key={shape} look={{ shape, hue: hueNames[i % hueNames.length], eyes: eyeStyles[i % eyeStyles.length] }} seed={shape} size={120} lookAt={gaze} />
        ))}
      </section>
      <section className="flex flex-wrap gap-5 items-end">
        {moods.map((mood) => (
          <div key={mood} className="text-center text-xs text-ink-2">
            <Gbot look={{ shape: "circle", hue: "cobalt", eyes: "pill" }} seed="m" size={96} mood={mood} live={mood === "working"} />
            {mood}
          </div>
        ))}
      </section>
      <section className="flex flex-wrap gap-2">
        {Array.from({ length: 48 }, (_, i) => <Gbot key={i} seed={"g" + i} size={44} />)}
      </section>
      <section className="flex flex-wrap gap-1.5">
        {Array.from({ length: 48 }, (_, i) => <Gbot key={i} seed={"g" + i} size={24} />)}
      </section>
    </div>
  );
}

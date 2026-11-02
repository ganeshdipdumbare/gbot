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
      <Lifecycle />
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

// One bot moving through its lifecycle, like the avatar motion demo on
// Grok Bot's design page. Picking a state shows the transition into it.
function Lifecycle() {
  const states: Mood[] = ["idle", "working", "waiting", "blocked", "thinking", "done"];
  const [mood, setMood] = useState<Mood>("idle");
  return (
    <section className="rounded-2xl bg-surface p-6 max-w-[560px]">
      <div className="grid place-items-center h-44">
        <Gbot look={{ shape: "circle", hue: "cobalt", eyes: "pill" }} seed="life" size={128} mood={mood} />
      </div>
      <div className="flex gap-1 mt-4" role="tablist">
        {states.map((m) => (
          <button key={m} role="tab" aria-selected={m === mood} onClick={() => setMood(m)}
            className={`flex-1 h-8 rounded-full text-[13px] capitalize cursor-pointer transition-colors duration-[120ms] ${m === mood ? "bg-emphasis text-on-emphasis" : "text-ink-2 hover:bg-hover"}`}>
            {m}
          </button>
        ))}
      </div>
    </section>
  );
}

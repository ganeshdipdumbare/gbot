import { motion } from "motion/react";
import { accessories, eyeStyles, Gbot, hueNames, mouths, randomSeed, resolveLook, shapes, type Look, type Traits } from "@gbot/characters";
import { hues } from "@gbot/ui-tokens";
import { bouncy, Icon } from "./ui";

const labels: Record<string, string> = {
  mochi: "Mochi", bun: "Bun", bean: "Bean", ghost: "Ghosty", blob: "Blob", drop: "Drop",
  round: "Round", tall: "Tall", sparkle: "Sparkly",
  none: "None", sprout: "Sprout", antenna: "Antenna", cat: "Kitty", bear: "Teddy", bow: "Bow",
  smile: "Smile", grin: "Grin",
};

export function LookPicker({ look, seed, onChange }: { look: Look; seed: string; onChange: (l: Look) => void }) {
  const t = resolveLook(look, seed);
  const setT = (patch: Partial<Traits>) => onChange({ ...t, seed: look.seed ?? seed, ...patch });

  const row = <K extends keyof Traits>(key: K, title: string, options: readonly Traits[K][]) => (
    <div>
      <div className="font-display font-extrabold text-[13px] text-ink-2 mb-1.5 px-1">{title}</div>
      <div className="flex gap-1.5 overflow-x-auto scroll-soft pb-1 -mx-1 px-1">
        {options.map((o) => {
          const on = t[key] === o;
          return (
            <motion.button
              key={String(o)}
              type="button"
              whileTap={{ scale: 0.9 }}
              whileHover={{ y: -2 }}
              transition={bouncy}
              onClick={() => setT({ [key]: o } as Partial<Traits>)}
              className={`shrink-0 flex flex-col items-center gap-0.5 rounded-2xl px-2 pt-1.5 pb-1 border-2 transition-colors cursor-pointer ${on ? "border-live bg-live-soft" : "border-transparent bg-surface hover:bg-surface-2"}`}
              aria-pressed={on}
              aria-label={labels[String(o)] ?? String(o)}
            >
              {key === "hue" ? (
                <span className="size-11 grid place-items-center">
                  <span className="size-8 rounded-full border-2" style={{ background: hues[o as keyof typeof hues].body, borderColor: hues[o as keyof typeof hues].shade }} />
                </span>
              ) : (
                <Gbot look={{ ...t, [key]: o }} seed={seed} size={44} props={false} mood={key === "mouth" && o === "grin" ? "idle" : "idle"} />
              )}
              <span className="text-[11px] font-semibold text-ink-2">{key === "hue" ? String(o)[0].toUpperCase() + String(o).slice(1) : labels[String(o)] ?? String(o)}</span>
            </motion.button>
          );
        })}
      </div>
    </div>
  );

  return (
    <div className="space-y-3">
      {row("shape", "Body", shapes)}
      {row("hue", "Color", hueNames)}
      {row("eyes", "Eyes", eyeStyles)}
      {row("accessory", "Topper", accessories)}
      {row("mouth", "Mouth", mouths)}
    </div>
  );
}

export function SurpriseButton({ onPick }: { onPick: (l: Look) => void }) {
  return (
    <motion.button
      type="button"
      whileHover={{ rotate: -12, scale: 1.05 }}
      whileTap={{ rotate: 200, scale: 0.9 }}
      transition={bouncy}
      onClick={() => onPick({ seed: randomSeed() })}
      className="inline-flex items-center gap-1.5 h-9 px-3 rounded-full bg-surface-2 text-[13px] font-display font-extrabold cursor-pointer"
    >
      <Icon.dice /> Surprise me
    </motion.button>
  );
}

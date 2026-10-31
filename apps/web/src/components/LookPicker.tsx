import { motion } from "motion/react";
import { eyeStyles, Gbot, hueNames, randomSeed, resolveLook, shapes, type Look, type Traits } from "@gbot/characters";
import { hues } from "@gbot/ui-tokens";
import { bouncy, Icon } from "./ui";

const cap = (s: string) => s[0].toUpperCase() + s.slice(1);
const eyeLabels: Record<string, string> = { dot: "Dots", pill: "Tall", wide: "Wide" };

export function LookPicker({ look, seed, onChange }: { look: Look; seed: string; onChange: (l: Look) => void }) {
  const t = resolveLook(look, seed);
  const setT = (patch: Partial<Traits>) => onChange({ ...t, seed: look.seed ?? seed, ...patch });

  return (
    <div className="space-y-4">
      <Row title="Shape">
        {shapes.map((o) => (
          <Choice key={o} on={t.shape === o} label={cap(o)} onClick={() => setT({ shape: o })}>
            <Gbot look={{ ...t, shape: o }} seed={seed} size={36} />
          </Choice>
        ))}
      </Row>
      <Row title="Color">
        {hueNames.map((o) => (
          <Choice key={o} on={t.hue === o} label={cap(o)} onClick={() => setT({ hue: o })} round>
            <span className="block size-7 rounded-full" style={{ background: hues[o].body, boxShadow: "inset 0 0 0 1px rgba(0,0,0,.08)" }} />
          </Choice>
        ))}
      </Row>
      <Row title="Eyes">
        {eyeStyles.map((o) => (
          <Choice key={o} on={t.eyes === o} label={eyeLabels[o]} onClick={() => setT({ eyes: o })}>
            <Gbot look={{ ...t, eyes: o }} seed={seed} size={36} />
          </Choice>
        ))}
      </Row>
    </div>
  );
}

function Row({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="text-[12px] font-semibold uppercase tracking-[.08em] text-ink-2 mb-2">{title}</div>
      <div className="flex flex-wrap gap-1.5">{children}</div>
    </div>
  );
}

function Choice({ on, label, onClick, round, children }: { on: boolean; label: string; onClick: () => void; round?: boolean; children: React.ReactNode }) {
  return (
    <motion.button
      type="button"
      whileTap={{ scale: 0.92 }}
      transition={bouncy}
      onClick={onClick}
      title={label}
      aria-label={label}
      aria-pressed={on}
      className={`grid place-items-center ${round ? "size-10 rounded-full" : "size-12 rounded-[14px]"} transition-colors cursor-pointer ${
        on ? "bg-surface ring-2 ring-ink" : "hover:bg-surface-2"
      }`}
    >
      {children}
    </motion.button>
  );
}

export function SurpriseButton({ onPick }: { onPick: (l: Look) => void }) {
  return (
    <motion.button
      type="button"
      whileTap={{ scale: 0.92 }}
      transition={bouncy}
      onClick={() => onPick({ seed: randomSeed() })}
      className="inline-flex items-center gap-1.5 h-8 px-3 rounded-full bg-surface-2 text-[13px] font-semibold cursor-pointer hover:bg-line transition-colors"
    >
      <Icon.dice /> Shuffle
    </motion.button>
  );
}

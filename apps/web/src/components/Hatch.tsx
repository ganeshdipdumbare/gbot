import { useEffect, useRef, useState, type FormEvent } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Gbot, randomSeed, type Look, type Mood } from "@gbot/characters";
import { api } from "../lib/api";
import { upsertBot } from "../lib/store";
import { useGaze } from "../lib/hooks";
import { Modal } from "./Modal";
import { LookPicker, SurpriseButton } from "./LookPicker";
import { bouncy, Button, Input, Label } from "./ui";

const names = ["Mochi", "Pip", "Biscuit", "Nori", "Pudding", "Tofu", "Bean", "Kiwi", "Dumpling", "Sprout", "Poppy", "Momo", "Waffles", "Juniper", "Taro"];
const jobs = ["Inbox helper", "Research buddy", "Writing pal", "Code sidekick", "Trip planner", "Study coach"];

function Egg({ onHatched }: { onHatched: () => void }) {
  const [crack, setCrack] = useState(0);
  // The parent re-renders often (gaze, mood); keep the timers running.
  const done = useRef(onHatched);
  done.current = onHatched;
  useEffect(() => {
    const ts = [setTimeout(() => setCrack(1), 500), setTimeout(() => setCrack(2), 1000), setTimeout(() => done.current(), 1450)];
    return () => ts.forEach(clearTimeout);
  }, []);
  return (
    <motion.svg
      viewBox="0 0 100 120" width="130" height="156"
      animate={{ rotate: crack === 0 ? [0, -6, 6, -4, 0] : [0, -10, 10, -8, 8, 0], scale: crack === 2 ? 1.06 : 1 }}
      transition={{ duration: 0.5, repeat: Infinity }}
      style={{ transformOrigin: "50% 90%" }}
      aria-label="An egg is hatching"
    >
      <ellipse cx="50" cy="114" rx="28" ry="4" fill="currentColor" opacity=".1" />
      <path d="M50 8C72 8 88 46 88 72C88 96 72 110 50 110C28 110 12 96 12 72C12 46 28 8 50 8Z" fill="#FFF6E9" stroke="#F1DCC4" strokeWidth="2" />
      <circle cx="34" cy="50" r="5" fill="#FFD9E4" /><circle cx="64" cy="36" r="4" fill="#D9C8FF" /><circle cx="68" cy="78" r="6" fill="#B8EBD3" /><circle cx="30" cy="86" r="4" fill="#FFE7A3" />
      <ellipse cx="36" cy="28" rx="7" ry="4" fill="#fff" opacity=".8" transform="rotate(-30 36 28)" />
      {crack >= 1 && <path d="M30 60L38 54L44 62L52 52L58 60L66 54L72 60" fill="none" stroke="#C9AE91" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />}
      {crack >= 2 && <path d="M44 62L42 70M58 60L61 68" fill="none" stroke="#C9AE91" strokeWidth="2" strokeLinecap="round" />}
    </motion.svg>
  );
}

export function Hatch({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: (id: string) => void }) {
  const [stage, setStage] = useState<"egg" | "custom">("egg");
  const [seed, setSeed] = useState(randomSeed);
  const [look, setLook] = useState<Look>({});
  const [name, setName] = useState("");
  const [title, setTitle] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [mood, setMood] = useState<Mood>("happy");
  const [ref, gaze] = useGaze<HTMLDivElement>();

  useEffect(() => {
    if (!open) return;
    const s = randomSeed();
    setSeed(s);
    setLook({ seed: s });
    setStage("egg");
    setName(names[Math.floor(Math.random() * names.length)]);
    setTitle("");
    setError("");
    setMood("happy");
  }, [open]);

  useEffect(() => {
    if (mood !== "happy") return;
    const t = setTimeout(() => setMood("idle"), 1400);
    return () => clearTimeout(t);
  }, [mood, look]);

  async function create(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      const b = await api.createBot({ name, title, look: { ...look, seed: look.seed ?? seed } });
      upsertBot(b);
      onCreated(b.id);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open={open} onClose={onClose} title={stage === "egg" ? "" : "Say hello to your new bot"} width={560}>
      <AnimatePresence mode="wait">
        {stage === "egg" ? (
          <motion.div key="egg" className="grid place-items-center py-14 text-ink" exit={{ scale: 1.3, opacity: 0 }} transition={{ duration: 0.18 }}>
            <Egg onHatched={() => setStage("custom")} />
            <p className="font-display font-extrabold text-ink-2 mt-4">Something's hatching…</p>
          </motion.div>
        ) : (
          <motion.form key="custom" onSubmit={create} initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="px-5 pb-5">
            <div ref={ref} className="relative flex flex-col items-center pt-1 pb-4">
              <motion.div initial={{ scale: 0, y: 30 }} animate={{ scale: 1, y: 0 }} transition={{ ...bouncy, stiffness: 300, damping: 11 }}>
                <Gbot look={look} seed={seed} size={140} mood={mood} lookAt={gaze} />
              </motion.div>
              <div className="absolute right-0 top-1">
                <SurpriseButton onPick={(l) => { setLook(l); setMood("happy"); }} />
              </div>
            </div>
            <div className="grid sm:grid-cols-2 gap-3 mb-4">
              <div>
                <Label>Name</Label>
                <Input value={name} onChange={(e) => setName(e.target.value)} maxLength={40} required />
              </div>
              <div>
                <Label hint="optional">Job</Label>
                <Input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={80} placeholder={jobs[name.length % jobs.length]} />
              </div>
            </div>
            <LookPicker look={look} seed={seed} onChange={(l) => { setLook(l); setMood("happy"); }} />
            {error && <p className="text-danger text-sm mt-3" role="alert">{error}</p>}
            <div className="flex justify-end gap-2 mt-5">
              <Button type="button" variant="ghost" onClick={onClose}>Not now</Button>
              <Button type="submit" disabled={busy || !name.trim()}>Welcome, {name.trim() || "friend"}!</Button>
            </div>
          </motion.form>
        )}
      </AnimatePresence>
    </Modal>
  );
}

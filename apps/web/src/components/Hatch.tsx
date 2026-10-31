import { useEffect, useState, type FormEvent } from "react";
import { motion } from "motion/react";
import { Gbot, randomSeed, type Look, type Mood } from "@gbot/characters";
import { api } from "../lib/api";
import { upsertBot } from "../lib/store";
import { useGaze } from "../lib/hooks";
import { Modal } from "./Modal";
import { LookPicker, SurpriseButton } from "./LookPicker";
import { bouncy, Button, Input, Label } from "./ui";

const names = ["Pip", "Nori", "Juno", "Otto", "Kiwi", "Momo", "Taro", "Ziggy", "Remy", "Bo", "Luma", "Sol", "Echo", "Pixel", "Mika"];
const jobs = ["Inbox helper", "Research", "Writing", "Code reviews", "Trip planning", "Study coach"];

export function Hatch({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: (id: string) => void }) {
  const [seed, setSeed] = useState(randomSeed);
  const [look, setLook] = useState<Look>({});
  const [name, setName] = useState("");
  const [title, setTitle] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [mood, setMood] = useState<Mood>("idle");
  const [ref, gaze] = useGaze<HTMLDivElement>();

  useEffect(() => {
    if (!open) return;
    const s = randomSeed();
    setSeed(s);
    setLook({ seed: s });
    setName(names[Math.floor(Math.random() * names.length)]);
    setTitle("");
    setError("");
  }, [open]);

  // A small happy hop whenever the look changes.
  const react = (l: Look) => {
    setLook(l);
    setMood("happy");
    setTimeout(() => setMood("idle"), 700);
  };

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
      setMood("blocked");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open={open} onClose={onClose} title="New bot" width={520}>
      <form onSubmit={create} className="px-5 pb-5">
        <div ref={ref} className="relative grid place-items-center pt-2 pb-5">
          <motion.div key={seed} initial={{ scale: 0.6, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} transition={bouncy}>
            <Gbot look={look} seed={seed} size={128} mood={mood} lookAt={gaze} />
          </motion.div>
          <div className="absolute right-0 top-0">
            <SurpriseButton onPick={react} />
          </div>
        </div>
        <div className="grid sm:grid-cols-2 gap-3 mb-5">
          <div>
            <Label>Name</Label>
            <Input value={name} onChange={(e) => setName(e.target.value)} maxLength={40} required />
          </div>
          <div>
            <Label hint="optional">Job</Label>
            <Input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={80} placeholder={jobs[name.length % jobs.length]} />
          </div>
        </div>
        <LookPicker look={look} seed={seed} onChange={react} />
        {error && <p className="text-danger text-sm mt-3" role="alert">{error}</p>}
        <div className="flex justify-end gap-2 mt-6">
          <Button type="button" variant="ghost" onClick={onClose}>Cancel</Button>
          <Button type="submit" disabled={busy || !name.trim()}>Create {name.trim() || "bot"}</Button>
        </div>
      </form>
    </Modal>
  );
}

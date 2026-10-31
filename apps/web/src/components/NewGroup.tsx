import { useEffect, useState } from "react";
import { motion } from "motion/react";
import { Gbot } from "@gbot/characters";
import { api, type Group } from "../lib/api";
import { upsertGroup, useStore } from "../lib/store";
import { Modal } from "./Modal";
import { bouncy, Button, Icon, Input, Label } from "./ui";
import { Stack } from "./Chat";

export function NewGroup({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: (g: Group) => void }) {
  const bots = useStore((s) => s.bots);
  const [name, setName] = useState("");
  const [picked, setPicked] = useState<string[]>([]);
  const [error, setError] = useState("");

  useEffect(() => { if (open) { setName(""); setPicked([]); setError(""); } }, [open]);
  const toggle = (id: string) => setPicked((p) => (p.includes(id) ? p.filter((x) => x !== id) : p.length >= 6 ? p : [...p, id]));
  const chosen = picked.map((id) => bots.find((b) => b.id === id)!).filter(Boolean);

  async function create() {
    setError("");
    try {
      const g = await api.createGroup({ name: name.trim() || chosen.map((b) => b.name).join(", "), botIds: picked });
      upsertGroup(g);
      onCreated(g);
    } catch (e) { setError((e as Error).message); }
  }

  return (
    <Modal open={open} onClose={onClose} title="New group" width={520}>
      <div className="px-5 pb-5 space-y-4">
        <div className="grid place-items-center h-20">
          {chosen.length ? <Stack bots={chosen} size={56} /> : <span className="text-ink-2"><Icon.users /></span>}
        </div>
        <div><Label hint="optional">Name</Label><Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Launch team" maxLength={40} /></div>
        <div>
          <Label hint={`${picked.length}/6 · the first one leads`}>Bots</Label>
          {bots.length < 2 ? (
            <p className="text-[14px] text-ink-2">Create at least two bots first.</p>
          ) : (
            <div className="grid grid-cols-3 sm:grid-cols-4 gap-2">
              {bots.map((b) => {
                const i = picked.indexOf(b.id);
                return (
                  <motion.button key={b.id} whileTap={{ scale: 0.94 }} transition={bouncy} onClick={() => toggle(b.id)}
                    className={`relative flex flex-col items-center gap-1 rounded-[18px] p-2.5 cursor-pointer transition-colors ${i >= 0 ? "bg-surface ring-2 ring-ink" : "hover:bg-surface-2"}`}
                    aria-pressed={i >= 0}>
                    <Gbot look={b.look} seed={b.id} size={44} mood={i >= 0 ? "happy" : "idle"} />
                    <span className="text-[13px] font-semibold truncate max-w-full">{b.name}</span>
                    {i >= 0 && <span className="absolute top-1.5 right-1.5 size-5 rounded-full bg-ink text-bg text-[11px] font-bold grid place-items-center">{i + 1}</span>}
                  </motion.button>
                );
              })}
            </div>
          )}
        </div>
        {error && <p className="text-danger text-sm">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button onClick={create} disabled={picked.length < 2}>Create group</Button>
        </div>
      </div>
    </Modal>
  );
}

export function GroupSettings({ open, group, onClose }: { open: boolean; group?: Group; onClose: () => void }) {
  const bots = useStore((s) => s.bots);
  const [confirm, setConfirm] = useState(false);
  useEffect(() => setConfirm(false), [open]);
  if (!group) return null;
  const members = group.botIds.map((id) => bots.find((b) => b.id === id)).filter(Boolean);
  return (
    <Modal open={open} onClose={onClose} side width={420} title={group.name}>
      <div className="px-5 pb-6 space-y-5">
        <ul className="space-y-1">
          {members.map((b, i) => (
            <li key={b!.id} className="flex items-center gap-3 rounded-2xl px-2 py-2">
              <Gbot look={b!.look} seed={b!.id} size={36} />
              <div className="min-w-0">
                <div className="font-semibold text-[14px]">{b!.name}{i === 0 && <span className="ml-2 text-[11px] font-semibold uppercase tracking-wide text-live">Lead</span>}</div>
                <div className="text-[12.5px] text-ink-2 truncate">{b!.title || "Teammate"}</div>
              </div>
            </li>
          ))}
        </ul>
        <p className="text-[13px] text-ink-2">Each bot keeps its own memory. The group's chat is shared by everyone in it.</p>
        <div className="border-t border-line pt-4">
          {confirm ? (
            <div className="flex items-center gap-2">
              <span className="text-[14px] flex-1">Archive this group?</span>
              <Button variant="ghost" onClick={() => setConfirm(false)}>Keep</Button>
              <Button variant="danger" onClick={async () => { await api.archiveGroup(group.id); onClose(); }}>Archive</Button>
            </div>
          ) : <Button variant="danger" onClick={() => setConfirm(true)}>Archive group</Button>}
        </div>
      </div>
    </Modal>
  );
}

import { useEffect, useState } from "react";
import { Gbot, type Look } from "@gbot/characters";
import { api } from "../lib/api";
import { upsertBot, useStore } from "../lib/store";
import { Modal } from "./Modal";
import { LookPicker, SurpriseButton } from "./LookPicker";
import { ModelSelect } from "./ModelSelect";
import { Button, Icon, Input, Label, TextArea } from "./ui";

export function BotSettings({ open, botId, onClose }: { open: boolean; botId: string | null; onClose: () => void }) {
  const bot = useStore((s) => s.bots.find((b) => b.id === botId));
  const [name, setName] = useState("");
  const [title, setTitle] = useState("");
  const [instructions, setInstructions] = useState("");
  const [model, setModel] = useState("");
  const [look, setLook] = useState<Look>({});
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState("");
  const [confirm, setConfirm] = useState(false);

  useEffect(() => {
    if (!open || !bot) return;
    setName(bot.name); setTitle(bot.title); setInstructions(bot.instructions); setModel(bot.model); setLook(bot.look ?? {});
    setError(""); setConfirm(false);
  }, [open, bot?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!bot) return null;

  async function save() {
    setError("");
    try {
      const b = await api.updateBot(bot!.id, { name, title, instructions, model, look });
      upsertBot(b);
      setSaved(true);
      setTimeout(() => setSaved(false), 1400);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <Modal open={open} onClose={onClose} side width={460} title={
      <span className="flex items-center gap-2"><Gbot look={look} seed={bot.id} size={34} props={false} mood={saved ? "happy" : "idle"} />{bot.name}</span>
    }>
      <div className="px-5 pb-6 space-y-5">
        <div className="grid grid-cols-2 gap-3">
          <div><Label>Name</Label><Input value={name} maxLength={40} onChange={(e) => setName(e.target.value)} /></div>
          <div><Label>Job</Label><Input value={title} maxLength={80} onChange={(e) => setTitle(e.target.value)} placeholder="What they do" /></div>
        </div>
        <div>
          <Label hint="You can also just tell them in chat">Instructions</Label>
          <TextArea rows={6} value={instructions} onChange={(e) => setInstructions(e.target.value)} placeholder={`e.g. "You help me plan my week. Keep answers short and bubbly."`} />
        </div>
        <div>
          <Label>Model</Label>
          <ModelSelect value={model} onChange={setModel} allowDefault="Account default" />
        </div>
        <div>
          <div className="flex items-center justify-between mb-2"><Label>Look</Label><SurpriseButton onPick={setLook} /></div>
          <LookPicker look={look} seed={bot.id} onChange={setLook} />
        </div>
        {error && <p className="text-danger text-sm" role="alert">{error}</p>}
        <div className="flex items-center gap-2 sticky bottom-0 bg-bg py-3 -mb-3">
          <Button onClick={save} className="flex-1">{saved ? "Saved ♡" : "Save changes"}</Button>
          <Button variant="soft" onClick={async () => upsertBot(await api.updateBot(bot.id, { pinned: !bot.pinned }))} aria-label={bot.pinned ? "Unpin" : "Pin"}>
            <Icon.pin /> {bot.pinned ? "Unpin" : "Pin"}
          </Button>
        </div>
        <div className="border-t border-line pt-4">
          {confirm ? (
            <div className="flex items-center gap-2">
              <span className="text-[14px] flex-1">Archive {bot.name}? Their chat is kept.</span>
              <Button variant="ghost" onClick={() => setConfirm(false)}>Keep</Button>
              <Button variant="danger" onClick={async () => { await api.archiveBot(bot.id); onClose(); }}>Archive</Button>
            </div>
          ) : (
            <Button variant="danger" onClick={() => setConfirm(true)}>Archive bot</Button>
          )}
        </div>
      </div>
    </Modal>
  );
}

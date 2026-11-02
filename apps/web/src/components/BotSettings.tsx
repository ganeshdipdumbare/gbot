import { useEffect, useState } from "react";
import { Gbot, type Look } from "@gbot/characters";
import { api } from "../lib/api";
import { upsertBot, useStore } from "../lib/store";
import { attempt, toast } from "../lib/toast";
import { Modal } from "./Modal";
import { LookPicker, SurpriseButton } from "./LookPicker";
import { ModelSelect } from "./ModelSelect";
import { Toggle } from "./SidePanel";
import { ShareSection } from "./Sharing";
import { speak, useSystemVoices } from "../lib/voice";
import { Button, Icon, Input, Label, TextArea } from "./ui";

export function BotSettings({ open, botId, onClose }: { open: boolean; botId: string | null; onClose: () => void }) {
  const bot = useStore((s) => s.bots.find((b) => b.id === botId));
  const [name, setName] = useState("");
  const [title, setTitle] = useState("");
  const [instructions, setInstructions] = useState("");
  const [model, setModel] = useState("");
  const [look, setLook] = useState<Look>({});
  const [network, setNetwork] = useState(false);
  const [readAloud, setReadAloud] = useState(false);
  const [voice, setVoice] = useState("");
  const voices = useSystemVoices();
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState("");
  const [confirm, setConfirm] = useState(false);

  useEffect(() => {
    if (!open || !bot) return;
    setName(bot.name); setTitle(bot.title); setInstructions(bot.instructions); setModel(bot.model); setLook(bot.look ?? {});
    setNetwork(bot.computerNetwork); setReadAloud(bot.readAloud); setVoice(bot.voice);
    setError(""); setConfirm(false);
  }, [open, bot?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!bot) return null;
  // Owners and managers change how a bot works; collaborators only chat with it.
  const role = bot.role ?? "owner";
  const canManage = role === "owner" || role === "manager";

  async function save() {
    setError("");
    try {
      const b = await api.updateBot(bot!.id, { name, title, instructions, model, look, computerNetwork: network, readAloud, voice });
      upsertBot(b);
      toast("Saved");
      setSaved(true);
      setTimeout(() => setSaved(false), 1400);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <Modal open={open} onClose={onClose} side width={460} title={
      <span className="flex items-center gap-2"><Gbot look={look} seed={bot.id} size={34} mood={saved ? "happy" : "idle"} />{bot.name}</span>
    }>
      <div className="px-5 pb-6 space-y-5">
        {!canManage && (
          <p className="rounded-2xl bg-surface-2 p-3 text-[13px] text-ink-2">Shared with you. Only its owner and managers can change how it works.</p>
        )}
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
        <div className="rounded-2xl border border-line divide-y divide-line">
          <div className="flex items-center gap-3 p-3">
            <span className="text-ink-2"><Icon.globe /></span>
            <div className="flex-1">
              <div className="font-semibold text-[14px]">Internet on its computer</div>
              <div className="text-[12.5px] text-ink-2">Off keeps its computer fully offline.</div>
            </div>
            <Toggle on={network} onChange={setNetwork} label="Internet access" />
          </div>
          <div className="flex items-center gap-3 p-3">
            <span className="text-ink-2"><Icon.speaker /></span>
            <div className="flex-1">
              <div className="font-semibold text-[14px]">Read replies aloud</div>
              <div className="text-[12.5px] text-ink-2">Uses this computer's voices.</div>
            </div>
            <Toggle on={readAloud} onChange={setReadAloud} label="Read replies aloud" />
          </div>
          {voices.length > 0 && (
            <div className="flex items-center gap-3 p-3">
              <span className="text-ink-2"><Icon.mic /></span>
              <select value={voice} onChange={(e) => setVoice(e.target.value)} aria-label="Voice"
                className="flex-1 h-9 rounded-xl bg-surface-2 px-2 text-[14px] outline-none cursor-pointer">
                <option value="">Default voice</option>
                {voices.map((v) => <option key={v.name} value={v.name}>{v.name}{v.localService ? "" : " (online)"}</option>)}
              </select>
              <Button variant="ghost" onClick={() => speak(`Hi, I'm ${name}.`, voice)}><Icon.play /></Button>
            </div>
          )}
        </div>
        <div>
          <div className="flex items-center justify-between mb-2"><Label>Look</Label><SurpriseButton onPick={setLook} /></div>
          <LookPicker look={look} seed={bot.id} onChange={setLook} />
        </div>
        {error && <p className="text-danger text-sm" role="alert">{error}</p>}
        {canManage && <div className="border-t border-line pt-4"><ShareSection botId={bot.id} role={role} /></div>}
        {canManage && <div className="flex items-center gap-2 sticky bottom-0 bg-bg py-3 -mb-3">
          <Button onClick={save} className="flex-1">Save changes</Button>
          <Button variant="soft" onClick={async () => { const b = await attempt(() => api.updateBot(bot.id, { pinned: !bot.pinned }), bot.pinned ? "Unpinned" : "Pinned"); if (b) upsertBot(b); }} aria-label={bot.pinned ? "Unpin" : "Pin"}>
            <Icon.pin /> {bot.pinned ? "Unpin" : "Pin"}
          </Button>
        </div>}
        {role === "owner" && <div className="border-t border-line pt-4">
          {confirm ? (
            <div className="flex items-center gap-2">
              <span className="text-[14px] flex-1">Archive {bot.name}? Their chat is kept.</span>
              <Button variant="ghost" onClick={() => setConfirm(false)}>Keep</Button>
              <Button variant="danger" onClick={async () => { if (await attempt(() => api.archiveBot(bot.id), `${bot.name} archived`) !== undefined) onClose(); }}>Archive</Button>
            </div>
          ) : (
            <Button variant="danger" onClick={() => setConfirm(true)}>Archive bot</Button>
          )}
        </div>}
      </div>
    </Modal>
  );
}

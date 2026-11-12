import { useEffect, useState } from "react";
import { Gbot, type Look } from "@gbot/characters";
import { api } from "../lib/api";
import { clearChat, upsertBot, useStore } from "../lib/store";
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
  const me = useStore((s) => s.me);
  const [name, setName] = useState("");
  const [title, setTitle] = useState("");
  const [instructions, setInstructions] = useState("");
  const [model, setModel] = useState("");
  const [look, setLook] = useState<Look>({});
  const [network, setNetwork] = useState(false);
  const [hosts, setHosts] = useState("");
  const [readAloud, setReadAloud] = useState(false);
  const [voice, setVoice] = useState("");
  const voices = useSystemVoices();
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState("");
  const [confirm, setConfirm] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [forgetToo, setForgetToo] = useState(false);

  useEffect(() => {
    if (!open || !bot) return;
    setName(bot.name); setTitle(bot.title); setInstructions(bot.instructions); setModel(bot.model); setLook(bot.look ?? {});
    setNetwork(bot.computerNetwork); setHosts((bot.allowedHosts ?? []).join("\n")); setReadAloud(bot.readAloud); setVoice(bot.voice);
    setError(""); setConfirm(false);
  }, [open, bot?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!bot) return null;
  // Owners and managers change how a bot works; collaborators only chat with it.
  const role = bot.role ?? "owner";
  const canManage = role === "owner" || role === "manager";

  async function save() {
    setError("");
    try {
      const allowedHosts = hosts.split("\n").map((s) => s.trim()).filter(Boolean);
      const b = await api.updateBot(bot!.id, { name, title, instructions, model, look, computerNetwork: network, allowedHosts, readAloud, voice });
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
          <Label hint="Account default follows Settings → Models">Model</Label>
          <ModelSelect value={model} onChange={setModel} allowDefault="Account default" fallback={me?.defaultModel} />
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
          {network && (
            <div className="p-3">
              <Label hint="One per site; all its subdomains are included">Only these sites</Label>
              <TextArea rows={3} value={hosts} onChange={(e) => setHosts(e.target.value)} placeholder={"linkedin.com\nindeed.com"} />
              <p className="text-[12px] text-ink-3 mt-1.5">Empty allows the whole internet. This checks the pages it visits, not the ads, CDNs or assets a page loads. Shell commands still use the network.</p>
            </div>
          )}
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
        <div className="border-t border-line pt-4">
          {clearing ? (
            <div className="space-y-3 rounded-2xl bg-surface p-4">
              <p className="text-[14px]">
                <span className="font-medium">Clear your chat with {bot.name}?</span>{" "}
                Every message goes, and {bot.name} starts fresh: it won't remember this conversation. Anything running stops. This can't be undone.
              </p>
              <div className="flex items-center gap-3">
                <div className="flex-1">
                  <div className="text-[14px]">Also forget what it learned</div>
                  <div className="text-[12.5px] text-ink-3">Preferences and facts it remembers about you</div>
                </div>
                <Toggle on={forgetToo} onChange={setForgetToo} label="Also forget what it learned" />
              </div>
              <div className="flex justify-end gap-2">
                <Button variant="ghost" onClick={() => setClearing(false)}>Cancel</Button>
                <Button variant="danger" onClick={async () => {
                  if (await attempt(() => api.clearChat(bot.id, forgetToo), "Chat cleared") !== undefined) { clearChat(bot.id); setClearing(false); }
                }}>Clear</Button>
              </div>
            </div>
          ) : (
            <Button variant="soft" onClick={() => { setForgetToo(false); setClearing(true); }}><Icon.trash /> Clear chat history</Button>
          )}
        </div>
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

import { useEffect, useRef, useState, type KeyboardEvent, type MouseEvent, type WheelEvent } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Gbot } from "@gbot/characters";
import { api, type Bot, type ComputerStatus, type Routine } from "../lib/api";
import { set, useStore } from "../lib/store";
import { moodOf } from "../lib/hooks";
import { bouncy, Button, Icon, IconButton, Input, Label, TextArea } from "./ui";

// The bot's own place: its computer (status → preview → takeover) and
// the routines it runs by itself.

export function SidePanel({ bot, tab, onTab, onClose, takeover, setTakeover }: {
  bot: Bot; tab: "computer" | "routines"; onTab: (t: "computer" | "routines") => void; onClose: () => void;
  takeover: boolean; setTakeover: (v: boolean) => void;
}) {
  return (
    <div className="h-full flex flex-col bg-bg">
      <div className="flex items-center gap-1 px-3 h-16 shrink-0 border-b border-line/70">
        {(["computer", "routines"] as const).map((t) => (
          <button key={t} onClick={() => onTab(t)}
            className={`h-9 px-3.5 rounded-full text-[14px] font-semibold cursor-pointer transition-colors ${tab === t ? "bg-surface-2 text-ink" : "text-ink-2 hover:text-ink"}`}>
            {t === "computer" ? "Computer" : "Routines"}
          </button>
        ))}
        <div className="flex-1" />
        <IconButton label="Close panel" onClick={onClose}><Icon.close /></IconButton>
      </div>
      <div className="flex-1 overflow-y-auto scroll-soft">
        {tab === "computer" ? <ComputerTab bot={bot} onTakeover={() => setTakeover(true)} /> : <RoutinesTab bot={bot} />}
      </div>
      <Takeover bot={bot} open={takeover} onClose={() => setTakeover(false)} />
    </div>
  );
}

function useComputer(bot: Bot) {
  const status = useStore((s) => s.computers[bot.id]);
  useEffect(() => {
    api.computer(bot.id).then((c) => set((s) => ({ computers: { ...s.computers, [bot.id]: c } }))).catch(() => {});
  }, [bot.id]);
  const update = (c: ComputerStatus) => set((s) => ({ computers: { ...s.computers, [bot.id]: c } }));
  return [status, update] as const;
}

/** Polls the screen while it's on and the page is visible. */
function useScreen(botId: string, on: boolean, fps = 1.2) {
  const [src, setSrc] = useState<string | null>(null);
  useEffect(() => {
    if (!on) { setSrc(null); return; }
    let stop = false;
    let last: string | null = null;
    const loop = async () => {
      while (!stop) {
        if (document.visibilityState === "visible") {
          try {
            const res = await fetch(api.screenUrl(botId), { credentials: "same-origin" });
            if (res.ok) {
              const url = URL.createObjectURL(await res.blob());
              setSrc(url);
              if (last) URL.revokeObjectURL(last);
              last = url;
            }
          } catch { /* keep the last frame */ }
        }
        await new Promise((r) => setTimeout(r, 1000 / fps));
      }
    };
    loop();
    return () => { stop = true; if (last) URL.revokeObjectURL(last); };
  }, [botId, on, fps]);
  return src;
}

function ComputerTab({ bot, onTakeover }: { bot: Bot; onTakeover: () => void }) {
  const [status, update] = useComputer(bot);
  const presence = useStore((s) => s.presence[bot.id]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const on = status?.state === "on";
  const src = useScreen(bot.id, on);

  async function act(a: "start" | "stop") {
    setBusy(true);
    setError("");
    try { update(await api.computerAction(bot.id, a)); } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }

  return (
    <div className="p-4 space-y-4">
      <div className="flex items-center gap-3">
        <Gbot look={bot.look} seed={bot.id} size={36} mood={moodOf(presence, bot)} live={on} />
        <div className="min-w-0 flex-1">
          <div className="font-display font-extrabold text-[16px]">{bot.name}'s computer</div>
          <div className="text-[12.5px] text-ink-2 flex items-center gap-1.5">
            <span className={`size-1.5 rounded-full ${on ? "bg-live" : status?.state === "starting" ? "bg-amber-400" : "bg-ink-2/40"}`} />
            {status?.state === "starting" ? "Starting…" : on ? (status.controller === "user" ? "You have control" : "On") : "Asleep"}
            <span className="opacity-50">·</span>
            <Icon.globe />
            {bot.computerNetwork ? "Online" : "No network"}
          </div>
        </div>
        {on ? (
          <Button variant="ghost" disabled={busy} onClick={() => act("stop")}>Sleep</Button>
        ) : (
          <Button variant="soft" disabled={busy || status?.state === "starting" || status?.available === false} onClick={() => act("start")}>Wake</Button>
        )}
      </div>

      <div className="relative aspect-[16/10] rounded-[18px] overflow-hidden bg-surface-2 border border-line">
        <AnimatePresence>
          {src ? (
            <motion.img key="screen" src={src} alt={`${bot.name}'s screen`} className="absolute inset-0 size-full object-cover" initial={{ opacity: 0 }} animate={{ opacity: 1 }} />
          ) : (
            <motion.div key="off" className="absolute inset-0 grid place-items-center text-center p-6" initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
              <div>
                <Gbot look={bot.look} seed={bot.id} size={56} mood={status?.state === "starting" ? "working" : "sleepy"} className="mx-auto" />
                <p className="text-[13px] text-ink-2 mt-2">
                  {status?.available === false ? "Docker or the computer image isn't available. Run `make computer`." :
                    status?.state === "starting" ? "Waking up…" : "Its computer wakes when it needs it."}
                </p>
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      {on && (
        <Button className="w-full" onClick={onTakeover}><Icon.screen /> Take control</Button>
      )}
      {error && <p className="text-danger text-[13px]">{error}</p>}
      <p className="text-[12.5px] text-ink-2 leading-relaxed">
        A private Linux desktop with a browser, terminal and files, running on this machine in Docker.
        {bot.computerNetwork ? " It can reach the internet." : " It has no network until you allow it in the bot's settings."}
      </p>
    </div>
  );
}

const keyMap: Record<string, string> = {
  Enter: "Return", Backspace: "BackSpace", Tab: "Tab", Escape: "Escape", Delete: "Delete",
  ArrowUp: "Up", ArrowDown: "Down", ArrowLeft: "Left", ArrowRight: "Right", Home: "Home", End: "End", PageUp: "Prior", PageDown: "Next",
};

function Takeover({ bot, open, onClose }: { bot: Bot; open: boolean; onClose: () => void }) {
  const [status, update] = useComputer(bot);
  const [error, setError] = useState("");
  const area = useRef<HTMLDivElement>(null);
  const src = useScreen(bot.id, open && status?.state === "on", 4);

  useEffect(() => {
    if (!open) return;
    api.computerAction(bot.id, "control", { user: true }).then(update).catch((e) => setError(e.message));
    setTimeout(() => area.current?.focus(), 50);
  }, [open, bot.id]); // eslint-disable-line react-hooks/exhaustive-deps

  async function release() {
    try { update(await api.computerAction(bot.id, "control", { user: false })); } catch { /* closing anyway */ }
    onClose();
  }

  const send = (body: Record<string, unknown>) => api.computerAction(bot.id, "input", body).catch((e) => setError(e.message));
  const pos = (e: MouseEvent | WheelEvent) => {
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
    return { x: (e.clientX - r.left) / r.width, y: (e.clientY - r.top) / r.height };
  };
  const onKey = (e: KeyboardEvent) => {
    if (e.key === "Escape" && e.shiftKey) return release();
    e.preventDefault();
    const mods = [e.ctrlKey && "ctrl", e.altKey && "alt", e.metaKey && "super"].filter(Boolean) as string[];
    if (e.key.length === 1 && mods.length === 0) send({ type: "type", text: e.key });
    else if (keyMap[e.key] || e.key.length === 1) send({ type: "key", key: [...mods, keyMap[e.key] ?? e.key].join("+") });
  };

  return (
    <AnimatePresence>
      {open && (
        <motion.div className="fixed inset-0 z-50 bg-[#0E0B12]/90 backdrop-blur-sm flex flex-col" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
          <div className="flex items-center gap-3 px-4 h-14 text-white">
            <Gbot look={bot.look} seed={bot.id} size={30} mood="waiting" live />
            <div className="font-semibold">You have control of {bot.name}'s computer</div>
            <span className="text-white/50 text-[13px] hidden sm:inline">Shift+Esc to hand it back</span>
            <div className="flex-1" />
            <Button onClick={release} className="bg-white! text-[#16131C]!">Hand back</Button>
          </div>
          <div className="flex-1 grid place-items-center p-4 min-h-0">
            <motion.div
              ref={area}
              tabIndex={0}
              onKeyDown={onKey}
              onClick={(e) => send({ type: "click", ...pos(e) })}
              onDoubleClick={(e) => send({ type: "dblclick", ...pos(e) })}
              onContextMenu={(e) => { e.preventDefault(); send({ type: "rightclick", ...pos(e) }); }}
              onWheel={(e) => send({ type: "scroll", deltaY: e.deltaY, ...pos(e) })}
              initial={{ scale: 0.96 }} animate={{ scale: 1 }} transition={bouncy}
              className="relative w-full max-w-[1280px] aspect-[16/10] rounded-[18px] overflow-hidden ring-2 ring-live outline-none cursor-default bg-black"
              aria-label={`${bot.name}'s screen. Click and type to control it.`}
            >
              {src && <img src={src} alt="" draggable={false} className="absolute inset-0 size-full object-contain select-none pointer-events-none" />}
            </motion.div>
          </div>
          {error && <p className="text-center text-red-300 text-sm pb-3">{error}</p>}
        </motion.div>
      )}
    </AnimatePresence>
  );
}

const presets = [
  { label: "Every hour", cron: "0 * * * *" },
  { label: "Every day", cron: "0 {h} * * *", time: true },
  { label: "Weekdays", cron: "0 {h} * * 1-5", time: true },
  { label: "Mondays", cron: "0 {h} * * 1", time: true },
  { label: "Custom", cron: "" },
];

function RoutinesTab({ bot }: { bot: Bot }) {
  const [list, setList] = useState<Routine[] | null>(null);
  const [adding, setAdding] = useState(false);
  const [secret, setSecret] = useState<Routine | null>(null);
  const load = () => api.routines(bot.id).then((r) => setList(r.routines)).catch(() => setList([]));
  useEffect(() => { load(); }, [bot.id]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="p-4 space-y-3">
      <div className="flex items-center">
        <div className="flex-1">
          <div className="font-display font-extrabold text-[16px]">Routines</div>
          <div className="text-[12.5px] text-ink-2">Prompts {bot.name} runs by itself. Or just ask in chat.</div>
        </div>
        {!adding && <Button variant="soft" onClick={() => setAdding(true)}><Icon.plus /> New</Button>}
      </div>

      <AnimatePresence initial={false}>
        {adding && <NewRoutine bot={bot} onDone={(r) => { setAdding(false); if (r) { load(); if (r.secret) setSecret(r); } }} />}
      </AnimatePresence>

      {secret && (
        <div className="rounded-[18px] border-2 border-live/40 bg-live-soft p-3 text-[13px] space-y-2">
          <div className="font-semibold">Webhook ready. Copy the key now; it won't be shown again.</div>
          <code className="block break-all bg-surface rounded-xl px-2.5 py-2">POST {secret.webhookUrl}</code>
          <code className="block break-all bg-surface rounded-xl px-2.5 py-2">Authorization: Bearer {secret.secret}</code>
          <button className="text-ink-2 font-semibold cursor-pointer" onClick={() => setSecret(null)}>Done</button>
        </div>
      )}

      {list?.length === 0 && !adding && (
        <div className="text-center py-8">
          <Gbot look={bot.look} seed={bot.id} size={56} mood="sleepy" className="mx-auto" />
          <p className="text-[13px] text-ink-2 mt-2">No routines yet. Try “Every weekday at 8am, summarize the news.”</p>
        </div>
      )}

      <ul className="space-y-2">
        {list?.map((r) => (
          <motion.li key={r.id} layout className="rounded-[18px] bg-surface border border-line p-3">
            <div className="flex items-start gap-3">
              <span className={`mt-0.5 ${r.active ? "text-live" : "text-ink-2/50"}`}><Icon.clock /></span>
              <div className="min-w-0 flex-1">
                <div className="font-semibold text-[14px] truncate">{r.name}</div>
                <div className="text-[12.5px] text-ink-2">{r.active ? r.when : "Paused"}{r.active && r.nextRunAt && r.kind === "schedule" ? ` · next ${new Date(r.nextRunAt).toLocaleString(undefined, { weekday: "short", hour: "numeric", minute: "2-digit" })}` : ""}</div>
                <div className="text-[13px] mt-1.5 line-clamp-2">{r.prompt}</div>
              </div>
              <Toggle on={r.active} label={r.active ? "Pause" : "Resume"} onChange={async (v) => { await api.updateRoutine(bot.id, r.id, { active: v }); load(); }} />
            </div>
            <div className="flex gap-1 mt-2 -mb-1 -ml-1">
              <button className="h-8 px-2.5 rounded-full text-[12.5px] font-semibold text-ink-2 hover:text-ink hover:bg-surface-2 cursor-pointer inline-flex items-center gap-1"
                onClick={() => api.runRoutine(bot.id, r.id)}><Icon.play /> Run now</button>
              <button className="h-8 px-2.5 rounded-full text-[12.5px] font-semibold text-ink-2 hover:text-danger hover:bg-surface-2 cursor-pointer inline-flex items-center gap-1"
                onClick={async () => { await api.deleteRoutine(bot.id, r.id); load(); }}><Icon.trash /> Delete</button>
            </div>
          </motion.li>
        ))}
      </ul>
    </div>
  );
}

export function Toggle({ on, onChange, label }: { on: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <button role="switch" aria-checked={on} aria-label={label} title={label} onClick={() => onChange(!on)}
      className={`relative shrink-0 w-11 h-6 rounded-full transition-colors cursor-pointer ${on ? "bg-live" : "bg-line"}`}>
      <motion.span className="absolute top-0.5 size-5 rounded-full bg-white shadow" animate={{ left: on ? 22 : 2 }} transition={bouncy} />
    </button>
  );
}

function NewRoutine({ bot, onDone }: { bot: Bot; onDone: (r?: Routine) => void }) {
  const [name, setName] = useState("");
  const [prompt, setPrompt] = useState("");
  const [kind, setKind] = useState<"schedule" | "webhook">("schedule");
  const [preset, setPreset] = useState(2);
  const [time, setTime] = useState("08:00");
  const [custom, setCustom] = useState("0 9 * * *");
  const [error, setError] = useState("");

  const cron = () => {
    const p = presets[preset];
    if (!p.cron) return custom;
    const [h, m] = time.split(":").map(Number);
    return p.cron.replace("0 {h}", `${m} ${h}`);
  };

  async function save() {
    setError("");
    try {
      onDone(await api.createRoutine(bot.id, { name, prompt, kind, schedule: kind === "schedule" ? cron() : undefined }));
    } catch (e) { setError((e as Error).message); }
  }

  return (
    <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} className="overflow-hidden">
      <div className="rounded-[18px] bg-surface border border-line p-3 space-y-3">
        <div><Label>Name</Label><Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Morning briefing" maxLength={80} /></div>
        <div><Label>Instruction</Label><TextArea rows={3} value={prompt} onChange={(e) => setPrompt(e.target.value)} placeholder="Summarize what's new and what needs me today." /></div>
        <div>
          <Label>When</Label>
          <div className="flex gap-1.5 mb-2">
            {(["schedule", "webhook"] as const).map((k) => (
              <button key={k} onClick={() => setKind(k)} className={`h-8 px-3 rounded-full text-[13px] font-semibold cursor-pointer ${kind === k ? "bg-ink text-bg" : "bg-surface-2"}`}>
                {k === "schedule" ? "On a schedule" : "Webhook"}
              </button>
            ))}
          </div>
          {kind === "schedule" && (
            <div className="flex flex-wrap gap-1.5 items-center">
              {presets.map((p, i) => (
                <button key={p.label} onClick={() => setPreset(i)}
                  className={`h-8 px-3 rounded-full text-[13px] cursor-pointer border ${preset === i ? "border-ink font-semibold" : "border-line text-ink-2"}`}>{p.label}</button>
              ))}
              {presets[preset].time && <input type="time" value={time} onChange={(e) => setTime(e.target.value)} className="h-8 px-2 rounded-full bg-surface-2 text-[13px]" aria-label="Time" />}
              {!presets[preset].cron && <Input value={custom} onChange={(e) => setCustom(e.target.value)} className="h-9! font-mono text-[13px]!" aria-label="Cron schedule" />}
            </div>
          )}
          {kind === "webhook" && <p className="text-[12.5px] text-ink-2">Any service can start it with a POST. Text it sends is treated as outside content, so changes still ask you first.</p>}
        </div>
        {error && <p className="text-danger text-[13px]">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => onDone()}>Cancel</Button>
          <Button onClick={save} disabled={!name.trim() || !prompt.trim()}>Save</Button>
        </div>
      </div>
    </motion.div>
  );
}

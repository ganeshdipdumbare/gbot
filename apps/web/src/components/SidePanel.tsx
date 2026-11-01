import { useEffect, useRef, useState, type KeyboardEvent, type MouseEvent, type ReactNode, type WheelEvent } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Gbot } from "@gbot/characters";
import { api, type Bot, type ComputerStatus, type Routine } from "../lib/api";
import { set, useStore } from "../lib/store";
import { attempt } from "../lib/toast";
import { bouncy, Button, Icon, IconButton, Input, Label, TextArea } from "./ui";

// The bot's own place: its computer (status → preview → takeover) and
// the routines it runs by itself.

export function SidePanel({ bot, onClose, takeover, setTakeover }: {
  bot: Bot; onClose: () => void;
  takeover: boolean; setTakeover: (v: boolean) => void;
}) {
  const status = useStore((s) => s.computers[bot.id]);
  const label = status?.state === "on" ? (status.controller === "user" ? "You have control" : "Running")
    : status?.state === "starting" ? "Starting" : "Asleep";
  return (
    <div className="h-full flex flex-col bg-bg">
      <div className="flex items-center gap-1 pl-4 pr-2 h-11 shrink-0">
        <span className="text-[14px] text-ink-2 truncate flex-1">{bot.name}'s computer <span className="text-ink-3">· {label}</span></span>
        <IconButton label="Close panel" onClick={onClose}><Icon.close /></IconButton>
      </div>
      <div className="flex-1 overflow-y-auto scroll-soft">
        <ComputerTab bot={bot} onTakeover={() => setTakeover(true)} />
        <RoutinesTab bot={bot} />
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

type DeskApp = "browser" | "terminal" | "files";

function AppDock({ current, onShow }: { current: DeskApp; onShow: (app: DeskApp) => void }) {
  const apps: { id: DeskApp; label: string; icon: ReactNode }[] = [
    { id: "browser", label: "Browser", icon: <Icon.globe /> },
    { id: "terminal", label: "Terminal", icon: <Icon.terminal /> },
    { id: "files", label: "Files", icon: <Icon.folder /> },
  ];
  return (
    <div
      className="absolute bottom-2.5 left-1/2 z-10 flex -translate-x-1/2 gap-1 rounded-full bg-black/75 p-1 shadow-pop backdrop-blur-sm"
      onMouseDown={(e) => e.stopPropagation()}
      onClick={(e) => e.stopPropagation()}
    >
      {apps.map((a) => (
        <button
          key={a.id}
          type="button"
          aria-label={a.label}
          title={a.label}
          aria-pressed={current === a.id}
          onClick={() => onShow(a.id)}
          className={`grid size-9 cursor-pointer place-items-center rounded-full transition-colors ${current === a.id ? "bg-white text-[#16131C]" : "text-white/90 hover:bg-white/15"}`}
        >
          {a.icon}
        </button>
      ))}
    </div>
  );
}

function ComputerTab({ bot, onTakeover }: { bot: Bot; onTakeover: () => void }) {
  const [status, update] = useComputer(bot);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [app, setApp] = useState<DeskApp>("browser");
  const on = status?.state === "on";
  const src = useScreen(bot.id, on);

  async function show(next: DeskApp) {
    setApp(next);
    setError("");
    try { update(await api.computerAction(bot.id, "show", { app: next })); }
    catch (e) { setError((e as Error).message); }
  }

  async function act(a: "start" | "stop") {
    setBusy(true);
    setError("");
    try { update(await api.computerAction(bot.id, a)); } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }

  return (
    <div className="px-4 pb-2 space-y-3">
      <div className="relative aspect-[16/10] rounded-2xl overflow-hidden bg-surface hairline">
        <AnimatePresence>
          {src ? (
            <motion.img key="screen" src={src} alt={`${bot.name}'s screen`} className="absolute inset-0 size-full object-cover" initial={{ opacity: 0 }} animate={{ opacity: 1 }} />
          ) : (
            <motion.div key="off" className="absolute inset-0 grid place-items-center text-center p-6" initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
              <div>
                <Gbot look={bot.look} seed={bot.id} size={40} mood={status?.state === "starting" ? "working" : "sleepy"} className="mx-auto" />
                <p className="text-[13px] text-ink-3 mt-2">
                  {status?.available === false ? "The computer image isn't built yet. Run make computer." :
                    status?.state === "starting" ? "Waking up…" : "Wakes up when it needs it."}
                </p>
              </div>
            </motion.div>
          )}
        </AnimatePresence>
        {on && <AppDock current={app} onShow={show} />}
      </div>

      <div className="flex items-center gap-2">
        <span className="text-[14px] text-ink-2 flex-1 truncate">{bot.name}'s screen</span>
        {on ? (
          <>
            <button onClick={() => act("stop")} disabled={busy} className="h-8 px-3 rounded-full text-[13px] text-ink-3 hover:text-ink hover:bg-hover cursor-pointer transition-colors duration-[120ms]">Sleep</button>
            <button onClick={onTakeover} className="h-8 px-3.5 rounded-full hairline text-[13px] font-medium hover:bg-hover cursor-pointer transition-colors duration-[120ms]">Take control</button>
          </>
        ) : (
          <button onClick={() => act("start")} disabled={busy || status?.state === "starting" || status?.available === false}
            className="h-8 px-3.5 rounded-full hairline text-[13px] font-medium hover:bg-hover cursor-pointer transition-colors duration-[120ms] disabled:opacity-40">Wake</button>
        )}
      </div>
      {error && <p className="text-danger text-[13px]">{error}</p>}
      <p className="text-[12px] text-ink-3 leading-relaxed flex items-start gap-1.5">
        <span className="[&_svg]:size-3.5 mt-px"><Icon.globe /></span>
        <span>
          {status?.backend === "microsandbox" ? "Its own microVM on this machine." : "A container on this machine."}
          {bot.computerNetwork ? " Online." : " Offline until you allow internet in its settings."}
        </span>
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
  const [app, setApp] = useState<DeskApp>("browser");
  const area = useRef<HTMLDivElement>(null);
  const src = useScreen(bot.id, open && status?.state === "on", 4);

  async function show(next: DeskApp) {
    setApp(next);
    setError("");
    try { await api.computerAction(bot.id, "show", { app: next }); }
    catch (e) { setError((e as Error).message); }
  }

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
              className="relative w-full max-w-[1280px] aspect-[16/10] rounded-xl overflow-hidden ring-1 ring-live outline-none cursor-default bg-black"
              aria-label={`${bot.name}'s screen. Click and type to control it.`}
            >
              {src && <img src={src} alt="" draggable={false} className="absolute inset-0 size-full object-contain select-none pointer-events-none" />}
              <AppDock current={app} onShow={show} />
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
    <div className="px-4 pt-4 pb-6">
      <div className="text-[14px] text-ink-2 mb-1">Routines</div>

      {secret && (
        <div className="rounded-2xl bg-surface p-3 text-[13px] space-y-2 my-2">
          <div className="font-medium">Webhook ready. Copy the key now; it won't be shown again.</div>
          <code className="block break-all bg-bg rounded-lg px-2.5 py-2 font-mono text-[12px]">POST {secret.webhookUrl}</code>
          <code className="block break-all bg-bg rounded-lg px-2.5 py-2 font-mono text-[12px]">Authorization: Bearer {secret.secret}</code>
          <button className="text-ink-2 hover:text-ink cursor-pointer" onClick={() => setSecret(null)}>Done</button>
        </div>
      )}

      <ul>
        {list?.map((r) => (
          <li key={r.id} className="group">
            <div className="flex items-center gap-2.5 h-10 -mx-2 px-2 rounded-xl hover:bg-hover transition-colors duration-[120ms]">
              <span className={`[&_svg]:size-3.5 ${r.active ? "text-warning" : "text-ink-3"}`}><Icon.clock /></span>
              <span className={`text-[14px] truncate flex-1 ${r.active ? "" : "text-ink-3"}`} title={r.prompt}>{r.name}</span>
              <span className="text-[12px] text-ink-3 shrink-0 group-hover:hidden">{r.active ? r.when : "Paused"}</span>
              <span className="hidden group-hover:flex items-center gap-0.5 shrink-0">
                <IconButton label="Run now" className="size-7! [&_svg]:size-3.5" onClick={() => attempt(() => api.runRoutine(bot.id, r.id), "Running now")}><Icon.play /></IconButton>
                <IconButton label={r.active ? "Pause" : "Resume"} className="size-7! [&_svg]:size-3.5"
                  onClick={async () => { await attempt(() => api.updateRoutine(bot.id, r.id, { active: !r.active }), r.active ? "Routine paused" : "Routine resumed"); load(); }}>
                  {r.active ? <Icon.stop /> : <Icon.play />}
                </IconButton>
                <IconButton label="Delete" className="size-7! hover:text-danger! [&_svg]:size-3.5" onClick={async () => { await attempt(() => api.deleteRoutine(bot.id, r.id), "Routine deleted"); load(); }}><Icon.trash /></IconButton>
              </span>
            </div>
          </li>
        ))}
      </ul>

      <AnimatePresence initial={false}>
        {adding && <NewRoutine bot={bot} onDone={(r) => { setAdding(false); if (r) { load(); if (r.secret) setSecret(r); } }} />}
      </AnimatePresence>
      {!adding && (
        <button onClick={() => setAdding(true)} className="flex items-center gap-2 h-10 -mx-2 px-2 w-[calc(100%+16px)] rounded-xl text-[14px] text-ink-3 hover:text-ink hover:bg-hover cursor-pointer transition-colors duration-[120ms] [&_svg]:size-3.5">
          <Icon.plus /> New routine
        </button>
      )}
      {list?.length === 0 && !adding && <p className="text-[12px] text-ink-3 mt-1">Or ask in chat: “every weekday at 8am, summarize the news.”</p>}
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
      <div className="rounded-2xl bg-surface p-3 space-y-3">
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
          {kind === "webhook" && <p className="text-[12px] text-ink-3">Any service can start it with a POST. Text it sends is treated as outside content, so changes still ask you first.</p>}
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

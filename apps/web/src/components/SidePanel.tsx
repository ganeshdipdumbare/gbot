import { useEffect, useRef, useState, type CompositionEvent, type FormEvent, type KeyboardEvent, type MouseEvent, type TouchEvent as RTouchEvent, type WheelEvent } from "react";
import { createPortal } from "react-dom";
import { AnimatePresence, motion } from "motion/react";
import { Gbot } from "@gbot/characters";
import { api, type Bot, type ComputerStatus, type Routine } from "../lib/api";
import { onLive, set, useStore } from "../lib/store";
import { attempt, toastError } from "../lib/toast";
import { bouncy, Button, fade, Icon, IconButton, Input, Label, leave, spring, TextArea } from "./ui";

// The bot's own place: its computer (status → preview → takeover) and
// the routines it runs by itself.

export function SidePanel({ bot, onClose, takeover, setTakeover }: {
  bot: Bot; onClose: () => void;
  takeover: boolean; setTakeover: (v: boolean) => void;
}) {
  const status = useStore((s) => s.computers[bot.id]);
  const [watch, setWatch] = useState(false);
  const label = status?.state === "on" ? (status.controller === "user" ? "You have control" : "Running")
    : status?.state === "starting" ? "Starting" : "Asleep";
  const closeView = () => { setWatch(false); setTakeover(false); };
  return (
    <div className="h-full flex flex-col bg-bg">
      <div className="flex items-center gap-0.5 pl-4 pr-2 h-11 shrink-0">
        <span className="text-[14px] text-ink-2 truncate flex-1">{bot.name}'s computer <span className="text-ink-3">· {label}</span></span>
        <ComputerActions bot={bot} onWatch={() => setWatch(true)} onTakeover={() => setTakeover(true)} />
        <IconButton label="Close panel" onClick={onClose}><Icon.close /></IconButton>
      </div>
      <div className="flex-1 overflow-y-auto scroll-soft">
        <ComputerTab bot={bot} onOpen={() => setWatch(true)} viewing={watch || takeover} />
        <RoutinesTab bot={bot} />
      </div>
      <ComputerView bot={bot} open={watch || takeover} control={takeover} onClose={closeView} />
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
              const blob = await res.blob();
              // Unmounted or turned off while the frame was loading: create no URL.
              if (stop) break;
              const url = URL.createObjectURL(blob);
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

// Wake, sleep, watch and take control, kept small in the panel's header.
function ComputerActions({ bot, onWatch, onTakeover }: { bot: Bot; onWatch: () => void; onTakeover: () => void }) {
  const [status, update] = useComputer(bot);
  const [busy, setBusy] = useState(false);
  const on = status?.state === "on";
  const asleep = status?.state === "asleep";

  async function act(a: "start" | "sleep" | "wake") {
    setBusy(true);
    try { update(await api.computerAction(bot.id, a)); }
    catch (e) {
      toastError(e, a === "start" ? "Couldn't wake the computer"
        : a === "sleep" ? "Couldn't put it to sleep"
        : "Couldn't wake the computer");
    } finally { setBusy(false); }
  }

  if (on) return (
    <>
      <IconButton label="Watch full screen" onClick={onWatch}><Icon.expand /></IconButton>
      <IconButton label="Take control" onClick={onTakeover}><Icon.control /></IconButton>
      <IconButton label="Sleep (keeps its windows and state)" disabled={busy} onClick={() => act("sleep")}><Icon.moon /></IconButton>
    </>
  );
  return (
    <IconButton label="Wake" disabled={busy || status?.state === "starting" || status?.available === false}
      onClick={() => act(asleep ? "wake" : "start")} className="disabled:opacity-40"><Icon.power /></IconButton>
  );
}

function ComputerTab({ bot, onOpen, viewing }: { bot: Bot; onOpen: () => void; viewing: boolean }) {
  const [status] = useComputer(bot);
  const on = status?.state === "on";
  const sleeping = status?.state === "asleep";
  // The full-screen stream replaces the thumbnail while it is open.
  const src = useScreen(bot.id, on && !viewing);
  const where = `${status?.backend === "microsandbox" ? "Its own microVM on this machine." : "A container on this machine."}${bot.computerNetwork ? " Online." : " Offline until you allow internet in its settings."}`;

  return (
    <div className="px-4 pb-2">
      <button type="button" onClick={onOpen} aria-label={`Watch ${bot.name}'s screen full screen`}
        className="group relative block w-full aspect-[16/10] rounded-2xl overflow-hidden bg-surface shadow-[var(--shadow)] cursor-pointer">
        <AnimatePresence initial={false}>
          {src ? (
            <motion.img key="screen" src={src} alt={`${bot.name}'s screen`} className="absolute inset-0 size-full object-cover" initial={{ opacity: 0 }} animate={{ opacity: 1 }} />
          ) : (
            <motion.div key="off" className="absolute inset-0 grid place-items-center text-center p-6 bg-surface" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
              <div className="px-4 py-3 text-ink">
                <Gbot look={bot.look} seed={bot.id} size={36} mood={status?.state === "starting" ? "working" : "sleepy"} className="mx-auto" />
                <p className="text-[13px] mt-1.5 text-ink-2">
                  {status?.available === false ? "The computer image isn't built yet. Run make computer." :
                    status?.state === "starting" ? "Waking up…" :
                    sleeping ? "Asleep — wake it from the header to keep going" :
                    viewing ? "Open in the full-screen view" :
                    on ? "Connecting to the screen…" : "Wakes up when it needs it"}
                </p>
              </div>
            </motion.div>
          )}
        </AnimatePresence>
        {src && (
          <span className="absolute right-2 top-2 inline-flex items-center gap-1 rounded-full bg-black/55 text-white text-[12px] px-2 py-1 opacity-0 group-hover:opacity-100 transition-opacity">
            <Icon.expand /> Watch
          </span>
        )}
      </button>
      <p className="text-center text-[12px] text-ink-3 mt-2" title={where}>{bot.name}'s screen</p>
    </div>
  );
}

const keyMap: Record<string, string> = {
  Enter: "Return", Backspace: "BackSpace", Tab: "Tab", Escape: "Escape", Delete: "Delete",
  ArrowUp: "Up", ArrowDown: "Down", ArrowLeft: "Left", ArrowRight: "Right", Home: "Home", End: "End", PageUp: "Prior", PageDown: "Next",
};

// Watch the screen full screen, and only take over when asked. Watching
// sends no input, so the bot keeps working while the person looks on.
function ComputerView({ bot, open, control, onClose }: {
  bot: Bot; open: boolean; control: boolean; onClose: () => void;
}) {
  const [status, update] = useComputer(bot);
  const [error, setError] = useState("");
  const [holding, setHolding] = useState(false);
  const area = useRef<HTMLDivElement>(null);
  // Mobile keyboards only open for an editable element, so a transparent
  // textarea sits over the screen and collects real keystrokes and IME.
  const keyboard = useRef<HTMLTextAreaElement>(null);
  const composing = useRef(false);
  // One MJPEG connection renders every frame; no request per frame.
  const src = open && status?.state === "on" ? api.streamUrl(bot.id) : null;

  async function take() {
    setError("");
    // Focus inside the tap so iOS opens the keyboard, then again after the
    // server confirms control (the first focus may happen while watching).
    keyboard.current?.focus({ preventScroll: true });
    try {
      update(await api.computerAction(bot.id, "control", { user: true }));
      setHolding(true);
      setTimeout(() => keyboard.current?.focus({ preventScroll: true }), 60);
    } catch (e) { setError(e instanceof Error ? e.message : "Couldn't take control"); }
  }

  // Mirrors `holding` for cleanup code, which runs after the state is gone.
  const holdingRef = useRef(false);
  holdingRef.current = holding;
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  // Inputs go one at a time, in order. Firing every scroll tick at once
  // queues dozens of execs inside the computer and makes it feel stuck.
  const chain = useRef<Promise<unknown>>(Promise.resolve());

  // Hands control back to the bot. Pending input goes out first, in order,
  // so nothing lands after the release. Safe to call after unmount.
  async function releaseControl() {
    flush();
    flushScroll();
    const wasHolding = holdingRef.current;
    holdingRef.current = false;
    // Let the last input land before handing control back, or the server
    // may reject it as "take control first".
    await chain.current.catch(() => {});
    if (wasHolding) {
      try { update(await api.computerAction(bot.id, "control", { user: false })); } catch { /* closing anyway */ }
    }
    if (mounted.current) setHolding(false);
  }

  async function release() {
    await releaseControl();
    onClose();
  }

  // A takeover card opens straight into control; watching stays passive.
  // Any way of leaving the view, including the panel's own Close button,
  // gives control back.
  useEffect(() => {
    if (open && control) { take(); return; }
    if (holdingRef.current) void releaseControl();
  }, [open, control]); // eslint-disable-line react-hooks/exhaustive-deps

  const send = (body: Record<string, unknown>) => {
    const p = chain.current.then(() => api.computerAction(bot.id, "input", body));
    chain.current = p.catch((e) => setError(e instanceof Error ? e.message : "Input failed"));
    return p;
  };

  // Typed characters are batched into one request instead of one per
  // key, which keeps typing responsive over the network.
  const queued = useRef("");
  const timer = useRef<number | null>(null);
  const flush = () => {
    if (timer.current !== null) { window.clearTimeout(timer.current); timer.current = null; }
    if (!queued.current) return;
    const text = queued.current;
    queued.current = "";
    send({ type: "type", text });
  };
  const queueText = (text: string) => {
    queued.current += text;
    if (timer.current === null) timer.current = window.setTimeout(flush, 35);
  };

  // Trackpads emit dozens of wheel events. Sum them and send one scroll
  // per 55ms with the right number of wheel clicks.
  const scroll = useRef({ dy: 0, x: 0.5, y: 0.5, timer: null as number | null });
  const scrollBusy = useRef(false);
  const flushScroll = () => {
    const s = scroll.current;
    if (s.timer !== null) { window.clearTimeout(s.timer); s.timer = null; }
    const dy = s.dy;
    if (!dy || scrollBusy.current) return;
    s.dy = 0;
    scrollBusy.current = true;
    send({ type: "scroll", deltaY: dy, x: s.x, y: s.y }).finally(() => {
      scrollBusy.current = false;
      // Everything the person scrolled while this one was in flight goes
      // in the next request, so the queue cannot grow without bound.
      if (scroll.current.dy) flushScroll();
    });
  };
  const queueScroll = (dy: number, x: number, y: number) => {
    const s = scroll.current;
    s.dy += dy;
    s.x = x;
    s.y = y;
    if (s.timer === null) s.timer = window.setTimeout(flushScroll, 55);
  };
  // Leaving the screen (unmount or bot switch) must not leave the bot locked
  // out of its own computer, and must not send input after the release.
  useEffect(() => () => {
    if (scroll.current.timer !== null) window.clearTimeout(scroll.current.timer);
    void releaseControl();
  }, [bot.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const posAt = (el: HTMLElement, clientX: number, clientY: number) => {
    const r = el.getBoundingClientRect();
    return { x: (clientX - r.left) / r.width, y: (clientY - r.top) / r.height };
  };
  const pos = (e: MouseEvent | WheelEvent) => posAt(e.currentTarget as HTMLElement, e.clientX, e.clientY);
  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Escape" && e.shiftKey) { e.preventDefault(); return release(); }
    if (!holding || e.nativeEvent.isComposing || e.key === "Process" || e.key === "Unidentified") return;
    const mods = [e.ctrlKey && "ctrl", e.altKey && "alt", e.metaKey && "super"].filter(Boolean) as string[];
    if (e.key.length === 1 && mods.length === 0) {
      // Don't preventDefault: the input event carries the composed
      // character, including mobile autocorrect and IME replacements.
      return;
    }
    e.preventDefault();
    flush();
    flushScroll();
    if (keyMap[e.key] || e.key.length === 1) send({ type: "key", key: [...mods, keyMap[e.key] ?? e.key].join("+") });
  };
  // The textarea is only a buffer: send what it received and clear it, so
  // the next character starts fresh. This is what makes the phone keyboard
  // type into the bot's computer.
  const onInput = (e: FormEvent<HTMLTextAreaElement>) => {
    if (composing.current) return;
    const el = e.currentTarget;
    const text = el.value;
    el.value = "";
    if (text && holding) queueText(text);
  };
  const onCompositionStart = () => { composing.current = true; };
  const onCompositionEnd = (e: CompositionEvent<HTMLTextAreaElement>) => {
    composing.current = false;
    const text = e.currentTarget.value;
    e.currentTarget.value = "";
    if (text) queueText(text);
  };
  // Touch: a drag scrolls, a tap clicks. The browser also fires a click
  // after a tap, so a drag must swallow the click that follows it.
  const touch = useRef({ y: 0 });
  const dragged = useRef(false);
  const onTouchStart = (e: RTouchEvent<HTMLDivElement>) => {
    if (!holding || !e.touches[0]) return;
    touch.current.y = e.touches[0].clientY;
    dragged.current = false;
  };
  const onTouchMove = (e: RTouchEvent<HTMLDivElement>) => {
    if (!holding || !e.touches[0]) return;
    const t = e.touches[0];
    const dy = touch.current.y - t.clientY;
    touch.current.y = t.clientY;
    if (Math.abs(dy) < 1) return;
    if (Math.abs(dy) > 4) dragged.current = true;
    const p = posAt(e.currentTarget, t.clientX, t.clientY);
    queueScroll(dy * 1.6, p.x, p.y);
    e.preventDefault();
  };
  const click = (type: string) => (e: MouseEvent) => {
    if (!holding) return;
    if (dragged.current) { dragged.current = false; return; }
    keyboard.current?.focus({ preventScroll: true });
    flush();
    flushScroll();
    send({ type, ...pos(e) });
  };

  // Through a portal, like Modal: nested in the side panel the overlay sits
  // in the panel's own stacking context, and the chat header (its own
  // backdrop-blur layer) paints straight through the top of it.
  return createPortal(
    <AnimatePresence>
      {open && (
        <motion.div className="fixed inset-0 z-50 bg-[#0E0B12]/90 backdrop-blur-sm flex flex-col" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={fade}>
          <div className="flex items-center gap-2 px-3 sm:px-4 h-14 text-white">
            <Gbot look={bot.look} seed={bot.id} size={30} mood={holding ? "waiting" : "idle"} live className="shrink-0" />
            <div className="font-semibold min-w-0 truncate text-[14px] sm:text-[15px]">
              {holding ? `You have control of ${bot.name}'s computer` : `Watching ${bot.name}'s computer`}
            </div>
            <span className="text-white/50 text-[13px] hidden md:inline shrink-0">
              {holding ? "Shift+Esc to hand it back" : "The bot keeps working while you watch"}
            </span>
            <div className="flex-1" />
            {holding
              ? <Button onClick={release} className="bg-white! text-[#16131C]! whitespace-nowrap shrink-0">Hand back</Button>
              : <Button onClick={take} className="bg-white! text-[#16131C]! whitespace-nowrap shrink-0">Take control</Button>}
            <Button onClick={release} className="bg-white/10! text-white! whitespace-nowrap shrink-0">Close</Button>
          </div>
          <div className="flex-1 grid place-items-center p-4 min-h-0">
            <motion.div
              ref={area}
              onClick={click("click")}
              onDoubleClick={click("dblclick")}
              onContextMenu={(e) => { e.preventDefault(); if (holding) click("rightclick")(e); }}
              onTouchStart={onTouchStart}
              onTouchMove={onTouchMove}
              onWheel={(e) => {
                if (!holding) return;
                e.preventDefault();
                const p = pos(e);
                queueScroll(e.deltaY, p.x, p.y);
              }}
              initial={{ scale: 0.98 }} animate={{ scale: 1 }} transition={spring}
              className={`relative touch-none w-[min(100%,1280px,calc((100dvh-88px)*1.6))] aspect-[16/10] rounded-2xl overflow-hidden ring-1 ring-white/10 shadow-[0_24px_80px_-16px_rgba(0,0,0,0.7)] outline-none bg-black ${holding ? "cursor-default" : "cursor-pointer"}`}
              aria-label={holding ? `${bot.name}'s screen. Click and type to control it.` : `${bot.name}'s screen. Take control to click and type.`}
            >
              <textarea
                ref={keyboard}
                onKeyDown={onKey}
                onInput={onInput}
                onCompositionStart={onCompositionStart}
                onCompositionEnd={onCompositionEnd}
                autoCapitalize="off"
                autoCorrect="off"
                autoComplete="off"
                spellCheck={false}
                aria-label={`Type into ${bot.name}'s computer`}
                className={`absolute inset-0 size-full resize-none border-0 bg-transparent p-0 text-transparent caret-transparent opacity-0 outline-none text-[16px] ${holding ? "cursor-crosshair" : "pointer-events-none"}`}
              />
              {src
                ? <img key={src} src={src} alt="" draggable={false} className="absolute inset-0 size-full object-contain select-none pointer-events-none" />
                : <div className="absolute inset-0 grid place-items-center text-white/60 text-sm">
                  {status?.state === "starting" ? "Waking up…" : status?.state === "on" ? "Connecting to the screen…" : "Its computer is asleep"}
                </div>}
              {!holding && status?.state === "on" && (
                <button type="button" onClick={(e) => { e.stopPropagation(); keyboard.current?.focus({ preventScroll: true }); take(); }}
                  className="absolute bottom-3 left-1/2 -translate-x-1/2 inline-flex items-center gap-1.5 rounded-full bg-black/65 hover:bg-black/80 text-white text-[13px] px-3 py-1.5 whitespace-nowrap">
                  <Icon.control /> Take control
                </button>
              )}
            </motion.div>
          </div>
          {error && <p className="text-center text-red-300 text-sm pb-3">{error}</p>}
        </motion.div>
      )}
    </AnimatePresence>,
    document.body,
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
  // A routine made in chat (or on another device) shows here at once.
  useEffect(() => onLive((e) => {
    if (e.type === "routine" && e.botId === bot.id) load();
  }), [bot.id]); // eslint-disable-line react-hooks/exhaustive-deps

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
            <div className="flex items-center gap-2.5 min-h-12 py-1.5 -mx-2 px-2 rounded-xl hover:bg-hover transition-colors duration-[120ms]">
              <span className={`self-start mt-[3px] [&_svg]:size-3.5 ${r.active ? "text-ink-2" : "text-ink-3"}`}><Icon.clock /></span>
              <span className="min-w-0 flex-1" title={r.prompt}>
                <span className={`block text-[14px] leading-5 truncate ${r.active ? "" : "text-ink-3"}`}>{r.name}</span>
                <span className="block text-[12px] leading-4 text-ink-3 truncate">{r.active ? r.when : "Paused"}</span>
              </span>
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
    <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0, transition: leave }} transition={spring} className="overflow-hidden">
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

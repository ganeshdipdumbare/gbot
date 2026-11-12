import { memo, useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Gbot } from "@gbot/characters";
import type { Bot, Group, Message, Presence, QueueItem } from "../lib/api";
import { api } from "../lib/api";
import { clearQueue, get as useStoreGet, groupKey, isGroupKey, loadMessages, loadOlder, loadQueue, onLive, queueAction, retry, sendMessage, set as setStore, useStore, type ChatKey } from "../lib/store";
import { toast } from "../lib/toast";
import { Skeleton } from "./Feedback";
import { moodOf, presenceText, useGaze } from "../lib/hooks";
import { renderMarkdown } from "../lib/markdown";
import { speak, stopSpeaking } from "../lib/voice";
import { Card } from "./Card";
import { Composer } from "./Composer";
import { Modal } from "./Modal";
import { bouncy, Button, Icon, IconButton } from "./ui";

export function Chat({ chatKey, onMenu, onSettings, onHatch, onComputer, panelOpen }: {
  chatKey: ChatKey | null; onMenu?: () => void; onSettings: () => void; onHatch: () => void;
  onComputer: (takeover?: boolean) => void; panelOpen: boolean;
}) {
  const group = useStore((s) => (isGroupKey(chatKey) ? s.groups.find((g) => groupKey(g.id) === chatKey) : undefined));
  const bot = useStore((s) => (!isGroupKey(chatKey) ? s.bots.find((b) => b.id === chatKey) : undefined));
  const bots = useStore((s) => s.bots);
  const messages = useStore((s) => (chatKey ? s.messages[chatKey] : undefined));
  const presence = useStore((s) => s.presence);
  const computers = useStore((s) => s.computers);
  const loaded = useStore((s) => s.bots.length > 0 || s.me === null);
  const [error, setError] = useState("");

  useEffect(() => {
    if (chatKey) loadMessages(chatKey).catch((e) => setError(e.message));
  }, [chatKey]);
  useEffect(() => {
    if (chatKey) loadQueue(chatKey).catch(() => {});
  }, [chatKey]);
  // Read the computer once so the green dot is right after a reload,
  // even when the bot is idle and no run has published presence.
  useEffect(() => {
    if (!bot) return;
    api.computer(bot.id).then((c) => setStore((s) => ({ computers: { ...s.computers, [bot.id]: c } }))).catch(() => {});
  }, [bot?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const members = useMemo(() => (group ? group.botIds.map((id) => bots.find((b) => b.id === id)).filter(Boolean) as Bot[] : []), [group, bots]);

  // Read finished replies aloud for bots that have it on.
  useEffect(() => onLive((e) => {
    if (e.type !== "message_done" || e.data.role !== "bot" || !e.data.content) return;
    const k = e.data.groupId ? groupKey(e.data.groupId) : e.data.botId;
    if (k !== chatKey) return;
    const b = bots.find((x) => x.id === e.data.botId);
    if (b?.readAloud) speak(e.data.content, b.voice).catch(() => {});
  }), [chatKey, bots]);

  if (!bot && !group) {
    return (
      <div className="h-full flex flex-col">
        {onMenu && <div className="p-3"><IconButton label="Bots" onClick={onMenu}><Icon.menu /></IconButton></div>}
        <div className="flex-1 grid place-items-center p-6 text-center">
          {loaded && (
            <div>
              <Gbot seed="empty" look={{ shape: "arch", hue: "sun", eyes: "wide" }} mood="sleepy" size={64} className="mx-auto" />
              <h2 className="text-[15px] font-semibold mt-4">No bots yet</h2>
              <p className="text-[13px] text-ink-2 mt-1">Create one and give it a job.</p>
              <Button className="mt-4" onClick={onHatch}><Icon.plus /> New bot</Button>
            </div>
          )}
        </div>
      </div>
    );
  }

  const p = bot ? presence[bot.id] : undefined;
  const busy = bot ? !!p && !["idle", "done", "waiting"].includes(p.state) : members.some((b) => { const s = presence[b.id]?.state; return s === "thinking" || s === "working"; });
  // The computer event is independent of presence; a live screen should
  // show the dot even when the bot is idle.
  const live = bot ? !!p?.live || computers[bot.id]?.state === "on" : false;

  return (
    <div className="h-full flex flex-col">
      <header className="flex items-center gap-1 px-2 h-11 shrink-0 hairline-b bg-bg/85 backdrop-blur-xl z-10">
        {onMenu && <IconButton label="Bots" onClick={onMenu}><Icon.menu /></IconButton>}
        {bot ? (
          <button onClick={onSettings} className="flex items-center gap-2 min-w-0 h-10 rounded-lg px-1.5 hover:bg-hover transition-colors duration-[120ms] cursor-pointer">
            <Gbot look={bot.look} seed={bot.id} size={26} mood={moodOf(p, bot)} />
            <span className="flex flex-col min-w-0 text-left">
              <span className="text-[15px] font-semibold leading-5 tracking-[-0.2px] truncate">{bot.name}</span>
              <span className="text-[12px] leading-4 text-ink-3 truncate">{botJob(p, bot.title)}</span>
            </span>
          </button>
        ) : (
          <button onClick={onSettings} className="flex items-center gap-2 min-w-0 h-10 rounded-lg px-1.5 hover:bg-hover transition-colors duration-[120ms] cursor-pointer">
            <Stack bots={members} size={20} presence={presence} />
            <span className="flex flex-col min-w-0 text-left">
              <span className="text-[15px] font-semibold leading-5 tracking-[-0.2px] truncate">{group!.name}</span>
              <span className="text-[12px] leading-4 text-ink-3 truncate">{members.map((b) => b.name).join(", ")}</span>
            </span>
          </button>
        )}
        <div className="flex-1" />
        {bot && (
          <IconButton label={live ? "Its computer is on" : "Computer and routines"} onClick={() => onComputer()}
            className={`relative ${panelOpen ? "bg-selected text-ink!" : ""}`}>
            <span className={live ? "text-live" : ""}><Icon.screen /></span>
            {live && <span className="absolute top-1.5 right-1.5 size-1.5 rounded-full bg-live" />}
          </IconButton>
        )}
        <IconButton label={bot ? "Bot settings" : "Group settings"} onClick={onSettings}><Icon.gear /></IconButton>
      </header>

      <Transcript chatKey={chatKey!} bot={bot} group={group} members={members} messages={messages} presence={presence} error={error} onTakeover={() => onComputer(true)} />
      <QueueStrip chatKey={chatKey!} />
      <Composer
        autoFocusKey={chatKey!}
        placeholder={bot ? `Message ${bot.name}…` : `Message ${group!.name}…`}
        busy={busy}
        mentionable={group ? members : bots.filter((b) => b.id !== bot?.id)}
        onSend={(t) => { stopSpeaking(); sendMessage(chatKey!, t); }}
        onStop={() => (bot ? api.stop(bot.id) : api.stopGroup(group!.id))}
      />
    </div>
  );
}

// A stable empty list: a fresh [] on every snapshot makes
// useSyncExternalStore re-render forever (React error #185).
const NO_QUEUE: QueueItem[] = [];

// QueueStrip is only the messages waiting behind the turn that is running
// now. The running one is already in the transcript, so repeating it here
// just looks like it is stuck.
function QueueStrip({ chatKey }: { chatKey: ChatKey }) {
  const items = useStore((s) => s.queue[chatKey] ?? NO_QUEUE);
  const waiting = items.filter((q) => q.status === "queued" || q.status === "paused");
  if (!waiting.length) return null;
  const fail = (e: unknown) => toast((e as Error).message || "Couldn't change the queue", "error");
  return (
    <div className="max-w-[720px] w-full mx-auto px-4 sm:px-6 pt-2 shrink-0 entry-in">
      <div className="rounded-2xl bg-surface hairline">
        <div className="flex items-center gap-2 px-3 pt-2 pb-1">
          <span className="text-[12px] font-medium text-ink-2">
            {waiting.length === 1 ? "1 message waiting" : `${waiting.length} messages waiting`}
          </span>
          <span className="flex-1" />
          <button className="text-[12px] text-ink-3 hover:text-danger cursor-pointer" onClick={() => clearQueue(chatKey).catch(fail)}>
            Clear
          </button>
        </div>
        <ul className="px-2 pb-2 max-h-32 overflow-y-auto scroll-soft">
          {waiting.map((q) => (
            <li key={q.id} className="flex items-center gap-2 px-1.5 py-1 rounded-lg hover:bg-hover">
              <span className={`size-1.5 rounded-full shrink-0 ${q.status === "paused" ? "bg-ink-3" : "bg-accent"}`} />
              <span className="text-[13px] truncate flex-1" title={q.prompt}>{q.prompt || "(no text)"}</span>
              <button className="text-[12px] text-ink-2 hover:text-ink cursor-pointer" onClick={() => queueAction(chatKey, q.id, q.status === "paused" ? "resume" : "pause").catch(fail)}>
                {q.status === "paused" ? "Resume" : "Pause"}
              </button>
              <button className="text-[12px] text-ink-3 hover:text-danger cursor-pointer" onClick={() => queueAction(chatKey, q.id, "cancel").catch(fail)}>Cancel</button>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

// The bot's job sits under its name; while it works, the job line shows
// what it is doing instead.
function botJob(p: Presence | undefined, fallback: string) {
  if (!p || p.state === "idle" || p.state === "done") return fallback;
  return p.state === "working" && p.detail ? p.detail : presenceText[p.state];
}

export function Stack({ bots, size, presence }: { bots: Bot[]; size: number; presence?: Record<string, Presence> }) {
  const shown = bots.slice(0, 3);
  return (
    <div className="flex items-center shrink-0" style={{ width: size + (shown.length - 1) * size * 0.55 }}>
      {shown.map((b, i) => (
        <div key={b.id} style={{ marginLeft: i ? -size * 0.45 : 0, zIndex: 3 - i }}>
          <Gbot look={b.look} seed={b.id} size={size} mood={presence ? moodOf(presence[b.id], b) : "idle"} />
        </div>
      ))}
    </div>
  );
}

function Transcript({ chatKey, bot, group, members, messages, presence, error, onTakeover }: {
  chatKey: ChatKey; bot?: Bot; group?: Group; members: Bot[]; messages?: Message[]; presence: Record<string, Presence>; error: string; onTakeover: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  const lastTop = useRef(0);
  const primed = useRef(false);
  const lastLen = messages?.length ?? 0;
  const lastId = messages?.[lastLen - 1]?.id;
  const hasMore = useStore((s) => s.hasMore[chatKey]);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [below, setBelow] = useState(0); // new messages below the fold
  const seenLast = useRef(lastId);
  // Only messages that arrive live animate in; history renders at rest.
  const settled = useRef<Set<string>>(new Set());
  const settledFor = useRef<string | null>(null);
  if (messages && settledFor.current !== chatKey) {
    settled.current = new Set(messages.map((m) => m.id));
    settledFor.current = chatKey;
  }
  const bots = useStore((s) => s.bots);
  const byId = useMemo(() => Object.fromEntries((bot ? [bot] : members).map((b) => [b.id, b])), [bot, members]);

  // Who is thinking right now, without a streaming bubble to show for it.
  // In a one-to-one chat, once the bot has spoken the header already says
  // what it is still doing; a thinking row under the reply reads as stuck.
  const typing = useMemo(() => {
    const ids = bot ? [bot.id] : members.map((b) => b.id);
    const rows = messages ?? [];
    const answered: string[] = [];
    for (let i = rows.length - 1; i >= 0 && rows[i].role !== "user"; i--) {
      const id = rows[i].botId;
      if (rows[i].role === "bot" && id) answered.push(id);
    }
    return ids.filter((id) => {
      const s = presence[id]?.state;
      return (s === "thinking" || s === "working")
        && !rows.some((m) => m.streaming && m.botId === id)
        && (!!group || !answered.includes(id));
    });
  }, [bot, group, members, presence, messages]);

  useEffect(() => { stick.current = true; lastTop.current = 0; primed.current = false; setBelow(0); }, [chatKey]);
  // Any content growth (thinking footer, streamed text, cards) follows
  // the bottom while the person is already there. A discrete arrival
  // animates; a token stream is followed per frame so it never lags.
  useEffect(() => {
    const el = ref.current, content = contentRef.current;
    if (!el || !content) return;
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    let raf = 0;
    let settle = 0;
    let lastGrow = 0;
    const toEnd = (smooth: boolean) => {
      if (!stick.current) return;
      const top = el.scrollHeight - el.clientHeight;
      // A long jump (first paint of history) is instant; animating it just
      // scrolls through the whole chat.
      const far = top - el.scrollTop > el.clientHeight;
      if (smooth && !far && !reduced) el.scrollTo({ top, behavior: "smooth" });
      else el.scrollTop = top;
    };
    const keepBottom = () => {
      if (!stick.current) return;
      const now = performance.now();
      const smooth = primed.current && now - lastGrow > 200;
      lastGrow = now;
      primed.current = true;
      clearTimeout(settle);
      settle = window.setTimeout(() => toEnd(true), 140);
      if (raf) return;
      raf = requestAnimationFrame(() => {
        raf = 0;
        toEnd(smooth);
        // One more frame catches text wrapping that lands after layout.
        requestAnimationFrame(() => toEnd(smooth));
      });
    };
    const ro = new ResizeObserver(keepBottom);
    ro.observe(content);
    ro.observe(el);
    return () => { cancelAnimationFrame(raf); clearTimeout(settle); ro.disconnect(); };
  }, [chatKey]);
  // Count messages that arrive while scrolled up.
  useEffect(() => {
    if (lastId && lastId !== seenLast.current && !stick.current) setBelow((n) => n + 1);
    seenLast.current = lastId;
  }, [lastId]);

  const jump = () => {
    const el = ref.current;
    if (!el) return;
    stick.current = true;
    setBelow(0);
    el.scrollTo({ top: el.scrollHeight, behavior: "smooth" });
  };

  // Load older messages near the top, keeping the view where it was.
  async function older() {
    const el = ref.current;
    if (!el || loadingOlder || !hasMore) return;
    setLoadingOlder(true);
    const from = el.scrollHeight - el.scrollTop;
    try {
      await loadOlder(chatKey);
      for (const m of useStoreGet().messages[chatKey] ?? []) settled.current.add(m.id);
      requestAnimationFrame(() => { if (ref.current) ref.current.scrollTop = ref.current.scrollHeight - from; });
    } finally {
      setLoadingOlder(false);
    }
  }

  // Copy buttons on code blocks (rendered from Markdown).
  const onClick = (e: React.MouseEvent) => {
    const btn = (e.target as HTMLElement).closest(".copy-code");
    if (!btn) return;
    const code = btn.parentElement?.querySelector("pre")?.textContent ?? "";
    navigator.clipboard.writeText(code).then(() => toast("Copied code"), () => toast("Couldn't copy", "error"));
  };

  const rows = useMemo(() => {
    const out: { m: Message; first: boolean; last: boolean; stamp?: string; round?: Message[] }[] = [];
    // A bot's asks from one turn show as one round card, drawn where the
    // first one sits; the rest draw nothing.
    const rounds = new Map<string, Message[]>();
    for (const m of messages ?? []) {
      const r = m.data?.round;
      if (m.role === "event" && m.data?.kind === "botmsg" && m.botId === m.data?.from && typeof r === "string") {
        rounds.set(r, [...(rounds.get(r) ?? []), m]);
      }
    }
    (messages ?? []).forEach((m, i, all) => {
      const prev = all[i - 1];
      const same = (a?: Message) => !!a && a.role === m.role && a.botId === m.botId && a.data?.from === m.data?.from && a.role !== "card" && a.role !== "event";
      // A quiet timestamp after a gap, like Grok Bot.
      const gap = !prev || new Date(m.createdAt).getTime() - new Date(prev.createdAt).getTime() > 30 * 60_000;
      const members = typeof m.data?.round === "string" && m.botId === m.data?.from ? rounds.get(m.data.round) : undefined;
      const round = members && members.length > 1 ? (members[0].id === m.id ? members : []) : undefined;
      out.push({ m, first: !same(prev) || gap, last: !same(all[i + 1]), stamp: gap ? stampOf(m.createdAt) : undefined, round });
    });
    return out;
  }, [messages]);

  return (
    <div className="relative flex-1 min-h-0">
    <div ref={ref} onClick={onClick}
      onScroll={(e) => {
        const el = e.currentTarget;
        const top = el.scrollTop;
        const dist = el.scrollHeight - top - el.clientHeight;
        // A user scroll up releases the bottom at once, even mid-stream;
        // coming back within a few pixels of the bottom pins it again.
        if (top < lastTop.current - 1 && dist > 8) stick.current = false;
        else if (dist <= 8) { stick.current = true; if (below) setBelow(0); }
        lastTop.current = top;
        if (top < 200) older();
      }}
      className="h-full overflow-y-auto scroll-soft">
      <div ref={contentRef} className="max-w-[720px] mx-auto px-4 sm:px-6 pt-4 pb-6" role="log" aria-live="polite" aria-relevant="additions" aria-label="Conversation">
        {!messages && (
          <div className="space-y-3 pt-6" aria-hidden>
            <div className="flex justify-end"><Skeleton className="h-9 w-56 rounded-2xl!" /></div>
            <Skeleton className="h-16 w-72 rounded-2xl!" />
            <div className="flex justify-end"><Skeleton className="h-9 w-40 rounded-2xl!" /></div>
            <Skeleton className="h-24 w-80 rounded-2xl!" />
          </div>
        )}
        {hasMore && messages && messages.length > 0 && (
          <div className="text-center text-[12px] text-ink-3 py-2">{loadingOlder ? "Loading earlier messages…" : <button onClick={older} className="hover:text-ink cursor-pointer">Load earlier messages</button>}</div>
        )}
        {messages && messages.length === 0 && (bot ? <Hello bot={bot} /> : <GroupHello group={group!} members={members} />)}
        {error && <p className="text-center text-danger text-[13px]">{error}</p>}
        {rows.map(({ m, first, last, stamp, round }) => {
          // Another bot's message to this one is shown as coming from it.
          const from = typeof m.data?.from === "string" ? bots.find((b) => b.id === m.data!.from) : undefined;
          const author = from ?? (m.botId ? byId[m.botId] : undefined);
          return (
            <div key={m.id}>
              {stamp && <div className="text-center text-[12px] text-ink-3 pt-5 pb-1">{stamp}</div>}
              {round
                ? round.length > 0 && <RoundCard ms={round} enter={settled.current.has(m.id) ? "" : "entry-in"} />
                : m.role === "card"
                ? author && <Card m={m} bot={author} onTakeover={onTakeover} />
                : <Row m={m} author={author} first={first} last={last} inGroup={!!group} chatKey={chatKey} animate={!settled.current.has(m.id)} />}
            </div>
          );
        })}
        <AnimatePresence>
          {typing.map((id) => {
            const b = byId[id];
            const p = presence[id];
            if (!b) return null;
            return (
              <motion.div key={"typing" + id} initial={{ opacity: 0, y: 8, scale: 0.97 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, transition: { duration: 0.1 } }}
                transition={bouncy} className="flex items-center gap-2.5 mt-3 py-1">
                <Gbot look={b.look} seed={b.id} size={32} mood={p?.state === "working" ? "working" : "thinking"} />
                <span className="text-[13px] text-ink-3">
                  {group && <span className="text-ink-2">{b.name} · </span>}
                  {p?.detail || (p?.state === "working" ? "Working" : "Thinking")}
                </span>
              </motion.div>
            );
          })}
        </AnimatePresence>
      </div>
    </div>
      <AnimatePresence>
        {below > 0 && (
          <motion.button key="jump" onClick={jump}
            initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 6, transition: { duration: 0.1 } }} transition={{ duration: 0.18 }}
            className="absolute bottom-3 left-1/2 -translate-x-1/2 h-8 pl-3 pr-3.5 rounded-full bg-bg shadow-pop text-[13px] text-ink-2 hover:text-ink inline-flex items-center gap-1.5 cursor-pointer [&_svg]:size-3.5 [&_svg]:rotate-90">
            <Icon.send /> {below === 1 ? "1 new message" : `${below} new messages`}
          </motion.button>
        )}
      </AnimatePresence>
    </div>
  );
}

function stampOf(iso: string) {
  const d = new Date(iso);
  const today = new Date().toDateString() === d.toDateString();
  return today ? d.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" })
    : d.toLocaleString(undefined, { weekday: "short", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
}

function Hello({ bot }: { bot: Bot }) {
  const [ref, gaze] = useGaze<HTMLDivElement>();
  return (
    <div className="entry-in text-center pt-[14vh] pb-8">
      <div ref={ref} className="inline-block"><Gbot look={bot.look} seed={bot.id} size={72} lookAt={gaze} /></div>
      <h2 className="text-[17px] font-semibold tracking-[-0.3px] mt-3">{bot.name}</h2>
      <p className="text-[13px] text-ink-2 mt-1 max-w-[360px] mx-auto">
        Tell {bot.name} what its job is, in plain words. It remembers, uses its own computer, and asks before anything risky.
      </p>
    </div>
  );
}

function GroupHello({ group, members }: { group: Group; members: Bot[] }) {
  return (
    <div className="entry-in text-center pt-[14vh] pb-8">
      <div className="flex justify-center"><Stack bots={members} size={44} /></div>
      <h2 className="text-[17px] font-semibold tracking-[-0.3px] mt-3">{group.name}</h2>
      <p className="text-[13px] text-ink-2 mt-1 max-w-[380px] mx-auto">
        {members[0]?.name} leads. @mention anyone to bring them in; they hand work to each other.
      </p>
    </div>
  );
}

const Row = memo(function Row({ m, author, first, last, inGroup, chatKey, animate }: { m: Message; author?: Bot; first: boolean; last: boolean; inGroup: boolean; chatKey: ChatKey; animate: boolean }) {
  const enter = animate ? "entry-in" : "";
  if (m.role === "event" && m.data?.kind === "activity") return <Activity m={m} enter={enter} />;
  if (m.role === "event" && m.data?.kind === "botmsg") return <Exchange m={m} enter={enter} />;
  if (m.role === "event") {
    const err = m.data?.kind === "error";
    return (
      <div className={`${enter} flex justify-center items-center gap-1.5 my-3 text-[13px] text-center ${err ? "text-danger" : "text-ink-2"}`}>
        {author && <Gbot look={author.look} seed={author.id} size={16} mood={err ? "blocked" : "idle"} />}
        {m.data?.kind === "routine" && <span className="[&_svg]:size-3.5"><Icon.clock /></span>}
        <span className="prose-bot [&_strong]:text-ink [&_strong]:font-medium" dangerouslySetInnerHTML={{ __html: renderMarkdown(m.content) }} />
      </div>
    );
  }
  const fromBot = m.role === "user" && !!m.data?.from;
  const mine = m.role === "user" && !fromBot;
  const named = inGroup || fromBot;
  const failed = !!m.data?.failed;
  // Bubbles round their joined corners less, like Grok Bot.
  const shape = mine ? `rounded-2xl ${!first ? "rounded-tr-md" : ""} ${!last ? "rounded-br-md" : ""}` : `rounded-2xl ${!first ? "rounded-tl-md" : ""} ${!last ? "rounded-bl-md" : ""}`;
  return (
    <div className={`${enter} group flex items-end gap-1.5 ${first ? "mt-3" : "mt-0.5"} ${mine ? "justify-end" : ""}`}
      style={{ transformOrigin: mine ? "bottom right" : "bottom left" }} title={new Date(m.createdAt).toLocaleString()}>
      {mine && !m.pending && <Actions m={m} chatKey={chatKey} failed={failed} />}
      {!mine && named && <div className="w-6 shrink-0">{last && author && <Gbot look={author.look} seed={author.id} size={24} mood={m.streaming ? "working" : "idle"} />}</div>}
      <div className={`flex flex-col ${mine ? "items-end" : "items-start"} max-w-[min(85%,560px)]`}>
        {named && first && !mine && author && <span className="text-[12px] text-ink-3 mb-1 ml-1">{author.name}</span>}
        <div className={`px-3.5 py-2 text-[15px] leading-[22px] ${shape} ${mine
          ? `bg-[var(--bubble-me)] text-[var(--bubble-me-ink)] ${m.pending ? "opacity-60" : ""} ${failed ? "ring-1 ring-danger" : ""}`
          : "bg-surface text-ink"}`}>
          {mine ? (
            <div className="whitespace-pre-wrap break-words">{m.content}</div>
          ) : (
            <div className={`prose-bot ${m.streaming ? "caret" : ""}`} dangerouslySetInnerHTML={{ __html: renderMarkdown(m.content || " ") }} />
          )}
          {failed && <div className="text-[12px] mt-1 opacity-80">Not sent: {String(m.data?.error ?? "")}</div>}
        </div>
      </div>
      {!mine && !m.streaming && m.content && <Actions m={m} chatKey={chatKey} voice={author?.voice} />}
    </div>
  );
});

type Step = { label: string; detail?: string; ok: boolean };

// What the bot did, as a quiet log above its reply (like Grok Bot's
// "Ran full suite 212 passed"). Long runs fold into one line.
function Activity({ m, enter }: { m: Message; enter: string }) {
  const steps = (m.data?.steps as Step[] | undefined) ?? [];
  const long = steps.length > 4;
  const [open, setOpen] = useState(!long);
  const failed = steps.filter((s) => !s.ok).length;
  return (
    <div className={`${enter} mt-2 max-w-[min(85%,560px)] text-[13.5px] leading-[22px]`}>
      {long && (
        <button onClick={() => setOpen((o) => !o)} aria-expanded={open}
          className="flex items-center gap-1.5 -ml-1 px-1 rounded-md text-ink-3 hover:text-ink-2 cursor-pointer transition-colors duration-[120ms]">
          <span>Worked through {steps.length} steps{failed ? ` · ${failed} failed` : ""}</span>
          <motion.span className="inline-block text-[10px]" animate={{ rotate: open ? 90 : 0 }} transition={bouncy}>›</motion.span>
        </button>
      )}
      <AnimatePresence initial={false}>
        {open && (
          <motion.ul initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }}
            transition={bouncy} className="overflow-hidden">
            {steps.map((s, i) => (
              <li key={i} className="flex items-center gap-2 min-w-0">
                <span className={`shrink-0 size-1.5 rounded-full ${s.ok ? "bg-ink-3" : "bg-danger"}`} />
                <span className="text-ink-2 shrink-0">{s.label}</span>
                {s.detail && <span className={`text-ink-3 truncate ${s.label === "Ran" ? "font-mono text-[12.5px]" : ""}`}>{s.detail}</span>}
              </li>
            ))}
          </motion.ul>
        )}
      </AnimatePresence>
    </div>
  );
}

// The keys of docs/protocol/bot-asks.md this view reads.
type ExchangeData = {
  from: string; to: string; message: string; reply?: string; status: string;
  expect?: "ask" | "tell"; round?: string; joined?: boolean; tookMs?: number;
};

// What the other side shows when there is no reply text.
const noReplyText: Record<string, string> = {
  delivered: "No reply needed", needs_you: "Needs the person before it can reply", failed: "No reply",
  expired: "Didn't answer in time", cancelled: "Stopped before answering",
};
const ended = new Set(["replied", "delivered", "failed", "needs_you", "expired", "cancelled"]);
const statusText = (status: string) => noReplyText[status] ?? (status === "working" ? "Working on it…" : status === "sent" ? "Waiting its turn…" : status);

// The reply as one plain line for the divider's second line.
const oneLine = (s: string) => s.replace(/[*_`#>]+/g, "").replace(/\s+/g, " ").trim();

// One bot messaging another, as in Grok Bot: a centered divider in each
// chat ("Messaged Maya" / "Message from Trader") with a line under it
// that updates in place when the reply arrives. Nothing here waits: the
// sender has already moved on, and the reply simply appears when it lands.
// Click to open the exchange.
function Exchange({ m, enter }: { m: Message; enter: string }) {
  const [open, setOpen] = useState(false);
  const bots = useStore((s) => s.bots);
  const d = m.data as ExchangeData;
  const from = bots.find((b) => b.id === d.from);
  const to = bots.find((b) => b.id === d.to);
  const outbound = m.botId === d.from;
  const peer = outbound ? to : from;
  const label = outbound ? `${d.expect === "tell" ? "Told" : "Asked"} ${to?.name ?? "a bot"}` : `Message from ${from?.name ?? "a bot"}`;
  const line = exchangeLine(d, outbound, to?.name);
  return (
    <>
      <div className={`${enter} my-3 flex flex-col items-center gap-0.5 text-center`}>
        <button onClick={() => setOpen(true)} aria-label={`${label}. Open the exchange`}
          className="flex items-center gap-1.5 rounded-full px-3 py-1 text-[13px] text-ink-2 hover:bg-hover hover:text-ink cursor-pointer transition-colors duration-[120ms]">
          {peer && <Gbot look={peer.look} seed={peer.id} size={16} mood="idle" />}
          <span>{label}</span>
        </button>
        <span className={`max-w-[min(85%,560px)] truncate text-[12.5px] ${d.status === "failed" ? "text-danger" : "text-ink-3"}`}>{line}</span>
      </div>
      <ExchangeView open={open} onClose={() => setOpen(false)} from={from} to={to} d={d} />
    </>
  );
}

// The divider’s second line. Before a reply there is nothing to wait on:
// the sender just shows that it was sent.
function exchangeLine(d: ExchangeData, outbound: boolean, toName?: string): string {
  if (d.reply) return `${outbound ? `${toName ?? "Bot"}: ` : "Replied: "}${oneLine(d.reply)}`;
  if (d.status === "failed") return noReplyText.failed;
  if (d.status === "delivered") return noReplyText.delivered;
  if (noReplyText[d.status]) return noReplyText[d.status];
  if (d.status === "working") return outbound ? `${toName ?? "Bot"} is on it…` : "Working on it…";
  return outbound ? "Sent" : "";
}

// "Pip", "Pip and Taro", "Pip, Taro and 2 more".
function names(list: (Bot | undefined)[]): string {
  const n = list.map((b) => b?.name ?? "a bot");
  if (n.length <= 1) return n[0] ?? "";
  if (n.length === 2) return `${n[0]} and ${n[1]}`;
  if (n.length === 3) return `${n[0]}, ${n[1]} and ${n[2]}`;
  return `${n[0]}, ${n[1]} and ${n.length - 2} more`;
}

function took(ms: number): string {
  const s = Math.round(ms / 1000);
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`;
}

// A round: the asks one bot sent in one turn, as one line that counts the
// answers as they land. Open it to read every question and answer.
function RoundCard({ ms, enter }: { ms: Message[]; enter: string }) {
  const [open, setOpen] = useState(false);
  const bots = useStore((s) => s.bots);
  const ds = ms.map((m) => m.data as ExchangeData);
  const from = bots.find((b) => b.id === ds[0].from);
  const tos = ds.map((d) => bots.find((b) => b.id === d.to));
  const answered = ds.filter((d) => ended.has(d.status)).length;
  const tookMs = ds.find((d) => typeof d.tookMs === "number")?.tookMs;
  const line = ds.some((d) => d.joined) && tookMs != null ? `All answered · ${took(tookMs)}`
    : answered === ds.length ? "All answered" : `${answered} of ${ds.length} answered`;
  const label = `Asked ${names(tos)}`;
  return (
    <>
      <div className={`${enter} my-3 flex flex-col items-center gap-0.5 text-center`}>
        <button onClick={() => setOpen(true)} aria-label={`${label}. ${line}. Open the answers`}
          className="flex items-center gap-1.5 rounded-full px-3 py-1 text-[13px] text-ink-2 hover:bg-hover hover:text-ink cursor-pointer transition-colors duration-[120ms]">
          <span className="flex items-center gap-0.5">
            {tos.filter(Boolean).slice(0, 3).map((b) => <Gbot key={b!.id} look={b!.look} seed={b!.id} size={16} mood="idle" />)}
          </span>
          <span>{label}</span>
        </button>
        <span className="text-[12.5px] text-ink-3">{line}</span>
      </div>
      <Modal open={open} onClose={() => setOpen(false)} width={560}>
        <div className="px-5 pb-5 pt-6 space-y-5">
          <div className="mx-auto flex w-fit items-center gap-2 rounded-full bg-surface px-3.5 py-1.5 text-[14px]">
            {from && <Gbot look={from.look} seed={from.id} size={18} mood="idle" />}
            <span className="font-medium">{from?.name ?? "A bot"} asked {ds.length} bots</span>
          </div>
          {ds.map((d, i) => (
            <div key={ms[i].id} className={`space-y-4 ${i > 0 ? "border-t border-line pt-5" : ""}`}>
              <ExchangeTurn bot={from}><div className="whitespace-pre-wrap break-words">{d.message}</div></ExchangeTurn>
              <ExchangeTurn bot={tos[i]}>
                {d.reply
                  ? <div className="prose-bot" dangerouslySetInnerHTML={{ __html: renderMarkdown(d.reply) }} />
                  : <div className="text-ink-3">{statusText(d.status)}</div>}
              </ExchangeTurn>
            </div>
          ))}
          <div className="text-center text-[12px] text-ink-3">{line}</div>
          <div className="flex justify-center pt-1">
            <button onClick={() => setOpen(false)} className="rounded-full bg-surface hover:bg-hover px-4 py-2 text-[14px] cursor-pointer transition-colors duration-[120ms]">Close</button>
          </div>
        </div>
      </Modal>
    </>
  );
}

// The exchange itself: both bots, the message, and the reply once it
// arrives. View only.
function ExchangeView({ open, onClose, from, to, d }: { open: boolean; onClose: () => void; from?: Bot; to?: Bot; d: ExchangeData }) {
  const pending = d.status === "sent"; // no reply yet; the view just leaves that turn out
  return (
    <Modal open={open} onClose={onClose} width={560}>
      <div className="px-5 pb-5 pt-6 space-y-5">
        <div className="mx-auto flex w-fit items-center gap-2 rounded-full bg-surface px-3.5 py-1.5 text-[14px]">
          {from && <Gbot look={from.look} seed={from.id} size={18} mood="idle" />}
          <span className="font-medium">{from?.name ?? "A bot"}</span>
          <span className="text-ink-3">⇄</span>
          {to && <Gbot look={to.look} seed={to.id} size={18} mood="idle" />}
          <span className="font-medium">{to?.name ?? "A bot"}</span>
        </div>
        <ExchangeTurn bot={from}><div className="whitespace-pre-wrap break-words">{d.message}</div></ExchangeTurn>
        {(d.reply || !pending) && (
          <ExchangeTurn bot={to}>
            {d.reply
              ? <div className="prose-bot" dangerouslySetInnerHTML={{ __html: renderMarkdown(d.reply) }} />
              : <div className="text-ink-3">{statusText(d.status)}</div>}
          </ExchangeTurn>
        )}
        <div className="flex justify-center pt-1">
          <button onClick={onClose} className="rounded-full bg-surface hover:bg-hover px-4 py-2 text-[14px] cursor-pointer transition-colors duration-[120ms]">Close Chat</button>
        </div>
      </div>
    </Modal>
  );
}

function ExchangeTurn({ bot, children }: { bot?: Bot; children: React.ReactNode }) {
  return (
    <div className="flex items-start gap-2.5">
      <div className="w-7 shrink-0 pt-0.5">{bot && <Gbot look={bot.look} seed={bot.id} size={28} mood="idle" />}</div>
      <div className="min-w-0 flex-1">
        <div className="text-[12px] text-ink-3 mb-1">{bot?.name ?? "Removed bot"}</div>
        <div className="rounded-2xl bg-surface px-3.5 py-2 text-[15px] leading-[22px]">{children}</div>
      </div>
    </div>
  );
}

// Hover actions on a message: copy, read aloud, or retry a failed send.
function Actions({ m, chatKey, voice, failed }: { m: Message; chatKey: ChatKey; voice?: string; failed?: boolean }) {
  const btn = "size-7 grid place-items-center rounded-lg text-ink-3 hover:text-ink hover:bg-hover cursor-pointer [&_svg]:size-[15px]";
  return (
    <div className={`flex items-center mb-0.5 transition-opacity duration-[120ms] ${failed ? "" : "opacity-0 group-hover:opacity-100 focus-within:opacity-100"}`}>
      {failed ? (
        <button onClick={() => retry(chatKey, m)} className="h-7 px-2 rounded-lg text-[12px] text-danger hover:bg-hover cursor-pointer">Retry</button>
      ) : (
        <>
          <button className={btn} aria-label="Copy" title="Copy"
            onClick={() => navigator.clipboard.writeText(m.content).then(() => toast("Copied"), () => toast("Couldn't copy", "error"))}>
            <Icon.copy />
          </button>
          {m.role === "bot" && (
            <button className={btn} aria-label="Read aloud" title="Read aloud" onClick={() => speak(m.content, voice).catch(() => toast("Couldn't read aloud", "error"))}>
              <Icon.speaker />
            </button>
          )}
        </>
      )}
    </div>
  );
}

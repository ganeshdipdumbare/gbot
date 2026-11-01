import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Gbot } from "@gbot/characters";
import type { Bot, Group, Message, Presence } from "../lib/api";
import { api } from "../lib/api";
import { get as useStoreGet, groupKey, isGroupKey, loadMessages, loadOlder, onLive, retry, sendMessage, useStore, type ChatKey } from "../lib/store";
import { toast } from "../lib/toast";
import { Skeleton } from "./Feedback";
import { moodOf, presenceText, useGaze } from "../lib/hooks";
import { renderMarkdown } from "../lib/markdown";
import { speak, stopSpeaking } from "../lib/voice";
import { Card } from "./Card";
import { Composer } from "./Composer";
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
  const loaded = useStore((s) => s.bots.length > 0 || s.me === null);
  const [error, setError] = useState("");

  useEffect(() => {
    if (chatKey) loadMessages(chatKey).catch((e) => setError(e.message));
  }, [chatKey]);

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
  const live = bot ? !!p?.live : false;

  return (
    <div className="h-full flex flex-col">
      <header className="flex items-center gap-1 px-2 h-11 shrink-0 hairline-b bg-bg/85 backdrop-blur-xl z-10">
        {onMenu && <IconButton label="Bots" onClick={onMenu}><Icon.menu /></IconButton>}
        {bot ? (
          <button onClick={onSettings} className="flex items-center gap-2 min-w-0 h-8 rounded-lg px-1.5 hover:bg-hover transition-colors duration-[120ms] cursor-pointer">
            <Gbot look={bot.look} seed={bot.id} size={24} mood={moodOf(p, bot)} />
            <span className="text-[15px] font-semibold tracking-[-0.2px] truncate">{bot.name}</span>
            <Status p={p} fallback={bot.title} />
          </button>
        ) : (
          <button onClick={onSettings} className="flex items-center gap-2 min-w-0 h-8 rounded-lg px-1.5 hover:bg-hover transition-colors duration-[120ms] cursor-pointer">
            <Stack bots={members} size={20} presence={presence} />
            <span className="text-[15px] font-semibold tracking-[-0.2px] truncate">{group!.name}</span>
            <span className="text-[13px] text-ink-3 truncate">{members.map((b) => b.name).join(", ")}</span>
          </button>
        )}
        <div className="flex-1" />
        {bot && (
          <IconButton label={live ? "Its computer is active" : "Computer and routines"} onClick={() => onComputer()}
            className={`relative ${panelOpen ? "bg-selected text-ink!" : ""}`}>
            <span className={live ? "text-live" : ""}><Icon.screen /></span>
            {live && <span className="absolute top-1.5 right-1.5 size-1.5 rounded-full bg-live" />}
          </IconButton>
        )}
        <IconButton label={bot ? "Bot settings" : "Group settings"} onClick={onSettings}><Icon.gear /></IconButton>
      </header>

      <Transcript chatKey={chatKey!} bot={bot} group={group} members={members} messages={messages} presence={presence} error={error} onTakeover={() => onComputer(true)} />
      <Composer
        autoFocusKey={chatKey!}
        placeholder={bot ? `Message ${bot.name}…` : `Message ${group!.name}…`}
        busy={busy}
        members={group ? members : undefined}
        onSend={(t) => { stopSpeaking(); sendMessage(chatKey!, t); }}
        onStop={() => (bot ? api.stop(bot.id) : api.stopGroup(group!.id))}
      />
    </div>
  );
}

function Status({ p, fallback }: { p?: Presence; fallback: string }) {
  const busy = p && p.state !== "idle";
  return (
    <AnimatePresence mode="wait" initial={false}>
      <motion.span key={(p?.state ?? "idle") + (p?.detail ?? "")}
        initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.12 }}
        className={`text-[13px] truncate ${busy ? "text-ink-2" : "text-ink-3"}`}>
        {busy ? (p!.detail && p!.state === "working" ? p!.detail : presenceText[p!.state]) : fallback}
      </motion.span>
    </AnimatePresence>
  );
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
  const stick = useRef(true);
  const lastLen = messages?.length ?? 0;
  const lastId = messages?.[lastLen - 1]?.id;
  const lastContent = messages?.[lastLen - 1]?.content.length ?? 0;
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
  const byId = useMemo(() => Object.fromEntries((bot ? [bot] : members).map((b) => [b.id, b])), [bot, members]);

  // Who is thinking right now, without a streaming bubble to show for it.
  const typing = useMemo(() => {
    const ids = bot ? [bot.id] : members.map((b) => b.id);
    return ids.filter((id) => {
      const s = presence[id]?.state;
      return (s === "thinking" || s === "working") && !messages?.some((m) => m.streaming && m.botId === id);
    });
  }, [bot, members, presence, messages]);

  useLayoutEffect(() => {
    const el = ref.current;
    if (el && stick.current) el.scrollTop = el.scrollHeight;
  }, [lastLen, lastContent, typing.length]);
  useEffect(() => { stick.current = true; setBelow(0); }, [chatKey]);
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
    const out: { m: Message; first: boolean; last: boolean; stamp?: string }[] = [];
    (messages ?? []).forEach((m, i, all) => {
      const prev = all[i - 1];
      const same = (a?: Message) => !!a && a.role === m.role && a.botId === m.botId && a.role !== "card" && a.role !== "event";
      // A quiet timestamp after a gap, like Grok Bot.
      const gap = !prev || new Date(m.createdAt).getTime() - new Date(prev.createdAt).getTime() > 30 * 60_000;
      out.push({ m, first: !same(prev) || gap, last: !same(all[i + 1]), stamp: gap ? stampOf(m.createdAt) : undefined });
    });
    return out;
  }, [messages]);

  return (
    <div className="relative flex-1 min-h-0">
    <div ref={ref} onClick={onClick}
      onScroll={(e) => {
        const el = e.currentTarget;
        stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
        if (stick.current && below) setBelow(0);
        if (el.scrollTop < 200) older();
      }}
      className="h-full overflow-y-auto scroll-soft">
      <div className="max-w-[720px] mx-auto px-4 sm:px-6 pt-4 pb-6" role="log" aria-live="polite" aria-relevant="additions" aria-label="Conversation">
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
        {rows.map(({ m, first, last, stamp }) => {
          const author = m.botId ? byId[m.botId] : undefined;
          return (
            <div key={m.id}>
              {stamp && <div className="text-center text-[12px] text-ink-3 pt-5 pb-1">{stamp}</div>}
              {m.role === "card"
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
  const mine = m.role === "user";
  const failed = !!m.data?.failed;
  // Bubbles round their joined corners less, like Grok Bot.
  const shape = mine ? `rounded-2xl ${!first ? "rounded-tr-md" : ""} ${!last ? "rounded-br-md" : ""}` : `rounded-2xl ${!first ? "rounded-tl-md" : ""} ${!last ? "rounded-bl-md" : ""}`;
  return (
    <div className={`${enter} group flex items-end gap-1.5 ${first ? "mt-3" : "mt-0.5"} ${mine ? "justify-end" : ""}`}
      style={{ transformOrigin: mine ? "bottom right" : "bottom left" }} title={new Date(m.createdAt).toLocaleString()}>
      {mine && !m.pending && <Actions m={m} chatKey={chatKey} failed={failed} />}
      {!mine && inGroup && <div className="w-6 shrink-0">{last && author && <Gbot look={author.look} seed={author.id} size={24} mood={m.streaming ? "working" : "idle"} />}</div>}
      <div className={`flex flex-col ${mine ? "items-end" : "items-start"} max-w-[min(85%,560px)]`}>
        {inGroup && first && !mine && author && <span className="text-[12px] text-ink-3 mb-1 ml-1">{author.name}</span>}
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

// What the bot did, as a quiet checklist above its reply (like Grok Bot
// and Rakazo): "✓ Ran → python3 fib.py · exit 0".
function Activity({ m, enter }: { m: Message; enter: string }) {
  const steps = (m.data?.steps as Step[] | undefined) ?? [];
  return (
    <div className={`${enter} mt-3 max-w-[min(85%,560px)] rounded-2xl bg-surface px-3.5 py-2.5 text-[14px] leading-[22px]`}>
      {steps.map((s, i) => (
        <div key={i} className="flex items-baseline gap-2 min-w-0">
          <span className={`shrink-0 [&_svg]:size-3.5 translate-y-[2px] ${s.ok ? "text-success" : "text-danger"}`}>{s.ok ? <Icon.check /> : <Icon.close />}</span>
          <span className="font-medium shrink-0">{s.label}</span>
          {s.detail && <><span className="text-ink-3 shrink-0">→</span><span className="text-ink-2 truncate font-mono text-[12.5px]">{s.detail}</span></>}
        </div>
      ))}
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

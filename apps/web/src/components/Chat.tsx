import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Gbot } from "@gbot/characters";
import type { Bot, Group, Message, Presence } from "../lib/api";
import { api } from "../lib/api";
import { groupKey, isGroupKey, loadMessages, onLive, sendMessage, useStore, type ChatKey } from "../lib/store";
import { moodOf, presenceText, useGaze } from "../lib/hooks";
import { renderMarkdown } from "../lib/markdown";
import { speak, stopSpeaking } from "../lib/voice";
import { Card } from "./Card";
import { Composer } from "./Composer";
import { bouncy, Button, Icon, IconButton, spring } from "./ui";

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
              <Gbot seed="empty" look={{ shape: "arch", hue: "sun", eyes: "wide" }} mood="sleepy" size={120} className="mx-auto" />
              <h2 className="font-display font-black text-2xl mt-4">It's quiet in here</h2>
              <p className="text-ink-2 mt-1">Create a bot to get started.</p>
              <Button className="mt-5" onClick={onHatch}><Icon.plus /> New bot</Button>
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
      <header className="flex items-center gap-2 px-3 sm:px-5 h-16 shrink-0 border-b border-line/70 bg-bg/80 backdrop-blur-md z-10">
        {onMenu && <IconButton label="Bots" onClick={onMenu}><Icon.menu /></IconButton>}
        {bot ? (
          <button onClick={onSettings} className="flex items-center gap-3 min-w-0 rounded-2xl pr-3 hover:bg-surface-2 transition-colors cursor-pointer">
            <Gbot look={bot.look} seed={bot.id} size={40} mood={moodOf(p, bot)} />
            <div className="min-w-0 text-left">
              <div className="font-display font-black text-[17px] leading-tight truncate">{bot.name}</div>
              <Status p={p} fallback={bot.title || "Your teammate"} />
            </div>
          </button>
        ) : (
          <button onClick={onSettings} className="flex items-center gap-3 min-w-0 rounded-2xl pr-3 hover:bg-surface-2 transition-colors cursor-pointer">
            <Stack bots={members} size={30} presence={presence} />
            <div className="min-w-0 text-left">
              <div className="font-display font-black text-[17px] leading-tight truncate">{group!.name}</div>
              <div className="text-[12.5px] text-ink-2 truncate">{members.map((b) => b.name).join(", ")}</div>
            </div>
          </button>
        )}
        <div className="flex-1" />
        {bot && (
          <IconButton label={live ? "Its computer is active" : "Computer and routines"} onClick={() => onComputer()}
            className={`relative ${panelOpen ? "bg-surface-2 text-ink!" : ""}`}>
            <span className={live ? "text-live" : ""}><Icon.screen /></span>
            {live && <span className="absolute top-1.5 right-1.5 size-2 rounded-full bg-live ring-2 ring-bg" />}
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
      <motion.div key={(p?.state ?? "idle") + (p?.detail ?? "")}
        initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.15 }}
        className={`text-[12.5px] truncate ${busy ? "text-live font-semibold" : "text-ink-2"}`}>
        {busy ? (p!.detail && p!.state === "working" ? p!.detail : presenceText[p!.state]) : fallback}
      </motion.div>
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
  const lastContent = messages?.[lastLen - 1]?.content.length ?? 0;
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
  useEffect(() => { stick.current = true; }, [chatKey]);

  const rows = useMemo(() => {
    const out: { m: Message; first: boolean; last: boolean }[] = [];
    (messages ?? []).forEach((m, i, all) => {
      const same = (a?: Message) => !!a && a.role === m.role && a.botId === m.botId && a.role !== "card" && a.role !== "event";
      out.push({ m, first: !same(all[i - 1]), last: !same(all[i + 1]) });
    });
    return out;
  }, [messages]);

  return (
    <div ref={ref} onScroll={(e) => { const el = e.currentTarget; stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80; }}
      className="flex-1 overflow-y-auto scroll-soft">
      <div className="max-w-[760px] mx-auto px-3 sm:px-6 py-6">
        {messages && messages.length === 0 && (bot ? <Hello bot={bot} /> : <GroupHello group={group!} members={members} />)}
        {error && <p className="text-center text-danger text-sm">{error}</p>}
        {rows.map(({ m, first, last }) => {
          const author = m.botId ? byId[m.botId] : undefined;
          if (m.role === "card") return author ? <Card key={m.id} m={m} bot={author} onTakeover={onTakeover} /> : null;
          return <Row key={m.id} m={m} author={author} first={first} last={last} showName={!!group} />;
        })}
        <AnimatePresence>
          {typing.map((id) => {
            const b = byId[id];
            const p = presence[id];
            if (!b) return null;
            return (
              <motion.div key={"typing" + id} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} transition={bouncy}
                className="flex items-end gap-2.5 mt-3">
                <Gbot look={b.look} seed={b.id} size={40} mood={p?.state === "working" ? "working" : "thinking"} />
                <span className="text-[13px] text-ink-2 pb-2">
                  {group && <span className="font-semibold text-ink">{b.name} · </span>}
                  {p?.detail || (p?.state === "working" ? "Working…" : "Thinking…")}
                </span>
              </motion.div>
            );
          })}
        </AnimatePresence>
        <div className="h-2" />
      </div>
    </div>
  );
}

function Hello({ bot }: { bot: Bot }) {
  const [ref, gaze] = useGaze<HTMLDivElement>();
  return (
    <motion.div initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={bouncy} className="text-center pt-[8vh] pb-8">
      <div ref={ref} className="inline-block"><Gbot look={bot.look} seed={bot.id} size={140} lookAt={gaze} /></div>
      <h2 className="font-display font-black text-[28px] mt-4">Hi, I'm {bot.name}</h2>
      <p className="text-ink-2 mt-1 max-w-[400px] mx-auto">
        Tell me what my job is, in plain words. I'll remember it, use my computer when it helps, and ask before anything risky.
      </p>
    </motion.div>
  );
}

function GroupHello({ group, members }: { group: Group; members: Bot[] }) {
  return (
    <motion.div initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={bouncy} className="text-center pt-[8vh] pb-8">
      <div className="flex justify-center gap-3">
        {members.map((b, i) => (
          <motion.div key={b.id} initial={{ y: 20, opacity: 0 }} animate={{ y: 0, opacity: 1 }} transition={{ ...bouncy, delay: i * 0.06 }}>
            <Gbot look={b.look} seed={b.id} size={72} />
          </motion.div>
        ))}
      </div>
      <h2 className="font-display font-black text-[28px] mt-4">{group.name}</h2>
      <p className="text-ink-2 mt-1 max-w-[420px] mx-auto">
        {members[0]?.name} takes the lead. @mention anyone to bring them in, and they'll hand work to each other.
      </p>
    </motion.div>
  );
}

const Row = memo(function Row({ m, author, first, last, showName }: { m: Message; author?: Bot; first: boolean; last: boolean; showName: boolean }) {
  if (m.role === "event") {
    const err = m.data?.kind === "error";
    return (
      <motion.div initial={{ opacity: 0, scale: 0.94 }} animate={{ opacity: 1, scale: 1 }} transition={bouncy} className="flex justify-center my-3">
        <div className={`flex items-center gap-2 text-[13px] px-3.5 py-1.5 rounded-full max-w-[90%] ${err ? "bg-danger/10 text-danger" : "bg-surface-2/70 text-ink-2"}`}>
          {author && <Gbot look={author.look} seed={author.id} size={18} mood={err ? "blocked" : "idle"} />}
          {m.data?.kind === "routine" && <Icon.clock />}
          <span className="prose-bot" dangerouslySetInnerHTML={{ __html: renderMarkdown(m.content) }} />
        </div>
      </motion.div>
    );
  }
  const mine = m.role === "user";
  const failed = !!m.data?.failed;
  return (
    <motion.div
      initial={{ opacity: 0, y: 10, scale: 0.97 }} animate={{ opacity: 1, y: 0, scale: 1 }} transition={spring}
      className={`group flex items-end gap-2.5 ${first ? "mt-4" : "mt-1"} ${mine ? "justify-end" : ""}`}
      style={{ transformOrigin: mine ? "bottom right" : "bottom left" }}
    >
      {!mine && <div className="w-[34px] shrink-0">{last && author && <Gbot look={author.look} seed={author.id} size={34} mood={m.streaming ? "working" : "idle"} />}</div>}
      <div className={`flex flex-col ${mine ? "items-end" : "items-start"} max-w-[min(82%,620px)]`}>
        {showName && first && !mine && author && <span className="text-[12px] font-semibold text-ink-2 mb-1 ml-1">{author.name}</span>}
        <div className={`px-4 py-2.5 text-[15px] ${mine
          ? `bg-[var(--bubble-me)] text-[var(--bubble-me-ink)] rounded-[22px] ${last ? "rounded-br-md" : ""} ${m.pending ? "opacity-70" : ""} ${failed ? "ring-2 ring-danger" : ""}`
          : `bg-surface border border-line shadow-soft rounded-[22px] ${last ? "rounded-bl-md" : ""}`}`}>
          {mine ? (
            <div className="whitespace-pre-wrap break-words leading-relaxed">{m.content}</div>
          ) : (
            <div className={`prose-bot ${m.streaming ? "caret" : ""}`} dangerouslySetInnerHTML={{ __html: renderMarkdown(m.content || " ") }} />
          )}
          {failed && <div className="text-[12px] mt-1 opacity-90">Not sent: {String(m.data?.error ?? "")}</div>}
        </div>
      </div>
      {!mine && !m.streaming && m.content && (
        <button onClick={() => speak(m.content, author?.voice).catch(() => {})} aria-label="Read aloud" title="Read aloud"
          className="opacity-0 group-hover:opacity-100 focus:opacity-100 transition-opacity size-8 grid place-items-center rounded-full text-ink-2 hover:text-ink hover:bg-surface-2 cursor-pointer mb-0.5">
          <Icon.speaker />
        </button>
      )}
    </motion.div>
  );
});

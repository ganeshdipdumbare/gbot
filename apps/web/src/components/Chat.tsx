import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Gbot } from "@gbot/characters";
import type { Bot, Message } from "../lib/api";
import { api } from "../lib/api";
import { loadMessages, sendMessage, useStore } from "../lib/store";
import { moodOf, presenceText, useGaze } from "../lib/hooks";
import { renderMarkdown } from "../lib/markdown";
import { bouncy, Button, Icon, IconButton, spring } from "./ui";

export function Chat({ botId, onMenu, onSettings, onHatch }: { botId: string | null; onMenu?: () => void; onSettings: () => void; onHatch: () => void }) {
  const bot = useStore((s) => s.bots.find((b) => b.id === botId));
  const messages = useStore((s) => (botId ? s.messages[botId] : undefined));
  const presence = useStore((s) => (botId ? s.presence[botId] : undefined));
  const loaded = useStore((s) => s.bots.length > 0 || s.me === null);
  const [error, setError] = useState("");

  useEffect(() => {
    if (botId) loadMessages(botId).catch((e) => setError(e.message));
  }, [botId]);

  if (!bot) {
    return (
      <div className="h-full flex flex-col">
        {onMenu && <div className="p-3"><IconButton label="Bots" onClick={onMenu}><Icon.menu /></IconButton></div>}
        <div className="flex-1 grid place-items-center p-6 text-center">
          {loaded && (
            <div>
              <Gbot seed="empty" look={{ shape: "bun", hue: "butter", accessory: "bear" }} mood="sleepy" size={120} className="mx-auto" />
              <h2 className="font-display font-black text-2xl mt-4">It's quiet in here</h2>
              <p className="text-ink-2 mt-1">Hatch a bot to get started.</p>
              <Button className="mt-5" onClick={onHatch}><Icon.plus /> Hatch a bot</Button>
            </div>
          )}
        </div>
      </div>
    );
  }

  const busy = !!presence && presence.state !== "idle" && presence.state !== "done";
  const mood = moodOf(presence, bot);

  return (
    <div className="h-full flex flex-col">
      <header className="flex items-center gap-2 px-3 sm:px-5 h-16 shrink-0 border-b border-line/70 bg-bg/80 backdrop-blur-md z-10">
        {onMenu && <IconButton label="Bots" onClick={onMenu}><Icon.menu /></IconButton>}
        <button onClick={onSettings} className="flex items-center gap-3 min-w-0 rounded-2xl pr-3 hover:bg-surface-2 transition-colors cursor-pointer">
          <Gbot look={bot.look} seed={bot.id} size={40} mood={mood} props={false} />
          <div className="min-w-0 text-left">
            <div className="font-display font-black text-[17px] leading-tight truncate">{bot.name}</div>
            <AnimatePresence mode="wait" initial={false}>
              <motion.div
                key={presence?.state ?? "idle"}
                initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.15 }}
                className={`text-[12.5px] truncate ${busy ? "text-live font-semibold" : "text-ink-2"}`}
              >
                {busy ? (presence.detail && presence.state === "working" ? presence.detail : presenceText[presence.state]) : bot.title || "Your teammate"}
              </motion.div>
            </AnimatePresence>
          </div>
        </button>
        <div className="flex-1" />
        <IconButton label="Bot settings" onClick={onSettings}><Icon.gear /></IconButton>
      </header>

      <Transcript bot={bot} messages={messages} presence={presence?.state} error={error} />
      <Composer bot={bot} busy={busy} />
    </div>
  );
}

function Transcript({ bot, messages, presence, error }: { bot: Bot; messages?: Message[]; presence?: string; error: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  const streaming = messages?.some((m) => m.streaming);
  const lastLen = messages?.length ?? 0;
  const lastContent = messages?.[lastLen - 1]?.content.length ?? 0;

  useLayoutEffect(() => {
    const el = ref.current;
    if (el && stick.current) el.scrollTop = el.scrollHeight;
  }, [lastLen, lastContent, presence]);

  const groups = useMemo(() => {
    const out: { m: Message; first: boolean; last: boolean }[] = [];
    (messages ?? []).forEach((m, i, all) => {
      const prev = all[i - 1];
      const next = all[i + 1];
      out.push({ m, first: !prev || prev.role !== m.role, last: !next || next.role !== m.role });
    });
    return out;
  }, [messages]);

  const showTyping = (presence === "thinking" || presence === "working") && !streaming;

  return (
    <div
      ref={ref}
      onScroll={(e) => {
        const el = e.currentTarget;
        stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
      }}
      className="flex-1 overflow-y-auto scroll-soft"
    >
      <div className="max-w-[760px] mx-auto px-3 sm:px-6 py-6">
        {messages && messages.length === 0 && <Hello bot={bot} />}
        {error && <p className="text-center text-danger text-sm">{error}</p>}
        {groups.map(({ m, first, last }) => <Row key={m.id} m={m} bot={bot} first={first} last={last} />)}
        <AnimatePresence>
          {showTyping && (
            <motion.div
              key="typing"
              initial={{ opacity: 0, y: 10, scale: 0.9 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, scale: 0.9 }} transition={bouncy}
              className="flex items-end gap-2 mt-3"
            >
              <Gbot look={bot.look} seed={bot.id} size={34} mood={presence === "working" ? "working" : "thinking"} props={false} />
              <div className="bg-surface border border-line rounded-[20px] rounded-bl-md px-4 py-3 shadow-soft flex gap-1.5" aria-label={`${bot.name} is typing`}>
                {[0, 1, 2].map((i) => (
                  <motion.span key={i} className="size-2 rounded-full bg-ink-2/60"
                    animate={{ y: [0, -5, 0], opacity: [0.5, 1, 0.5] }} transition={{ duration: 0.9, repeat: Infinity, delay: i * 0.14, ease: "easeInOut" }} />
                ))}
              </div>
            </motion.div>
          )}
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
      <div ref={ref} className="inline-block">
        <Gbot look={bot.look} seed={bot.id} size={150} mood="idle" lookAt={gaze} />
      </div>
      <h2 className="font-display font-black text-[28px] mt-3">Hi, I'm {bot.name}!</h2>
      <p className="text-ink-2 mt-1 max-w-[380px] mx-auto">
        Tell me what my job is, in plain words. I'll remember it and get better at it over time.
      </p>
    </motion.div>
  );
}

const Row = memo(function Row({ m, bot, first, last }: { m: Message; bot: Bot; first: boolean; last: boolean }) {
  if (m.role === "event") {
    const err = m.data?.kind === "error";
    return (
      <motion.div initial={{ opacity: 0, scale: 0.9 }} animate={{ opacity: 1, scale: 1 }} transition={bouncy} className="flex justify-center my-3">
        <div className={`flex items-center gap-2 text-[13px] px-3.5 py-2 rounded-full max-w-[90%] ${err ? "bg-danger/10 text-danger" : "bg-surface-2 text-ink-2"}`}>
          {err && <Gbot look={bot.look} seed={bot.id} size={22} mood="blocked" props={false} />}
          <span className="prose-bot" dangerouslySetInnerHTML={{ __html: renderMarkdown(m.content) }} />
        </div>
      </motion.div>
    );
  }
  const mine = m.role === "user";
  const failed = !!m.data?.failed;
  return (
    <motion.div
      initial={{ opacity: 0, y: 12, scale: 0.96 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={spring}
      className={`flex items-end gap-2 ${first ? "mt-4" : "mt-1"} ${mine ? "justify-end" : ""}`}
      style={{ transformOrigin: mine ? "bottom right" : "bottom left" }}
    >
      {!mine && <div className="w-[34px] shrink-0">{last && <Gbot look={bot.look} seed={bot.id} size={34} props={false} mood={m.streaming ? "working" : "idle"} />}</div>}
      <div
        className={`max-w-[min(82%,620px)] px-4 py-2.5 text-[15px] ${
          mine
            ? `bg-[var(--bubble-me)] text-[var(--bubble-me-ink)] rounded-[22px] ${last ? "rounded-br-md" : ""} ${m.pending ? "opacity-70" : ""} ${failed ? "ring-2 ring-danger" : ""}`
            : `bg-surface border border-line shadow-soft rounded-[22px] ${last ? "rounded-bl-md" : ""}`
        }`}
      >
        {mine ? (
          <div className="whitespace-pre-wrap break-words leading-relaxed">{m.content}</div>
        ) : (
          <div className={`prose-bot ${m.streaming ? "caret" : ""}`} dangerouslySetInnerHTML={{ __html: renderMarkdown(m.content || " ") }} />
        )}
        {failed && <div className="text-[12px] mt-1 opacity-90">Not sent: {String(m.data?.error ?? "")}</div>}
      </div>
    </motion.div>
  );
});

function Composer({ bot, busy }: { bot: Bot; busy: boolean }) {
  const [text, setText] = useState("");
  const [hop, setHop] = useState(0);
  const ta = useRef<HTMLTextAreaElement>(null);

  useEffect(() => { ta.current?.focus(); }, [bot.id]);
  useLayoutEffect(() => {
    const el = ta.current;
    if (!el) return;
    el.style.height = "0px";
    el.style.height = Math.min(el.scrollHeight, 220) + "px";
  }, [text]);

  const send = () => {
    const t = text.trim();
    if (!t) return;
    setText("");
    setHop((h) => h + 1);
    sendMessage(bot.id, t);
  };
  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      send();
    }
  };

  return (
    <div className="shrink-0 px-3 sm:px-6 pb-[max(env(safe-area-inset-bottom),12px)] pt-1">
      <div className="max-w-[760px] mx-auto">
        <div className="flex items-end gap-2 bg-surface border-2 border-line focus-within:border-live/60 rounded-[26px] p-1.5 pl-4 shadow-soft transition-colors">
          <textarea
            ref={ta}
            rows={1}
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={onKey}
            placeholder={`Message ${bot.name}…`}
            aria-label={`Message ${bot.name}`}
            className="flex-1 resize-none bg-transparent outline-none focus-visible:outline-none py-2 text-[15px] leading-6 max-h-[220px] placeholder:text-ink-2/60"
          />
          <AnimatePresence mode="popLayout" initial={false}>
            {busy && !text.trim() ? (
              <motion.button
                key="stop"
                initial={{ scale: 0 }} animate={{ scale: 1 }} exit={{ scale: 0 }} transition={bouncy}
                whileTap={{ scale: 0.85 }}
                onClick={() => api.stop(bot.id)}
                className="size-10 rounded-full bg-surface-2 text-ink grid place-items-center cursor-pointer"
                aria-label="Stop"
                title="Stop"
              >
                <Icon.stop />
              </motion.button>
            ) : (
              <motion.button
                key={"send" + hop}
                initial={hop ? { y: 0 } : false}
                animate={hop ? { y: [0, -10, 0], rotate: [0, -12, 0] } : {}}
                transition={{ duration: 0.4, ease: "easeOut" }}
                whileHover={{ scale: 1.06 }}
                whileTap={{ scale: 0.85 }}
                onClick={send}
                disabled={!text.trim()}
                className="size-10 rounded-full bg-ink text-bg grid place-items-center disabled:opacity-30 cursor-pointer transition-opacity"
                aria-label="Send"
                title="Send"
              >
                <Icon.send />
              </motion.button>
            )}
          </AnimatePresence>
        </div>
        <p className="text-center text-[11px] text-ink-2/70 mt-1.5">
          {busy ? `${bot.name} is working. You can keep chatting.` : "Enter to send · Shift+Enter for a new line"}
        </p>
      </div>
    </div>
  );
}

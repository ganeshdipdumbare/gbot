import { useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Gbot } from "@gbot/characters";
import type { Bot, Skill } from "../lib/api";
import { api } from "../lib/api";
import { useDictation } from "../lib/voice";
import { bouncy, Icon } from "./ui";

type Suggestion = { key: string; label: string; hint?: string; insert: string; bot?: Bot };

let skillsCache: Promise<Skill[]> | null = null;
const loadSkills = () => (skillsCache ??= api.skills().then((r) => r.skills).catch(() => []));
export const invalidateSkills = () => { skillsCache = null; };

export function Composer({ placeholder, busy, members, onSend, onStop, autoFocusKey }: {
  placeholder: string; busy: boolean; members?: Bot[]; onSend: (t: string) => void; onStop: () => void; autoFocusKey: string;
}) {
  const [text, setText] = useState("");
  const [hop, setHop] = useState(0);
  const [skills, setSkills] = useState<Skill[]>([]);
  const [pick, setPick] = useState(0);
  const ta = useRef<HTMLTextAreaElement>(null);
  const dictation = useDictation((t) => setText((x) => (x ? x.trimEnd() + " " : "") + t));

  useEffect(() => { ta.current?.focus(); loadSkills().then(setSkills); }, [autoFocusKey]);
  useLayoutEffect(() => {
    const el = ta.current;
    if (!el) return;
    el.style.height = "0px";
    el.style.height = Math.min(el.scrollHeight, 220) + "px";
  }, [text]);

  // "@" suggests teammates in a group; "/" at the start suggests skills.
  const suggestions = useMemo<Suggestion[]>(() => {
    const at = /(^|\s)@([\p{L}\p{N}_-]*)$/u.exec(text);
    if (at && members?.length) {
      const q = at[2].toLowerCase();
      return members.filter((b) => b.name.toLowerCase().startsWith(q)).map((b) => ({
        key: b.id, label: b.name, hint: b.title, bot: b, insert: text.slice(0, text.length - at[2].length - 1) + "@" + b.name + " ",
      }));
    }
    const slash = /^\/([\w-]*)$/.exec(text);
    if (slash) {
      const q = slash[1].toLowerCase();
      return skills.filter((s) => s.name.startsWith(q)).map((s) => ({ key: s.id, label: "/" + s.name, hint: s.description, insert: `Use the ${s.name} skill: ` }));
    }
    return [];
  }, [text, members, skills]);
  useEffect(() => setPick(0), [suggestions.length]);

  const send = () => {
    const t = text.trim();
    if (!t) return;
    setText("");
    setHop((h) => h + 1);
    onSend(t);
  };
  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (suggestions.length) {
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        setPick((p) => (p + (e.key === "ArrowDown" ? 1 : suggestions.length - 1)) % suggestions.length);
        return;
      }
      if (e.key === "Enter" || e.key === "Tab") {
        e.preventDefault();
        setText(suggestions[pick].insert);
        return;
      }
    }
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      send();
    }
  };

  const listening = dictation.state === "listening";

  return (
    <div className="shrink-0 px-3 sm:px-6 pb-[max(env(safe-area-inset-bottom),12px)] pt-1">
      <div className="max-w-[760px] mx-auto relative">
        <AnimatePresence>
          {suggestions.length > 0 && (
            <motion.ul
              initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 6 }} transition={{ duration: 0.12 }}
              className="absolute bottom-full mb-2 left-0 right-0 bg-surface border border-line rounded-[20px] shadow-pop p-1.5 max-h-64 overflow-auto scroll-soft z-10"
              role="listbox"
            >
              {suggestions.map((s, i) => (
                <li key={s.key}>
                  <button
                    onMouseDown={(e) => { e.preventDefault(); setText(s.insert); ta.current?.focus(); }}
                    onMouseEnter={() => setPick(i)}
                    className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-2xl text-left cursor-pointer ${i === pick ? "bg-surface-2" : ""}`}
                    role="option" aria-selected={i === pick}
                  >
                    {s.bot ? <Gbot look={s.bot.look} seed={s.bot.id} size={24} /> : <span className="text-live"><Icon.spark /></span>}
                    <span className="font-semibold text-[14px]">{s.label}</span>
                    {s.hint && <span className="text-[13px] text-ink-2 truncate">{s.hint}</span>}
                  </button>
                </li>
              ))}
            </motion.ul>
          )}
        </AnimatePresence>
        <div className="flex items-end gap-1.5 bg-surface border-2 border-line focus-within:border-live/60 rounded-[26px] p-1.5 pl-4 shadow-soft transition-colors">
          <textarea
            ref={ta}
            rows={1}
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={onKey}
            placeholder={listening ? "Listening…" : placeholder}
            aria-label={placeholder}
            className="flex-1 resize-none bg-transparent outline-none focus-visible:outline-none py-2 text-[15px] leading-6 max-h-[220px] placeholder:text-ink-2/60"
          />
          {dictation.mode !== "none" && (
            <motion.button
              whileTap={{ scale: 0.88 }} transition={bouncy}
              onClick={dictation.toggle}
              className={`relative size-10 rounded-full grid place-items-center cursor-pointer transition-colors ${listening ? "bg-live text-white" : "text-ink-2 hover:text-ink hover:bg-surface-2"}`}
              aria-label={listening ? "Stop dictation" : "Dictate"}
              title={dictation.mode === "browser" ? "Dictate (your browser's speech service)" : "Dictate (on this machine)"}
            >
              {listening && <motion.span className="absolute inset-0 rounded-full bg-live/40" animate={{ scale: 1 + dictation.level * 1.2 }} transition={{ duration: 0.08 }} />}
              {dictation.state === "working" ? <span className="size-4 rounded-full border-2 border-current border-t-transparent animate-spin" /> : <span className="relative"><Icon.mic /></span>}
            </motion.button>
          )}
          <AnimatePresence mode="popLayout" initial={false}>
            {busy && !text.trim() ? (
              <motion.button key="stop" initial={{ scale: 0 }} animate={{ scale: 1 }} exit={{ scale: 0 }} transition={bouncy} whileTap={{ scale: 0.85 }}
                onClick={onStop} className="size-10 rounded-full bg-surface-2 text-ink grid place-items-center cursor-pointer" aria-label="Stop" title="Stop">
                <Icon.stop />
              </motion.button>
            ) : (
              <motion.button
                key={"send" + hop}
                initial={hop ? { y: 0 } : false}
                animate={hop ? { y: [0, -8, 0] } : {}}
                transition={{ duration: 0.35, ease: "easeOut" }}
                whileTap={{ scale: 0.85 }}
                onClick={send}
                disabled={!text.trim()}
                className="size-10 rounded-full bg-ink text-bg grid place-items-center disabled:opacity-30 cursor-pointer transition-opacity"
                aria-label="Send" title="Send"
              >
                <Icon.send />
              </motion.button>
            )}
          </AnimatePresence>
        </div>
        <p className="text-center text-[11px] text-ink-2/70 mt-1.5">
          {busy ? "Working on it. You can keep chatting." : members ? "@ to mention a bot · / for skills" : "Enter to send · / for skills"}
        </p>
      </div>
    </div>
  );
}

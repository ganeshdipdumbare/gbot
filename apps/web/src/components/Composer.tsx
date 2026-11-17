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

// mentionable are the bots "@" can name: the group's members, or every
// other bot in a one-to-one chat.
export function Composer({ placeholder, busy, mentionable, onSend, onStop, autoFocusKey }: {
  placeholder: string; busy: boolean; mentionable?: Bot[]; onSend: (t: string) => void; onStop: () => void; autoFocusKey: string;
}) {
  const [text, setText] = useState("");
  const [hop, setHop] = useState(0);
  const [skills, setSkills] = useState<Skill[]>([]);
  const [pick, setPick] = useState(0);
  const [menu, setMenu] = useState(false);
  const ta = useRef<HTMLTextAreaElement>(null);
  const dictation = useDictation((t) => setText((x) => (x ? x.trimEnd() + " " : "") + t));

  useEffect(() => { ta.current?.focus(); loadSkills().then(setSkills); }, [autoFocusKey]);
  useLayoutEffect(() => {
    const el = ta.current;
    if (!el) return;
    el.style.height = "0px";
    el.style.height = Math.min(el.scrollHeight, 220) + "px";
  }, [text]);

  // "@" suggests bots by name, spaces included ("@Grok Bot"); "/" at the
  // start suggests skills.
  const suggestions = useMemo<Suggestion[]>(() => {
    const at = /(^|\s)@([^@\n]*)$/.exec(text);
    if (at && mentionable?.length) {
      const q = at[2].toLowerCase();
      return mentionable.filter((b) => b.name.toLowerCase().startsWith(q)).map((b) => ({
        key: b.id, label: b.name, hint: b.title, bot: b, insert: text.slice(0, text.length - at[2].length - 1) + "@" + b.name + " ",
      }));
    }
    const slash = /^\/([\w-]*)$/.exec(text);
    if (slash) {
      const q = slash[1].toLowerCase();
      return skills.filter((s) => s.name.startsWith(q)).map((s) => ({ key: s.id, label: "/" + s.name, hint: s.description, insert: `Use the ${s.name} skill: ` }));
    }
    return [];
  }, [text, mentionable, skills]);
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
    <div className="relative shrink-0 px-4 sm:px-6 pb-[max(env(safe-area-inset-bottom),14px)]">
      {/* A soft fade lifts the composer off the transcript. */}
      <div className="pointer-events-none absolute inset-x-0 -top-6 h-6 bg-gradient-to-t from-[var(--bg)] to-transparent" />
      <div className="max-w-[720px] mx-auto relative">
        <AnimatePresence>
          {suggestions.length > 0 && (
            <motion.ul
              initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 6 }} transition={{ duration: 0.12 }}
              className="absolute bottom-full mb-2 left-0 right-0 bg-bg rounded-xl shadow-pop p-1 max-h-64 overflow-auto scroll-soft z-10"
              role="listbox"
            >
              {suggestions.map((s, i) => (
                <li key={s.key}>
                  <button
                    onMouseDown={(e) => { e.preventDefault(); setText(s.insert); ta.current?.focus(); }}
                    onMouseEnter={() => setPick(i)}
                    className={`w-full flex items-center gap-2.5 px-2.5 py-1.5 rounded-lg text-left cursor-pointer ${i === pick ? "bg-selected" : ""}`}
                    role="option" aria-selected={i === pick}
                  >
                    {s.bot ? <Gbot look={s.bot.look} seed={s.bot.id} size={26} /> : <span className="text-ink-2"><Icon.spark /></span>}
                    <span className="text-[14px]">{s.label}</span>
                    {s.hint && <span className="text-[13px] text-ink-3 truncate">{s.hint}</span>}
                  </button>
                </li>
              ))}
            </motion.ul>
          )}
        </AnimatePresence>
        <div className="flex items-end gap-1.5 rounded-[26px] p-[7px] hairline bg-surface backdrop-blur-xl focus-within:shadow-[inset_0_0_0_1px_var(--ink-3)] transition-shadow duration-[120ms]">
          <div className="relative">
            <button onClick={() => setMenu((v) => !v)} aria-label="More" aria-expanded={menu} title="Skills and mentions"
              className="size-[34px] rounded-full grid place-items-center hairline text-ink-2 hover:text-ink hover:bg-hover cursor-pointer transition-colors duration-[120ms] [&_svg]:size-4">
              <Icon.plus />
            </button>
            <AnimatePresence>
              {menu && (
                <motion.div initial={{ opacity: 0, y: 4, scale: 0.98 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, transition: { duration: 0.1 } }} transition={{ duration: 0.16 }}
                  className="absolute bottom-full mb-2 left-0 w-56 rounded-xl bg-bg shadow-pop p-1 z-20" role="menu">
                  <MenuItem icon={<Icon.spark />} label="Use a skill" hint="/" onClick={() => { setMenu(false); setText("/"); ta.current?.focus(); }} />
                  {mentionable && mentionable.length > 0 && <MenuItem icon={<Icon.users />} label="Mention a bot" hint="@" onClick={() => { setMenu(false); setText((t) => (t ? t.trimEnd() + " @" : "@")); ta.current?.focus(); }} />}
                  {dictation.mode !== "none" && <MenuItem icon={<Icon.mic />} label="Dictate" onClick={() => { setMenu(false); dictation.toggle(); }} />}
                </motion.div>
              )}
            </AnimatePresence>
          </div>
          <textarea
            ref={ta}
            rows={1}
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={onKey}
            placeholder={listening ? "Listening…" : placeholder}
            aria-label={placeholder}
            className="flex-1 resize-none bg-transparent outline-none focus-visible:outline-none py-[6px] px-1 text-[15px] leading-[22px] max-h-[200px] placeholder:text-ink-3"
          />
          {dictation.state !== "idle" && (
            <motion.button
              whileTap={{ scale: 0.88 }} transition={bouncy}
              onClick={dictation.toggle}
              className={`relative size-[34px] rounded-full grid place-items-center cursor-pointer transition-colors duration-[120ms] ${listening ? "bg-live text-white" : "text-ink-3 hover:text-ink hover:bg-hover"}`}
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
                onClick={onStop} className="size-[34px] rounded-full bg-emphasis text-on-emphasis grid place-items-center cursor-pointer [&_svg]:size-3.5" aria-label="Stop" title="Stop">
                <Icon.stop />
              </motion.button>
            ) : (
              <motion.button
                key={"send" + hop}
                initial={false}
                whileTap={{ scale: 0.85 }}
                onClick={send}
                disabled={!text.trim()}
                className="size-[34px] rounded-full bg-emphasis text-on-emphasis grid place-items-center disabled:bg-surface-2 disabled:text-ink-3 cursor-pointer transition-colors duration-[120ms] [&_svg]:size-[18px]"
                aria-label="Send" title="Send"
              >
                <Icon.up />
              </motion.button>
            )}
          </AnimatePresence>
        </div>
      </div>
    </div>
  );
}

function MenuItem({ icon, label, hint, onClick }: { icon: React.ReactNode; label: string; hint?: string; onClick: () => void }) {
  return (
    <button role="menuitem" onClick={onClick}
      className="w-full flex items-center gap-2.5 px-2.5 py-1.5 rounded-lg text-left text-[14px] hover:bg-hover cursor-pointer [&_svg]:size-4 text-ink">
      <span className="text-ink-2">{icon}</span>
      <span className="flex-1">{label}</span>
      {hint && <kbd className="text-[12px] text-ink-3 font-sans">{hint}</kbd>}
    </button>
  );
}

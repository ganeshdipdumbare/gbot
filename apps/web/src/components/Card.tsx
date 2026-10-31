import { useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Gbot } from "@gbot/characters";
import { api, type Bot, type Message } from "../lib/api";
import { patchMessage, keyOf } from "../lib/store";
import { bouncy, Button, Icon } from "./ui";

// Cards are where a bot asks for something: approval, an answer, or a
// hand on its computer. They never block the chat; once answered they
// fold into a one-line receipt.

const status: Record<string, { label: string; tone: string }> = {
  approved: { label: "Approved", tone: "text-emerald-600 dark:text-emerald-400" },
  denied: { label: "Declined", tone: "text-ink-2" },
  answered: { label: "Answered", tone: "text-emerald-600 dark:text-emerald-400" },
  done: { label: "Handed back", tone: "text-emerald-600 dark:text-emerald-400" },
  skipped: { label: "Skipped", tone: "text-ink-2" },
};

function argsPreview(tool: string, args: Record<string, unknown> | undefined) {
  if (!args) return null;
  if (tool === "computer_shell") return String(args.command ?? "");
  if (tool === "computer_write_file") return `${args.path}\n\n${String(args.content ?? "").slice(0, 600)}`;
  if (tool === "browser_open" || tool === "browser_read") return String(args.url ?? "");
  if (tool === "routine_create") return `${args.name}\n${args.schedule}\n\n${args.prompt}`;
  const s = JSON.stringify(args, null, 2);
  return s === "{}" ? null : s;
}

export function Card({ m, bot, onTakeover }: { m: Message; bot: Bot; onTakeover?: () => void }) {
  const d = (m.data ?? {}) as Record<string, unknown>;
  const kind = d.kind as "approval" | "question" | "takeover";
  const st = d.status as string;
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function answer(decision: "approve" | "reject" | "answer", value?: string, always?: boolean) {
    setBusy(true);
    setError("");
    try {
      const upd = await api.answer(m.id, { decision, text: value, always });
      patchMessage(keyOf(m), m.id, () => upd);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (st !== "pending") {
    const s = status[st] ?? { label: st, tone: "text-ink-2" };
    return (
      <motion.div layout initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="flex justify-center my-2">
        <div className="flex items-center gap-2 text-[13px] px-3 py-1.5 rounded-full bg-surface-2/70">
          <Gbot look={bot.look} seed={bot.id} size={18} mood={st === "denied" || st === "skipped" ? "idle" : "done"} />
          <span className={`font-semibold ${s.tone}`}>{s.label}</span>
          <span className="text-ink-2 truncate max-w-[340px]">
            {kind === "question" && d.answer ? `· “${d.answer}”` : `· ${m.content}`}
          </span>
        </div>
      </motion.div>
    );
  }

  const preview = kind === "approval" ? argsPreview(String(d.tool), d.args as Record<string, unknown>) : null;
  const options = (d.options as string[] | undefined) ?? [];

  return (
    <motion.div
      layout
      initial={{ opacity: 0, y: 12, scale: 0.97 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={bouncy}
      className="my-4 ml-[42px] max-w-[min(88%,560px)] rounded-[22px] bg-surface border border-line shadow-pop overflow-hidden"
      role="group"
      aria-label={`${bot.name} needs you`}
    >
      <div className="flex items-start gap-3 px-4 pt-4">
        <Gbot look={bot.look} seed={bot.id} size={36} mood="waiting" />
        <div className="min-w-0 flex-1 pt-0.5">
          <div className="text-[12px] font-semibold uppercase tracking-[.08em] text-live">
            {kind === "approval" ? "Needs your OK" : kind === "question" ? "Question" : "Needs you on its computer"}
          </div>
          <div className="font-display font-extrabold text-[17px] leading-snug mt-0.5 break-words">{m.content || "Take over for a moment"}</div>
        </div>
      </div>

      {preview && (
        <pre className="mx-4 mt-3 max-h-48 overflow-auto scroll-soft rounded-2xl bg-surface-2 px-3 py-2.5 text-[12.5px] leading-relaxed font-mono whitespace-pre-wrap break-words">
          {preview}
        </pre>
      )}

      <div className="px-4 pt-3 pb-4">
        {kind === "approval" && (
          <div className="flex flex-wrap items-center gap-2">
            <Button disabled={busy} onClick={() => answer("approve")}>Approve</Button>
            <Button disabled={busy} variant="soft" onClick={() => answer("reject")}>Decline</Button>
            <button disabled={busy} onClick={() => answer("approve", undefined, true)}
              className="ml-auto text-[13px] font-semibold text-ink-2 hover:text-ink cursor-pointer disabled:opacity-50">
              Always allow {String(d.tool).replace(/^m[0-9a-f]{8}__/, "").replace(/_/g, " ")}
            </button>
          </div>
        )}

        {kind === "question" && (
          <>
            {options.length > 0 && (
              <div className="flex flex-wrap gap-2 mb-3">
                {options.map((o) => (
                  <motion.button key={o} whileTap={{ scale: 0.94 }} transition={bouncy} disabled={busy} onClick={() => answer("answer", o)}
                    className="h-10 px-4 rounded-full border-2 border-line hover:border-ink font-semibold text-[14px] cursor-pointer transition-colors disabled:opacity-50">
                    {o}
                  </motion.button>
                ))}
              </div>
            )}
            <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); if (text.trim()) answer("answer", text.trim()); }}>
              <input value={text} onChange={(e) => setText(e.target.value)} placeholder={options.length ? "Or type an answer" : "Type your answer"}
                className="flex-1 h-10 rounded-full bg-surface-2 px-4 text-[14px] outline-none focus:ring-2 focus:ring-live/50" aria-label="Your answer" />
              <button disabled={busy || !text.trim()} className="size-10 grid place-items-center rounded-full bg-ink text-bg disabled:opacity-30 cursor-pointer" aria-label="Send answer">
                <Icon.send />
              </button>
            </form>
          </>
        )}

        {kind === "takeover" && (
          <div className="flex flex-wrap gap-2">
            <Button onClick={onTakeover}><Icon.screen /> Take control</Button>
            <Button variant="soft" disabled={busy} onClick={() => answer("answer")}>I'm done</Button>
          </div>
        )}

        <AnimatePresence>
          {error && <motion.p initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="text-danger text-[13px] mt-2">{error}</motion.p>}
        </AnimatePresence>
      </div>
    </motion.div>
  );
}

import { useState } from "react";
import { Gbot } from "@gbot/characters";
import { api, type Bot, type Message } from "../lib/api";
import { patchMessage, keyOf } from "../lib/store";
import { Icon } from "./ui";

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
  const [confirmAlways, setConfirmAlways] = useState(false);

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
    const ok = st !== "denied" && st !== "skipped";
    return (
      <div className="flex justify-center items-center gap-1.5 my-2 text-[13px] text-ink-2">
        <span className={`receipt-check grid place-items-center size-4 rounded-full [&_svg]:size-3 ${ok ? "bg-[color-mix(in_srgb,var(--success)_16%,transparent)] text-success" : "bg-surface-2 text-ink-3"}`}>
          {ok ? <Icon.check /> : <Icon.close />}
        </span>
        <span className="text-ink">{s.label}</span>
        <span className="truncate max-w-[380px]">{kind === "question" && d.answer ? `“${d.answer}”` : m.content}</span>
      </div>
    );
  }

  const preview = kind === "approval" ? argsPreview(String(d.tool), d.args as Record<string, unknown>) : null;
  const options = (d.options as string[] | undefined) ?? [];
  const label = kind === "approval" ? "Needs approval" : kind === "question" ? "Question" : "Needs you";

  return (
    <div className="entry-in my-3 max-w-[min(100%,520px)] rounded-2xl bg-surface px-3 pt-2.5 pb-3 text-[14px] leading-5"
      role="group" aria-label={`${bot.name} needs you`}>
      <div className="flex items-center gap-2">
        <Gbot look={bot.look} seed={bot.id} size={18} mood="waiting" />
        <span className="font-semibold truncate">{bot.name}</span>
        <span className="ml-auto inline-flex items-center gap-1 rounded-full px-[7px] text-[12px] leading-[19px] bg-[color-mix(in_srgb,var(--warning)_14%,transparent)] text-warning shrink-0">
          <span className="size-1.5 rounded-full bg-current" />{label}
        </span>
      </div>
      <div className="mt-1.5 break-words">{m.content || "Take over for a moment"}</div>

      {preview && (
        <pre className="mt-2 max-h-44 overflow-auto scroll-soft rounded-[10px] bg-bg hairline px-2.5 py-2 text-[12px] leading-[18px] font-mono whitespace-pre-wrap break-words text-ink-2">
          {preview}
        </pre>
      )}

      <div className="mt-2.5">
        {kind === "approval" && (
          <div className="flex flex-wrap items-center gap-1.5">
            <Pill primary disabled={busy} onClick={() => answer("approve")}>Approve</Pill>
            <Pill disabled={busy} onClick={() => answer("reject")}>Decline</Pill>
            {!confirmAlways ? (
              <button disabled={busy} onClick={() => setConfirmAlways(true)}
                className="ml-auto text-[12px] text-ink-3 hover:text-ink transition-colors duration-[120ms] cursor-pointer disabled:opacity-40">
                Always allow
              </button>
            ) : (
              <div className="ml-auto flex flex-wrap items-center gap-1.5 text-[12px] text-ink-2">
                <span>Allow {String(d.tool)} for every future request, without asking?</span>
                <Pill primary disabled={busy} onClick={() => answer("approve", undefined, true)}>Always allow</Pill>
                <Pill disabled={busy} onClick={() => setConfirmAlways(false)}>Cancel</Pill>
              </div>
            )}
          </div>
        )}

        {kind === "question" && (
          <div className="space-y-1.5">
            {options.map((o, i) => (
              <button key={`${i}:${o}`} disabled={busy} onClick={() => answer("answer", o)}
                className="w-full text-left rounded-[10px] bg-bg hairline px-2.5 py-2 text-[14px] hover:bg-hover transition-colors duration-[120ms] cursor-pointer disabled:opacity-40">
                {o}
              </button>
            ))}
            <form className="flex gap-1.5" onSubmit={(e) => { e.preventDefault(); if (text.trim()) answer("answer", text.trim()); }}>
              <input value={text} onChange={(e) => setText(e.target.value)} placeholder={options.length ? "Something else…" : "Your answer"}
                className="flex-1 rounded-[10px] bg-bg hairline px-2.5 py-2 text-[14px] outline-none focus:shadow-[inset_0_0_0_1px_var(--accent)] placeholder:text-ink-3" aria-label="Your answer" />
              <Pill primary disabled={busy || !text.trim()}>Send</Pill>
            </form>
          </div>
        )}

        {kind === "takeover" && (
          <div className="flex flex-wrap gap-1.5">
            <Pill primary disabled={busy} onClick={onTakeover}>Take control</Pill>
            <Pill disabled={busy} onClick={() => answer("answer")}>I'm done</Pill>
          </div>
        )}

        {error && <p className="text-danger text-[13px] mt-2">{error}</p>}
      </div>
    </div>
  );
}

function Pill({ primary, children, ...rest }: React.ButtonHTMLAttributes<HTMLButtonElement> & { primary?: boolean }) {
  return (
    <button {...rest}
      className={`inline-flex items-center gap-1.5 rounded-full px-3 py-[5px] text-[13px] leading-[18px] font-medium transition-[background-color,opacity] duration-[120ms] cursor-pointer disabled:opacity-40 active:scale-[0.97] ${primary ? "bg-emphasis text-on-emphasis hover:opacity-90" : "bg-surface-2 text-ink hover:bg-selected"}`}>
      {children}
    </button>
  );
}

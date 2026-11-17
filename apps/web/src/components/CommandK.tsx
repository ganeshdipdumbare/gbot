import { useEffect, useMemo, useRef, useState } from "react";
import { Gbot } from "@gbot/characters";
import { groupKey, useStore } from "../lib/store";
import { Stack } from "./Chat";
import { moodOf } from "../lib/hooks";
import { Modal } from "./Modal";
import { Icon } from "./ui";

export function CommandK({ open, onClose, onPick, onHatch }: { open: boolean; onClose: () => void; onPick: (id: string) => void; onHatch: () => void }) {
  const bots = useStore((s) => s.bots);
  const groups = useStore((s) => s.groups);
  const presence = useStore((s) => s.presence);
  const [q, setQ] = useState("");
  const [i, setI] = useState(0);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => { if (open) { setQ(""); setI(0); setTimeout(() => input.current?.focus(), 30); } }, [open]);
  const list = useMemo(() => {
    const s = q.trim().toLowerCase();
    const all = [
      ...bots.map((b) => ({ id: b.id, name: b.name, sub: b.title, bot: b, members: undefined as typeof bots | undefined })),
      ...groups.map((g) => ({ id: groupKey(g.id), name: g.name, sub: "Group", bot: undefined, members: g.botIds.map((id) => bots.find((b) => b.id === id)).filter(Boolean) as typeof bots })),
    ];
    return s ? all.filter((x) => (x.name + " " + x.sub).toLowerCase().includes(s)) : all;
  }, [bots, groups, q]);

  return (
    <Modal open={open} onClose={onClose} width={480}>
      <div className="p-2">
        <div className="flex items-center gap-2 px-3 text-ink-2">
          <Icon.search />
          <input
            ref={input}
            value={q}
            onChange={(e) => { setQ(e.target.value); setI(0); }}
            onKeyDown={(e) => {
              if (e.key === "ArrowDown") { e.preventDefault(); setI((x) => Math.min(x + 1, list.length)); }
              if (e.key === "ArrowUp") { e.preventDefault(); setI((x) => Math.max(x - 1, 0)); }
              if (e.key === "Enter") { if (i < list.length) onPick(list[i].id); else onHatch(); }
            }}
            placeholder="Jump to a bot…"
            className="flex-1 h-12 bg-transparent outline-none text-[14px] text-ink"
            aria-label="Search bots"
          />
        </div>
        <ul className="max-h-[50vh] overflow-y-auto scroll-soft">
          {list.map((b, j) => (
            <li key={b.id}>
              <button onMouseEnter={() => setI(j)} onClick={() => onPick(b.id)}
                className={`w-full flex items-center gap-3 px-3 py-2 rounded-2xl text-left cursor-pointer ${i === j ? "bg-surface-2" : ""}`}>
                {b.bot ? <Gbot look={b.bot.look} seed={b.bot.id} size={40} mood={moodOf(presence[b.bot.id], b.bot)} /> : <Stack bots={b.members ?? []} size={28} />}
                <span className="font-semibold">{b.name}</span>
                <span className="text-ink-2 text-[13px] truncate">{b.sub}</span>
              </button>
            </li>
          ))}
          <li>
            <button onMouseEnter={() => setI(list.length)} onClick={onHatch}
              className={`w-full flex items-center gap-3 px-3 py-2.5 rounded-2xl text-left cursor-pointer ${i === list.length ? "bg-surface-2" : ""}`}>
              <span className="size-8 grid place-items-center rounded-full bg-ink text-bg"><Icon.plus /></span>
              <span className="font-semibold">New bot</span>
            </button>
          </li>
        </ul>
      </div>
    </Modal>
  );
}

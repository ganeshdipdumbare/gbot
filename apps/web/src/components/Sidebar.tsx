import { AnimatePresence, motion } from "motion/react";
import { Gbot } from "@gbot/characters";
import { groupKey, useStore } from "../lib/store";
import { Stack } from "./Chat";
import { moodOf, presenceText, timeAgo } from "../lib/hooks";
import { Icon, IconButton, spring } from "./ui";

export function Sidebar({ activeId, onSelect, onHatch, onSettings, onSearch, onNewGroup }: {
  activeId: string | null; onSelect: (id: string) => void; onHatch: () => void; onSettings: () => void; onSearch: () => void; onNewGroup: () => void;
}) {
  const bots = useStore((s) => s.bots);
  const groups = useStore((s) => s.groups);
  const presence = useStore((s) => s.presence);
  const me = useStore((s) => s.me);
  const connected = useStore((s) => s.connected);

  return (
    <div className="h-full flex flex-col">
      <div className="flex items-center gap-2 px-4 pt-4 pb-3">
        <div className="flex items-center gap-1.5 flex-1 min-w-0">
          <Gbot seed="brand" look={{ shape: "circle", hue: "grape", eyes: "pill" }} size={30} />
          <span className="font-display font-black text-[22px] tracking-tight">gbot</span>
        </div>
        <IconButton label="Search (⌘K)" onClick={onSearch}><Icon.search /></IconButton>
        <IconButton label="New bot" onClick={onHatch} className="bg-ink! text-bg! hover:text-bg!"><Icon.plus /></IconButton>
      </div>

      <nav className="flex-1 overflow-y-auto scroll-soft px-2 pb-2" aria-label="Bots">
        <motion.ul layout className="space-y-0.5">
          <AnimatePresence initial={false}>
            {bots.map((b) => {
              const p = presence[b.id];
              const busy = p && p.state !== "idle";
              const active = b.id === activeId;
              const preview = busy ? presenceText[p.state] + (p.detail && p.state === "working" ? ` ${p.detail}` : "") :
                b.lastMessage ? (b.lastMessage.role === "user" ? "You: " : "") + b.lastMessage.content.replace(/[#*_`>]/g, "") : b.title || "Say hi!";
              return (
                <motion.li
                  key={b.id}
                  layout
                  initial={{ opacity: 0, scale: 0.8 }}
                  animate={{ opacity: 1, scale: 1 }}
                  exit={{ opacity: 0, scale: 0.8 }}
                  transition={spring}
                >
                  <motion.button
                    whileTap={{ scale: 0.97 }}
                    onClick={() => onSelect(b.id)}
                    className={`w-full flex items-center gap-3 rounded-[20px] px-2.5 py-2 text-left transition-colors cursor-pointer ${active ? "bg-surface shadow-soft" : "hover:bg-surface-2"}`}
                    aria-current={active ? "page" : undefined}
                  >
                    <div className="relative shrink-0">
                      <Gbot look={b.look} seed={b.id} size={44} mood={moodOf(p, b)} title={b.name} />
                      {busy && p.state === "waiting" && <span className="absolute -top-0.5 -right-0.5 size-3 rounded-full bg-live ring-2 ring-bg" />}
                      {p?.live && p.state !== "waiting" && <span className="absolute -bottom-0.5 -right-0.5 size-3 rounded-full bg-live ring-2 ring-bg" title="Its computer is active" />}
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-baseline gap-2">
                        <span className="font-display font-extrabold text-[15px] truncate">{b.name}</span>
                        {b.pinned && <span className="text-ink-2 opacity-60 scale-75"><Icon.pin /></span>}
                        <span className="ml-auto text-[11px] text-ink-2 shrink-0">{timeAgo(b.lastMessage?.createdAt)}</span>
                      </div>
                      <div className={`text-[13px] truncate ${busy ? "text-live font-semibold" : "text-ink-2"}`}>{preview}</div>
                    </div>
                  </motion.button>
                </motion.li>
              );
            })}
          </AnimatePresence>
        </motion.ul>
        {(groups.length > 0 || bots.length >= 2) && (
          <div className="mt-4">
            <div className="flex items-center px-2.5 mb-1">
              <span className="text-[11.5px] font-semibold uppercase tracking-[.08em] text-ink-2 flex-1">Groups</span>
              <IconButton label="New group" onClick={onNewGroup} className="size-7!"><Icon.plus /></IconButton>
            </div>
            <ul className="space-y-0.5">
              {groups.map((g) => {
                const members = g.botIds.map((id) => bots.find((b) => b.id === id)).filter(Boolean) as typeof bots;
                const k = groupKey(g.id);
                const active = k === activeId;
                const working = members.find((b) => ["thinking", "working"].includes(presence[b.id]?.state ?? ""));
                return (
                  <li key={g.id}>
                    <motion.button whileTap={{ scale: 0.97 }} onClick={() => onSelect(k)}
                      className={`w-full flex items-center gap-3 rounded-[20px] px-2.5 py-2 text-left transition-colors cursor-pointer ${active ? "bg-surface shadow-soft" : "hover:bg-surface-2"}`}>
                      <div className="w-11 grid place-items-center"><Stack bots={members} size={26} presence={presence} /></div>
                      <div className="min-w-0 flex-1">
                        <div className="flex items-baseline gap-2">
                          <span className="font-display font-extrabold text-[15px] truncate">{g.name}</span>
                          <span className="ml-auto text-[11px] text-ink-2 shrink-0">{timeAgo(g.lastMessage?.createdAt)}</span>
                        </div>
                        <div className={`text-[13px] truncate ${working ? "text-live font-semibold" : "text-ink-2"}`}>
                          {working ? `${working.name} is ${presence[working.id]?.state}…` : g.lastMessage ? g.lastMessage.content.replace(/[#*_`>]/g, "") : members.map((b) => b.name).join(", ")}
                        </div>
                      </div>
                    </motion.button>
                  </li>
                );
              })}
            </ul>
          </div>
        )}
        {bots.length > 0 && bots.length < 3 && (
          <button onClick={onHatch} className="w-full mt-2 rounded-[20px] border-2 border-dashed border-line px-3 py-3 text-[13px] text-ink-2 hover:text-ink hover:border-ink-2/40 transition-colors cursor-pointer">
            One bot per job works best. <span className="font-bold">Add another →</span>
          </button>
        )}
      </nav>

      <div className="border-t border-line px-3 py-2.5 flex items-center gap-2">
        <div className="size-8 rounded-full bg-surface-2 grid place-items-center font-display font-black text-[14px]">{me?.name?.[0]?.toUpperCase()}</div>
        <div className="min-w-0 flex-1">
          <div className="text-[13px] font-semibold truncate">{me?.name}</div>
          <div className="text-[11px] text-ink-2 flex items-center gap-1.5">
            <span className={`size-1.5 rounded-full ${connected ? "bg-emerald-400" : "bg-amber-400"}`} />
            {connected ? "Live · on this machine" : "Reconnecting…"}
          </div>
        </div>
        <IconButton label="Settings" onClick={onSettings}><Icon.gear /></IconButton>
      </div>
    </div>
  );
}

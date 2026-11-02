import { Gbot } from "@gbot/characters";
import type { Bot, Presence } from "../lib/api";
import { groupKey, useStore } from "../lib/store";
import { Stack } from "./Chat";
import { moodOf, presenceText, timeAgo } from "../lib/hooks";
import { Icon, IconButton } from "./ui";
import { Skeleton } from "./Feedback";

const clean = (s: string) => s.replace(/[#*_`>]/g, "").replace(/\s+/g, " ").trim();

export function Sidebar({ activeId, onSelect, onHatch, onSettings, onSearch, onNewGroup }: {
  activeId: string | null; onSelect: (id: string) => void; onHatch: () => void; onSettings: (tab?: "integrations") => void; onSearch: () => void; onNewGroup: () => void;
}) {
  const bots = useStore((s) => s.bots);
  const groups = useStore((s) => s.groups);
  const presence = useStore((s) => s.presence);
  const me = useStore((s) => s.me);
  const connected = useStore((s) => s.connected);
  const loaded = useStore((s) => s.loaded);
  const unread = useStore((s) => s.unread);
  const initials = (me?.name ?? "").split(/\s+/).map((w) => w[0]).join("").slice(0, 2).toUpperCase();

  return (
    <div className="h-full flex flex-col bg-chrome select-none">
      <div className="flex items-center gap-1 h-11 pl-4 pr-2 shrink-0">
        <span className="text-[14px] font-semibold tracking-[-0.2px] flex-1">gbot</span>
        <IconButton label="New group" onClick={onNewGroup} className="size-7!"><Icon.users /></IconButton>
        <IconButton label="New bot" onClick={onHatch} className="size-7!"><Icon.plus /></IconButton>
      </div>

      <div className="px-2.5 pb-2">
        <button onClick={onSearch}
          className="w-full flex items-center gap-2 h-8 px-2.5 rounded-lg bg-surface hairline text-ink-3 text-[13px] hover:text-ink-2 transition-colors duration-[120ms] cursor-pointer">
          <Icon.search />
          <span className="flex-1 text-left">Search</span>
          <kbd className="text-[11px] font-sans text-ink-3">⌘K</kbd>
        </button>
      </div>

      <nav className="flex-1 overflow-y-auto scroll-soft px-2.5 pb-2" aria-label="Bots">
        {!loaded && (
          <div className="space-y-px" aria-hidden>
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="flex items-center gap-2.5 p-2">
                <Skeleton className="size-8 rounded-full!" />
                <div className="flex-1 space-y-1.5"><Skeleton className="h-3 w-24" /><Skeleton className="h-2.5 w-36" /></div>
              </div>
            ))}
          </div>
        )}
        <ul className="space-y-px">
          {bots.map((b) => (
            <li key={b.id}>
              <Row
                active={b.id === activeId}
                onClick={() => onSelect(b.id)}
                avatar={<BotAvatar bot={b} p={presence[b.id]} />}
                name={b.name}
                pinned={b.pinned}
                time={timeAgo(b.lastMessage?.createdAt)}
                preview={preview(b, presence[b.id])}
                busy={!!presence[b.id] && presence[b.id].state !== "idle"}
                unread={b.id === activeId ? 0 : unread[b.id] ?? 0}
              />
            </li>
          ))}
        </ul>

        {groups.length > 0 && (
          <>
            <div className="px-2 pt-4 pb-1 text-[12px] text-ink-3">Groups</div>
            <ul className="space-y-px">
              {groups.map((g) => {
                const members = g.botIds.map((id) => bots.find((b) => b.id === id)).filter(Boolean) as Bot[];
                const working = members.find((b) => ["thinking", "working"].includes(presence[b.id]?.state ?? ""));
                return (
                  <li key={g.id}>
                    <Row
                      active={groupKey(g.id) === activeId}
                      onClick={() => onSelect(groupKey(g.id))}
                      avatar={<div className="size-[42px] grid place-items-center"><Stack bots={members} size={26} presence={presence} /></div>}
                      name={g.name}
                      time={timeAgo(g.lastMessage?.createdAt)}
                      preview={working ? `${working.name} is ${presence[working.id]?.state}…` : g.lastMessage ? clean(g.lastMessage.content) : members.map((b) => b.name).join(", ")}
                      busy={!!working}
                      unread={groupKey(g.id) === activeId ? 0 : unread[groupKey(g.id)] ?? 0}
                    />
                  </li>
                );
              })}
            </ul>
          </>
        )}
      </nav>

      <div className="px-2.5 pt-1 pb-3 shrink-0 space-y-px">
        <button onClick={() => onSettings("integrations")}
          className="w-full flex items-center gap-3 h-10 px-2.5 rounded-xl text-[14px] text-ink-2 hover:text-ink hover:bg-hover transition-colors duration-[120ms] cursor-pointer">
          <span className="size-7 rounded-full hairline grid place-items-center [&_svg]:size-3.5"><Icon.plug /></span>
          Integrations
        </button>
        <button onClick={() => onSettings()}
          className="w-full flex items-center gap-3 h-10 px-2.5 rounded-xl text-[14px] hover:bg-hover transition-colors duration-[120ms] cursor-pointer">
          <span className="size-7 rounded-full bg-surface-2 grid place-items-center text-[11px] font-medium text-ink-2">{initials}</span>
          <span className="truncate flex-1 text-left">{me?.name}</span>
          {!connected && <span className="text-[11px] text-warning">Reconnecting</span>}
          <span className="text-ink-3 [&_svg]:size-4"><Icon.gear /></span>
        </button>
      </div>
    </div>
  );
}

function preview(b: Bot, p?: Presence) {
  if (p && p.state !== "idle") return p.state === "working" && p.detail ? p.detail : presenceText[p.state];
  if (b.lastMessage) return (b.lastMessage.role === "user" ? "You: " : "") + clean(b.lastMessage.content);
  return b.title || "New bot";
}

function BotAvatar({ bot, p }: { bot: Bot; p?: Presence }) {
  return (
    <div className="relative shrink-0">
      <Gbot look={bot.look} seed={bot.id} size={42} mood={moodOf(p, bot)} title={bot.name} />
      {p?.state === "waiting" && <span className="absolute -top-px -right-px size-2.5 rounded-full bg-danger ring-2 ring-[var(--chrome)]" title="Needs you" />}
      {p?.live && p.state !== "waiting" && <span className="absolute -bottom-px -right-px size-2.5 rounded-full bg-live ring-2 ring-[var(--chrome)]" title="Its computer is on" />}
    </div>
  );
}

function Row({ active, onClick, avatar, name, pinned, time, preview, busy, unread }: {
  active: boolean; onClick: () => void; avatar: React.ReactNode; name: string; pinned?: boolean; time: string; preview: string; busy: boolean; unread: number;
}) {
  return (
    <button
      onClick={onClick}
      aria-current={active ? "page" : undefined}
      className={`w-full flex items-center gap-2.5 rounded-xl px-2 py-1.5 text-left transition-colors duration-[120ms] cursor-pointer ${active ? "bg-selected" : "hover:bg-hover"}`}
    >
      {avatar}
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline gap-2">
          <span className={`text-[15px] leading-5 truncate ${unread ? "font-semibold" : "font-medium"}`}>{name}</span>
          {pinned && <span className="text-ink-3 shrink-0 [&_svg]:size-3"><Icon.pin /></span>}
          <span className="ml-auto text-[12px] leading-4 text-ink-3 shrink-0">{time}</span>
        </div>
        <div className="flex items-center gap-2">
          <div className={`flex-1 text-[13px] leading-[18px] truncate ${busy || unread ? "text-ink-2" : "text-ink-3"}`}>{preview}</div>
          {unread > 0 && (
            <span className="shrink-0 min-w-[18px] h-[18px] px-1 rounded-full bg-accent text-white text-[11px] font-semibold leading-[18px] text-center" aria-label={`${unread} unread`}>
              {unread > 99 ? "99+" : unread}
            </span>
          )}
        </div>
      </div>
    </button>
  );
}

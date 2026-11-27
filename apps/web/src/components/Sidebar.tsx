import { memo, useEffect, useMemo, useRef, useState, type MouseEvent as RMouseEvent, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { Gbot } from "@gbot/characters";
import { api, type Bot, type Presence } from "../lib/api";
import { groupKey, removeBot, set as setStore, upsertBot, useStore } from "../lib/store";
import { Stack } from "./Chat";
import { moodOf, presenceText, timeAgo } from "../lib/hooks";
import { Button, Icon, IconButton, Input, Label } from "./ui";
import { Skeleton } from "./Feedback";
import { Modal } from "./Modal";
import { toast, toastError } from "../lib/toast";

const clean = (s: string) => s.replace(/[#*_`>]/g, "").replace(/\s+/g, " ").trim();

const readHidden = (): Set<string> => {
  try {
    const v = JSON.parse(localStorage.getItem("gbot.hidden") ?? "[]");
    return new Set(Array.isArray(v) ? v.filter((x) => typeof x === "string") : []);
  } catch { return new Set(); }
};

export function Sidebar({ activeId, compact, onSelect, onHatch, onSettings, onSearch, onNewGroup }: {
  activeId: string | null; compact: boolean; onSelect: (id: string) => void; onHatch: () => void;
  onSettings: () => void; onSearch: () => void; onNewGroup: () => void;
}) {
  const bots = useStore((s) => s.bots);
  const groups = useStore((s) => s.groups);
  const presence = useStore((s) => s.presence);
  const computers = useStore((s) => s.computers);
  const me = useStore((s) => s.me);
  const connected = useStore((s) => s.connected);
  const loaded = useStore((s) => s.loaded);
  const unread = useStore((s) => s.unread);
  const initials = (me?.name ?? "").split(/\s+/).map((w) => w[0]).join("").slice(0, 2).toUpperCase();

  const [hidden, setHidden] = useState<Set<string>>(readHidden);
  const [menu, setMenu] = useState<{ bot: Bot; x: number; y: number } | null>(null);
  const [renaming, setRenaming] = useState<Bot | null>(null);
  const [renameText, setRenameText] = useState("");
  const [deleting, setDeleting] = useState<Bot | null>(null);
  const [deletingBusy, setDeletingBusy] = useState(false);
  const [showHidden, setShowHidden] = useState(false);
  const [tip, setTip] = useState<{ name: string; job: string; x: number; y: number } | null>(null);

  const visibleBots = useMemo(() => bots.filter((b) => !hidden.has(b.id)), [bots, hidden]);
  const hiddenBots = useMemo(() => bots.filter((b) => hidden.has(b.id)), [bots, hidden]);

  const saveHidden = (next: Set<string>) => {
    setHidden(next);
    try { localStorage.setItem("gbot.hidden", JSON.stringify([...next])); } catch { /* storage blocked */ }
  };
  const hide = (bot: Bot) => {
    const next = new Set(hidden);
    next.add(bot.id);
    saveHidden(next);
    if (bot.id === activeId) {
      const first = visibleBots.find((b) => b.id !== bot.id);
      if (first) onSelect(first.id);
    }
    setMenu(null);
  };
  const unhide = (bot: Bot) => {
    const next = new Set(hidden);
    next.delete(bot.id);
    saveHidden(next);
  };

  const openMenu = (e: RMouseEvent, bot: Bot) => {
    e.preventDefault();
    setTip(null);
    setMenu({ bot, x: e.clientX, y: e.clientY });
  };

  // A small fixed card next to the row. Native title tooltips are
  // swallowed by the character SVG, so draw our own in a portal.
  const hover = (el: HTMLElement | null, name: string, job: string) => {
    if (!el) { setTip(null); return; }
    const r = el.getBoundingClientRect();
    setTip({
      name, job,
      x: Math.min(r.right + 8, window.innerWidth - 280),
      y: r.top + r.height / 2,
    });
  };
  useEffect(() => {
    if (!tip) return;
    const clear = () => setTip(null);
    window.addEventListener("scroll", clear, true);
    window.addEventListener("resize", clear);
    return () => {
      window.removeEventListener("scroll", clear, true);
      window.removeEventListener("resize", clear);
    };
  }, [tip]);

  useEffect(() => {
    if (!menu) return;
    const close = () => setMenu(null);
    const key = (e: KeyboardEvent) => { if (e.key === "Escape") close(); };
    window.addEventListener("scroll", close, true);
    window.addEventListener("resize", close);
    window.addEventListener("keydown", key);
    return () => {
      window.removeEventListener("scroll", close, true);
      window.removeEventListener("resize", close);
      window.removeEventListener("keydown", key);
    };
  }, [menu]);

  async function saveRename(e: React.FormEvent) {
    e.preventDefault();
    if (!renaming) return;
    const name = renameText.trim();
    if (!name) return;
    try {
      upsertBot(await api.updateBot(renaming.id, { name }));
      toast("Renamed");
      setRenaming(null);
    } catch (err) { toastError(err, "Couldn't rename"); }
  }

  async function confirmDelete() {
    if (!deleting || deletingBusy) return;
    setDeletingBusy(true);
    try {
      await api.archiveBot(deleting.id);
      removeBot(deleting.id);
      if (hidden.has(deleting.id)) {
        const next = new Set(hidden);
        next.delete(deleting.id);
        saveHidden(next);
      }
      toast(`${deleting.name} deleted`);
      setDeleting(null);
    } catch (err) {
      toastError(err, "Couldn't delete");
    } finally {
      setDeletingBusy(false);
    }
  }

  return (
    <div className="h-full flex flex-col bg-chrome select-none">
      <div className={compact ? "flex flex-col items-center gap-0.5 py-2 shrink-0" : "flex items-center gap-1 h-11 pl-4 pr-2 shrink-0"}>
        {!compact && <span className="text-[16px] font-semibold tracking-[-0.2px] flex-1">gbot</span>}
        {compact && <IconButton label="Search" onClick={onSearch} className="size-7!"><Icon.search /></IconButton>}
        <IconButton label="New group" onClick={onNewGroup} className="size-7!"><Icon.users /></IconButton>
        <IconButton label="New bot" onClick={onHatch} className="size-7!"><Icon.plus /></IconButton>
      </div>

      {!compact && (
        <div className="px-2.5 pb-2">
          <button onClick={onSearch}
            className="w-full flex items-center gap-2 h-8 px-2.5 rounded-lg bg-surface hairline text-ink-3 text-[13px] hover:text-ink-2 transition-colors duration-[120ms] cursor-pointer">
            <Icon.search />
            <span className="flex-1 text-left">Search</span>
            <kbd className="text-[11px] font-sans text-ink-3">⌘K</kbd>
          </button>
        </div>
      )}

      <nav className={`flex-1 overflow-y-auto scroll-soft pb-2 ${compact ? "px-1.5" : "px-2.5"}`} aria-label="Bots">
        {!loaded && (
          <div className={compact ? "flex flex-col items-center gap-1 pt-1" : "space-y-px"} aria-hidden>
            {[0, 1, 2, 3].map((i) => compact
              ? <Skeleton key={i} className="size-10 rounded-full!" />
              : <div key={i} className="flex items-center gap-2.5 p-2">
                  <Skeleton className="size-8 rounded-full!" />
                  <div className="flex-1 space-y-1.5"><Skeleton className="h-3 w-24" /><Skeleton className="h-2.5 w-36" /></div>
                </div>)}
          </div>
        )}
        <ul className="space-y-px">
          {visibleBots.map((b) => {
            const bp = presence[b.id];
            const live = !!bp?.live || computers[b.id]?.state === "on";
            return (
            <li key={b.id}>
              <Row
                compact={compact}
                active={b.id === activeId}
                onClick={() => onSelect(b.id)}
                onMenu={(e) => openMenu(e, b)}
                onHover={(el) => hover(el, b.name, b.title || "General-purpose bot")}
                avatar={<BotAvatar bot={b} p={live && !bp?.live ? { state: bp?.state ?? "idle", live: true } : bp} />}
                name={b.name}
                titleText={b.title ? `${b.name} — ${b.title}` : b.name}
                pinned={b.pinned}
                time={timeAgo(b.lastMessage?.createdAt)}
                preview={preview(b, presence[b.id])}
                busy={!!presence[b.id] && presence[b.id].state !== "idle"}
                unread={b.id === activeId ? 0 : unread[b.id] ?? 0}
              />
            </li>
            );
          })}
        </ul>

        {groups.length > 0 && (
          <>
            {compact
              ? <div className="my-1.5 mx-2 h-px bg-line" />
              : <div className="px-2 pt-4 pb-1 text-[12px] text-ink-3">Groups</div>}
            <ul className="space-y-px">
              {groups.map((g) => {
                const members = g.botIds.map((id) => bots.find((b) => b.id === id)).filter(Boolean) as Bot[];
                const working = members.find((b) => ["thinking", "working"].includes(presence[b.id]?.state ?? ""));
                return (
                  <li key={g.id}>
                    <Row
                      compact={compact}
                      active={groupKey(g.id) === activeId}
                      onClick={() => onSelect(groupKey(g.id))}
                      onHover={(el) => hover(el, g.name, members.map((b) => b.name).join(", "))}
                      avatar={<div className="size-[52px] grid place-items-center transition-transform duration-200 ease-out group-hover:scale-[1.03]"><Stack bots={members} size={28} presence={presence} /></div>}
                      name={g.name}
                      titleText={`${g.name} — ${members.map((b) => b.name).join(", ")}`}
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

      <div className={compact ? "flex flex-col items-center gap-1 py-2 shrink-0" : "px-2.5 pt-1 pb-3 shrink-0 space-y-px"}>
        {hiddenBots.length > 0 && (compact ? (
          <IconButton label={`Hidden bots (${hiddenBots.length})`} onClick={() => setShowHidden(true)} className="size-8!"><Icon.eye /></IconButton>
        ) : (
          <button onClick={() => setShowHidden(true)}
            className="w-full flex items-center gap-3 h-10 px-2.5 rounded-xl text-[14px] text-ink-2 hover:text-ink hover:bg-hover transition-colors duration-[120ms] cursor-pointer">
            <span className="size-7 rounded-full hairline grid place-items-center [&_svg]:size-3.5"><Icon.eye /></span>
            Hidden ({hiddenBots.length})
          </button>
        ))}
        {compact ? (
          <IconButton label={`Settings (${me?.name ?? ""})`} onClick={onSettings} className="size-8!">
            <span className="size-7 rounded-full bg-surface-2 grid place-items-center text-[11px] font-medium text-ink-2">{initials}</span>
          </IconButton>
        ) : (
          <button onClick={onSettings}
            className="w-full flex items-center gap-3 h-10 px-2.5 rounded-xl text-[14px] hover:bg-hover transition-colors duration-[120ms] cursor-pointer">
            <span className="size-7 rounded-full bg-surface-2 grid place-items-center text-[11px] font-medium text-ink-2">{initials}</span>
            <span className="truncate flex-1 text-left">{me?.name}</span>
            {!connected && <span className="text-[11px] text-warning">Reconnecting</span>}
            <span className="text-ink-3 [&_svg]:size-4"><Icon.gear /></span>
          </button>
        )}
      </div>

      {tip && createPortal(
        <div className="fixed z-[60] pointer-events-none rounded-xl bg-bg shadow-pop hairline px-3 py-2 max-w-[260px] pop-in"
          style={{ left: tip.x, top: tip.y, transform: "translateY(-50%)" }}>
          <div className="text-[13px] font-semibold leading-5 truncate">{tip.name}</div>
          {tip.job && <div className="text-[12px] leading-4 text-ink-3 truncate">{tip.job}</div>}
        </div>,
        document.body,
      )}

      {menu && (
        <BotMenu
          bot={menu.bot} x={menu.x} y={menu.y}
          onClose={() => setMenu(null)}
          onPin={async () => {
            try { upsertBot(await api.updateBot(menu.bot.id, { pinned: !menu.bot.pinned })); } catch (e) { toastError(e, "Couldn't pin"); }
            setMenu(null);
          }}
          onUnread={() => {
            setStore((s) => ({ unread: { ...s.unread, [menu.bot.id]: Math.max(1, s.unread[menu.bot.id] ?? 0) } }));
            setMenu(null);
          }}
          onRename={() => { setRenaming(menu.bot); setRenameText(menu.bot.name); setMenu(null); }}
          onCopy={async () => {
            try { await navigator.clipboard.writeText(menu.bot.id); toast("Copied conversation ID"); } catch { toast("Couldn't copy", "error"); }
            setMenu(null);
          }}
          onHide={() => hide(menu.bot)}
          onDelete={() => { setDeleting(menu.bot); setMenu(null); }}
        />
      )}

      <Modal open={!!deleting} onClose={() => { if (!deletingBusy) setDeleting(null); }} title={deleting ? `Delete ${deleting.name}?` : "Delete bot"} width={420}>
        <div className="px-5 pb-5 space-y-4">
          <p className="text-[14px] text-ink-2">Their chat is kept.</p>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="soft" disabled={deletingBusy} onClick={() => setDeleting(null)}>Cancel</Button>
            <Button type="button" variant="danger" disabled={deletingBusy} onClick={confirmDelete}>{deletingBusy ? "Deleting…" : "Delete"}</Button>
          </div>
        </div>
      </Modal>

      <Modal open={!!renaming} onClose={() => setRenaming(null)} title="Rename bot" width={420}>
        <form onSubmit={saveRename} className="px-5 pb-5 space-y-4">
          <div><Label>Name</Label><Input autoFocus value={renameText} maxLength={40} onChange={(e) => setRenameText(e.target.value)} /></div>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="soft" onClick={() => setRenaming(null)}>Cancel</Button>
            <Button type="submit" disabled={!renameText.trim()}>Save</Button>
          </div>
        </form>
      </Modal>

      <Modal open={showHidden} onClose={() => setShowHidden(false)} title="Hidden bots" width={420}>
        <div className="px-5 pb-5 space-y-1">
          {hiddenBots.length === 0
            ? <p className="text-[14px] text-ink-2">No hidden bots.</p>
            : hiddenBots.map((b) => (
                <div key={b.id} className="flex items-center gap-3 py-2">
                  <BotAvatar bot={b} p={presence[b.id]} />
                  <span className="flex-1 truncate text-[14px]">{b.name}</span>
                  <Button variant="soft" onClick={() => unhide(b)}>Show</Button>
                </div>
              ))}
        </div>
      </Modal>
    </div>
  );
}

function preview(b: Bot, p?: Presence) {
  if (p && p.state !== "idle") return p.state === "working" && p.detail ? p.detail : presenceText[p.state];
  if (b.lastMessage) return (b.lastMessage.role === "user" ? "You: " : "") + clean(b.lastMessage.content);
  return b.title || "New bot";
}

const BotAvatar = memo(function BotAvatar({ bot, p }: { bot: Bot; p?: Presence }) {
  return (
    <div className="relative shrink-0 transition-transform duration-200 ease-out group-hover:scale-[1.04]">
      <Gbot look={bot.look} seed={bot.id} size={52} mood={moodOf(p, bot)} />
      {p?.state === "waiting" && <span className="absolute -top-px -right-px size-2.5 rounded-full bg-danger ring-2 ring-[var(--chrome)]" title="Needs you" />}
      {p?.live && p.state !== "waiting" && <span className="absolute -bottom-px -right-px size-2.5 rounded-full bg-live ring-2 ring-[var(--chrome)]" title="Its computer is on" />}
    </div>
  );
});

function Row({ compact, active, onClick, onMenu, onHover, avatar, name, titleText, pinned, time, preview, busy, unread }: {
  compact: boolean; active: boolean; onClick: () => void; onMenu?: (e: RMouseEvent) => void;
  onHover?: (el: HTMLElement | null) => void;
  avatar: ReactNode; name: string; titleText: string; pinned?: boolean; time: string; preview: string; busy: boolean; unread: number;
}) {
  if (compact) {
    return (
      <button onClick={onClick} onContextMenu={onMenu} aria-label={titleText}
        onMouseEnter={(e) => onHover?.(e.currentTarget)} onMouseLeave={() => onHover?.(null)}
        onFocus={(e) => onHover?.(e.currentTarget)} onBlur={() => onHover?.(null)}
        aria-current={active ? "page" : undefined}
        className={`group relative w-full flex justify-center rounded-xl p-1.5 my-0.5 transition-colors duration-[120ms] cursor-pointer ${active ? "bg-selected" : "hover:bg-hover"}`}>
        <div className="relative">
          {avatar}
          {pinned && <span className="absolute -bottom-0.5 -left-1 text-ink-3 [&_svg]:size-3"><Icon.pin /></span>}
          {unread > 0 && (
            <span className="absolute -top-1 -right-1 min-w-[16px] h-4 px-1 rounded-full bg-accent text-white text-[10px] font-semibold leading-4 text-center">
              {unread > 9 ? "9+" : unread}
            </span>
          )}
        </div>
      </button>
    );
  }
  return (
    <button
      onClick={onClick}
      onContextMenu={onMenu}
      onMouseEnter={(e) => onHover?.(e.currentTarget)} onMouseLeave={() => onHover?.(null)}
      onFocus={(e) => onHover?.(e.currentTarget)} onBlur={() => onHover?.(null)}
      aria-label={titleText}
      aria-current={active ? "page" : undefined}
      className={`group w-full flex items-center gap-2.5 rounded-xl px-2 py-1.5 text-left transition-colors duration-[120ms] cursor-pointer ${active ? "bg-selected" : "hover:bg-hover"}`}
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

function BotMenu({ bot, x, y, onClose, onPin, onUnread, onRename, onCopy, onHide, onDelete }: {
  bot: Bot; x: number; y: number; onClose: () => void; onPin: () => void; onUnread: () => void;
  onRename: () => void; onCopy: () => void; onHide: () => void; onDelete: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  // Least privilege when the role is unknown: the server enforces the rest.
  const manage = bot.role === "owner" || bot.role === "manager";
  const owner = bot.role === "owner";
  // Keep the menu on screen; it is fixed to the pointer.
  const left = Math.max(8, Math.min(x, window.innerWidth - 252));
  const top = Math.max(8, Math.min(y, window.innerHeight - 360));
  return (
    <>
      <div className="fixed inset-0 z-40" onClick={onClose} onContextMenu={(e) => { e.preventDefault(); onClose(); }} />
      <div ref={ref} style={{ left, top }} className="fixed z-50 w-[240px] rounded-2xl bg-bg shadow-pop hairline p-1.5 text-[14px] pop-in">
        {manage && <MenuItem icon={<Icon.pin />} onClick={onPin}>{bot.pinned ? "Unpin" : "Pin"}</MenuItem>}
        <MenuItem icon={<Icon.unread />} onClick={onUnread}>Mark as Unread</MenuItem>
        {manage && <><div className="my-1 h-px bg-line" />
          <MenuItem icon={<Icon.gear />} onClick={onRename}>Rename Bot</MenuItem>
          <MenuItem icon={<Icon.copy />} onClick={onCopy}>Copy conversation ID</MenuItem>
        </>}
        {!manage && <MenuItem icon={<Icon.copy />} onClick={onCopy}>Copy conversation ID</MenuItem>}
        <div className="my-1 h-px bg-line" />
        <MenuItem icon={<Icon.eyeOff />} onClick={onHide}>Hide from sidebar</MenuItem>
        {owner && !bot.main && <MenuItem icon={<Icon.trash />} danger onClick={onDelete}>Delete</MenuItem>}
      </div>
    </>
  );
}

function MenuItem({ icon, children, onClick, danger }: { icon: ReactNode; children: ReactNode; onClick: () => void; danger?: boolean }) {
  return (
    <button onClick={onClick}
      className={`w-full flex items-center gap-2.5 h-9 px-2.5 rounded-lg text-left cursor-pointer transition-colors duration-[120ms] ${danger ? "text-danger hover:bg-danger/10" : "text-ink hover:bg-hover"}`}>
      <span className="text-ink-3 [&_svg]:size-4">{icon}</span>
      {children}
    </button>
  );
}

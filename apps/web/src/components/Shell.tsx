import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { connectLive, get, groupKey, isGroupKey, loadAll, markRead, set, useStore } from "../lib/store";
import { toastError } from "../lib/toast";
import { useMediaQuery } from "../lib/hooks";
import { Sidebar } from "./Sidebar";
import { Chat } from "./Chat";
import { Hatch } from "./Hatch";
import { BotSettings } from "./BotSettings";
import { Settings, type SettingsTab } from "./Settings";
import { CommandK } from "./CommandK";
import { NewGroup, GroupSettings } from "./NewGroup";
import { SidePanel } from "./SidePanel";
import { fade, leave, spring } from "./ui";

export type Panel = null | "hatch" | "bot" | "group" | "settings" | "search" | "newGroup";

const clamp = (n: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, n));
const savedWidth = (key: string, def: number, lo: number, hi: number) => {
  try {
    const n = Number(localStorage.getItem(key));
    return Number.isFinite(n) && n > 0 ? clamp(n, lo, hi) : def;
  } catch { return def; }
};

// A thin drag handle between panes. It moves in one-pixel steps, so the
// panel follows the pointer instead of jumping on release.
function Splitter({ onMove, label }: { onMove: (dx: number) => void; label: string }) {
  return (
    <div role="separator" aria-orientation="vertical" aria-label={label} tabIndex={0}
      className="w-[5px] shrink-0 cursor-col-resize bg-transparent hover:bg-line focus:bg-line outline-none"
      onPointerDown={(e) => {
        e.preventDefault();
        let last = e.clientX;
        document.body.style.cursor = "col-resize";
        document.body.style.userSelect = "none";
        const move = (ev: PointerEvent) => { onMove(ev.clientX - last); last = ev.clientX; };
        const up = () => {
          window.removeEventListener("pointermove", move);
          window.removeEventListener("pointerup", up);
          window.removeEventListener("pointercancel", up);
          window.removeEventListener("blur", up);
          document.body.style.cursor = "";
          document.body.style.userSelect = "";
        };
        window.addEventListener("pointermove", move);
        window.addEventListener("pointerup", up);
        // A drag can end outside the window or be cancelled by the system;
        // either way the listeners and body styles must still be cleared.
        window.addEventListener("pointercancel", up);
        window.addEventListener("blur", up);
      }}
    />
  );
}

export function Shell() {
  const [panel, setPanel] = useState<Panel>(null);
  const [drawer, setDrawer] = useState(false);
  const [side, setSide] = useState<null | "computer">(null);
  const [takeover, setTakeover] = useState(false);
  const [settingsTab, setSettingsTab] = useState<SettingsTab>("general");
  const [sidebarW, setSidebarW] = useState(() => savedWidth("gbot.sidebarW", 272, 64, 420));
  const [sideW, setSideW] = useState(() => savedWidth("gbot.sideW", 420, 320, 760));
  const wide = useMediaQuery("(min-width: 820px)");
  const roomy = useMediaQuery("(min-width: 1180px)");
  // The phone app can open a specific settings tab with ?settings=vault.
  useEffect(() => {
    const q = new URLSearchParams(window.location.search).get("settings");
    const valid: SettingsTab[] = ["general", "providers", "team", "integrations", "vault", "skills", "voice"];
    if (q && valid.includes(q as SettingsTab)) {
      setSettingsTab(q as SettingsTab);
      setPanel("settings");
      window.history.replaceState({}, "", window.location.pathname);
    }
  }, []);
  const activeId = useStore((s) => s.activeId);
  const activeBot = useStore((s) => s.bots.find((b) => b.id === s.activeId));
  const activeGroup = useStore((s) => s.groups.find((g) => groupKey(g.id) === s.activeId));

  useEffect(() => {
    loadAll().catch((e) => toastError(e, "Couldn't load your bots"));
    const off = connectLive();
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setPanel((p) => (p === "search" ? null : "search"));
      }
    };
    window.addEventListener("keydown", onKey);
    return () => { off(); window.removeEventListener("keydown", onKey); };
  }, []);

  useEffect(() => {
    try { localStorage.setItem("gbot.sidebarW", String(sidebarW)); } catch { /* storage blocked */ }
  }, [sidebarW]);
  useEffect(() => {
    try { localStorage.setItem("gbot.sideW", String(sideW)); } catch { /* storage blocked */ }
  }, [sideW]);

  // The computer panel belongs to one bot; groups don't have one.
  useEffect(() => { if (isGroupKey(activeId)) setSide(null); setTakeover(false); markRead(activeId); }, [activeId]);
  // Coming back to the tab reads the open chat.
  useEffect(() => {
    const onVis = () => { if (document.visibilityState === "visible") markRead(get().activeId); };
    document.addEventListener("visibilitychange", onVis);
    return () => document.removeEventListener("visibilitychange", onVis);
  }, []);

  const select = (id: string) => {
    set(() => ({ activeId: id }));
    setDrawer(false);
    try { localStorage.setItem("gbot.active", id); } catch { /* storage blocked */ }
  };

  const sidebar = (
    <Sidebar
      activeId={activeId}
      compact={sidebarW < 160}
      onSelect={select}
      onHatch={() => { setPanel("hatch"); setDrawer(false); }}
      onSettings={() => { setSettingsTab("general"); setPanel("settings"); setDrawer(false); }}
      onSearch={() => setPanel("search")}
      onNewGroup={() => { setPanel("newGroup"); setDrawer(false); }}
    />
  );

  const sidePanel = activeBot && side && (
    <SidePanel bot={activeBot} onClose={() => setSide(null)} takeover={takeover} setTakeover={setTakeover} />
  );

  return (
    <div className="h-full flex">
      {wide ? (
        <>
          <aside style={{ width: sidebarW }} className="shrink-0 h-full hairline-r">{sidebar}</aside>
          <Splitter label="Resize bot list" onMove={(dx) => setSidebarW((w) => clamp(w + dx, 64, 420))} />
        </>
      ) : (
        <AnimatePresence>
          {drawer && (
            <>
              <motion.div className="fixed inset-0 bg-black/30 z-30" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={fade} onClick={() => setDrawer(false)} />
              <motion.aside className="fixed inset-y-0 left-0 w-[86%] max-w-[300px] z-40 shadow-pop"
                initial={{ x: "-100%" }} animate={{ x: 0 }} exit={{ x: "-100%", transition: leave }} transition={spring}>
                {sidebar}
              </motion.aside>
            </>
          )}
        </AnimatePresence>
      )}
      <main className="flex-1 min-w-0 h-full">
        <Chat
          key={activeId ?? "none"}
          chatKey={activeId}
          onMenu={wide ? undefined : () => setDrawer(true)}
          onSettings={() => setPanel(activeGroup ? "group" : "bot")}
          onHatch={() => setPanel("hatch")}
          panelOpen={!!side}
          onComputer={(take) => { setSide((s) => (take ? "computer" : s ? null : "computer")); if (take) setTakeover(true); }}
        />
      </main>

      {/* Pinned beside the chat on wide screens; a sheet otherwise. */}
      {roomy ? (
        sidePanel && (
          <>
            <Splitter label="Resize computer panel" onMove={(dx) => setSideW((w) => clamp(w + dx, 320, 760))} />
            <aside className="h-full hairline-l overflow-hidden shrink-0 fade-in" style={{ width: sideW }}>
              <div className="h-full" style={{ width: sideW }}>{sidePanel}</div>
            </aside>
          </>
        )
      ) : (
        <AnimatePresence>
          {sidePanel && (
            <>
              <motion.div className="fixed inset-0 bg-black/30 z-30" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={fade} onClick={() => setSide(null)} />
              <motion.aside className="fixed inset-y-0 right-0 w-full max-w-[440px] z-40 shadow-pop"
                initial={{ x: "100%" }} animate={{ x: 0 }} exit={{ x: "100%", transition: leave }} transition={spring}>
                {sidePanel}
              </motion.aside>
            </>
          )}
        </AnimatePresence>
      )}

      <Hatch open={panel === "hatch"} onClose={() => setPanel(null)} onCreated={(id) => { setPanel(null); select(id); }} />
      <BotSettings open={panel === "bot"} botId={activeBot?.id ?? null} onClose={() => setPanel(null)} />
      <GroupSettings open={panel === "group"} group={activeGroup} onClose={() => setPanel(null)} />
      <NewGroup open={panel === "newGroup"} onClose={() => setPanel(null)} onCreated={(g) => { setPanel(null); select(groupKey(g.id)); }} />
      <Settings open={panel === "settings"} onClose={() => setPanel(null)} initialTab={settingsTab} />
      <CommandK open={panel === "search"} onClose={() => setPanel(null)} onPick={(id) => { setPanel(null); select(id); }} onHatch={() => setPanel("hatch")} />
    </div>
  );
}

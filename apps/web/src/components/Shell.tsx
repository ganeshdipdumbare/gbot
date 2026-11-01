import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { connectLive, groupKey, isGroupKey, loadAll, set, useStore } from "../lib/store";
import { useMediaQuery } from "../lib/hooks";
import { Sidebar } from "./Sidebar";
import { Chat } from "./Chat";
import { Hatch } from "./Hatch";
import { BotSettings } from "./BotSettings";
import { Settings } from "./Settings";
import { CommandK } from "./CommandK";
import { NewGroup, GroupSettings } from "./NewGroup";
import { SidePanel } from "./SidePanel";
import { spring } from "./ui";

export type Panel = null | "hatch" | "bot" | "group" | "settings" | "search" | "newGroup";

export function Shell() {
  const [panel, setPanel] = useState<Panel>(null);
  const [drawer, setDrawer] = useState(false);
  const [side, setSide] = useState<null | "computer" | "routines">(null);
  const [takeover, setTakeover] = useState(false);
  const wide = useMediaQuery("(min-width: 820px)");
  const roomy = useMediaQuery("(min-width: 1180px)");
  const activeId = useStore((s) => s.activeId);
  const activeBot = useStore((s) => s.bots.find((b) => b.id === s.activeId));
  const activeGroup = useStore((s) => s.groups.find((g) => groupKey(g.id) === s.activeId));

  useEffect(() => {
    loadAll().catch(() => {});
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

  // The computer panel belongs to one bot; groups don't have one.
  useEffect(() => { if (isGroupKey(activeId)) setSide(null); setTakeover(false); }, [activeId]);

  const select = (id: string) => {
    set(() => ({ activeId: id }));
    setDrawer(false);
    try { localStorage.setItem("gbot.active", id); } catch { /* storage blocked */ }
  };

  const sidebar = (
    <Sidebar
      activeId={activeId}
      onSelect={select}
      onHatch={() => { setPanel("hatch"); setDrawer(false); }}
      onSettings={() => { setPanel("settings"); setDrawer(false); }}
      onSearch={() => setPanel("search")}
      onNewGroup={() => { setPanel("newGroup"); setDrawer(false); }}
    />
  );

  const sidePanel = activeBot && side && (
    <SidePanel bot={activeBot} tab={side} onTab={setSide} onClose={() => setSide(null)} takeover={takeover} setTakeover={setTakeover} />
  );

  return (
    <div className="h-full flex">
      {wide ? (
        <aside className="w-[272px] shrink-0 h-full hairline-r">{sidebar}</aside>
      ) : (
        <AnimatePresence>
          {drawer && (
            <>
              <motion.div className="fixed inset-0 bg-black/30 z-30" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={() => setDrawer(false)} />
              <motion.aside className="fixed inset-y-0 left-0 w-[86%] max-w-[300px] z-40 shadow-pop"
                initial={{ x: "-100%" }} animate={{ x: 0 }} exit={{ x: "-100%" }} transition={spring}>
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
        <AnimatePresence initial={false}>
          {sidePanel && (
            <motion.aside key="side" className="h-full hairline-l overflow-hidden shrink-0"
              initial={{ width: 0 }} animate={{ width: 420 }} exit={{ width: 0 }} transition={spring}>
              <div className="w-[420px] h-full">{sidePanel}</div>
            </motion.aside>
          )}
        </AnimatePresence>
      ) : (
        <AnimatePresence>
          {sidePanel && (
            <>
              <motion.div className="fixed inset-0 bg-black/30 z-30" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={() => setSide(null)} />
              <motion.aside className="fixed inset-y-0 right-0 w-full max-w-[440px] z-40 shadow-pop"
                initial={{ x: "100%" }} animate={{ x: 0 }} exit={{ x: "100%" }} transition={spring}>
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
      <Settings open={panel === "settings"} onClose={() => setPanel(null)} />
      <CommandK open={panel === "search"} onClose={() => setPanel(null)} onPick={(id) => { setPanel(null); select(id); }} onHatch={() => setPanel("hatch")} />
    </div>
  );
}

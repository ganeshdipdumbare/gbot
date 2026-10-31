import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { connectLive, loadBots, set, useStore } from "../lib/store";
import { useMediaQuery } from "../lib/hooks";
import { Sidebar } from "./Sidebar";
import { Chat } from "./Chat";
import { Hatch } from "./Hatch";
import { BotSettings } from "./BotSettings";
import { Settings } from "./Settings";
import { CommandK } from "./CommandK";
import { spring } from "./ui";

export type Panel = null | "hatch" | "bot" | "settings" | "search";

export function Shell() {
  const [panel, setPanel] = useState<Panel>(null);
  const [drawer, setDrawer] = useState(false);
  const wide = useMediaQuery("(min-width: 820px)");
  const activeId = useStore((s) => s.activeId);

  useEffect(() => {
    loadBots().catch(() => {});
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
    />
  );

  return (
    <div className="h-full flex">
      {wide ? (
        <aside className="w-[300px] shrink-0 h-full border-r border-line">{sidebar}</aside>
      ) : (
        <AnimatePresence>
          {drawer && (
            <>
              <motion.div className="fixed inset-0 bg-black/30 z-30" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={() => setDrawer(false)} />
              <motion.aside
                className="fixed inset-y-0 left-0 w-[86%] max-w-[320px] bg-bg z-40 shadow-pop"
                initial={{ x: "-100%" }} animate={{ x: 0 }} exit={{ x: "-100%" }} transition={spring}
              >
                {sidebar}
              </motion.aside>
            </>
          )}
        </AnimatePresence>
      )}
      <main className="flex-1 min-w-0 h-full">
        <Chat key={activeId ?? "none"} botId={activeId} onMenu={wide ? undefined : () => setDrawer(true)} onSettings={() => setPanel("bot")} onHatch={() => setPanel("hatch")} />
      </main>

      <Hatch open={panel === "hatch"} onClose={() => setPanel(null)} onCreated={(id) => { setPanel(null); select(id); }} />
      <BotSettings open={panel === "bot"} botId={activeId} onClose={() => setPanel(null)} />
      <Settings open={panel === "settings"} onClose={() => setPanel(null)} />
      <CommandK open={panel === "search"} onClose={() => setPanel(null)} onPick={(id) => { setPanel(null); select(id); }} onHatch={() => setPanel("hatch")} />
    </div>
  );
}


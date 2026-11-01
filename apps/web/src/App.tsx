import { lazy, Suspense, useEffect, useState } from "react";
import { api, ApiError } from "./lib/api";
import { get, set, useStore } from "./lib/store";
import { Welcome } from "./components/Welcome";
import { Shell } from "./components/Shell";
import { Gbot } from "@gbot/characters";
import { ErrorBoundary, Toaster } from "./components/Feedback";
import { toast } from "./lib/toast";

const Gallery = lazy(() => import("./components/Gallery"));

export function App() {
  if (location.pathname === "/gallery") return <Suspense><Gallery /></Suspense>;
  return (
    <ErrorBoundary>
      <Main />
      <Toaster />
    </ErrorBoundary>
  );
}

function Main() {
  const me = useStore((s) => s.me);
  const [booting, setBooting] = useState(true);
  const [offline, setOffline] = useState(false);

  // A session that expired while open signs out cleanly.
  useEffect(() => {
    const out = () => {
      if (!get().me) return;
      set(() => ({ me: null, bots: [], groups: [], messages: {}, presence: {}, unread: {}, loaded: false }));
      toast("Your session ended. Sign in again.");
    };
    window.addEventListener("gbot:signed-out", out);
    return () => window.removeEventListener("gbot:signed-out", out);
  }, []);

  // Unread replies show in the tab title.
  const unread = useStore((s) => Object.values(s.unread).reduce((a, b) => a + b, 0));
  useEffect(() => { document.title = unread ? `(${unread}) gbot` : "gbot"; }, [unread]);

  useEffect(() => {
    api.me()
      .then((u) => set(() => ({ me: u })))
      .catch((e) => { if (!(e instanceof ApiError)) setOffline(true); })
      .finally(() => setBooting(false));
  }, []);

  if (booting) {
    return (
      <div className="h-full grid place-items-center fade-in">
        <Gbot seed="boot" look={{ shape: "circle", hue: "grape", eyes: "pill" }} mood="thinking" size={40} />
      </div>
    );
  }
  if (offline) {
    return (
      <div className="h-full grid place-items-center p-6 text-center">
        <div>
          <Gbot seed="offline" look={{ shape: "square", hue: "sky", eyes: "dot" }} mood="blocked" size={56} className="mx-auto" />
          <h1 className="text-[15px] font-semibold mt-4">Can't reach the gbot server</h1>
          <p className="text-[13px] text-ink-2 mt-1">Start it with <code className="bg-surface-2 px-1.5 py-0.5 rounded-md font-mono text-[12px]">gbot serve</code>, then try again.</p>
          <button onClick={() => location.reload()} className="mt-4 h-8 px-3.5 rounded-full bg-emphasis text-on-emphasis text-[13px] font-medium cursor-pointer">Try again</button>
        </div>
      </div>
    );
  }
  return me ? <Shell /> : <Welcome />;
}

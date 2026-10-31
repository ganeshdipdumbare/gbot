import { lazy, Suspense, useEffect, useState } from "react";
import { api, ApiError } from "./lib/api";
import { set, useStore } from "./lib/store";
import { Welcome } from "./components/Welcome";
import { Shell } from "./components/Shell";
import { Gbot } from "@gbot/characters";

const Gallery = lazy(() => import("./components/Gallery"));

export function App() {
  if (location.pathname === "/gallery") return <Suspense><Gallery /></Suspense>;
  return <Main />;
}

function Main() {
  const me = useStore((s) => s.me);
  const [booting, setBooting] = useState(true);
  const [offline, setOffline] = useState(false);

  useEffect(() => {
    api.me()
      .then((u) => set(() => ({ me: u })))
      .catch((e) => { if (!(e instanceof ApiError)) setOffline(true); })
      .finally(() => setBooting(false));
  }, []);

  if (booting) {
    return (
      <div className="h-full grid place-items-center">
        <Gbot seed="boot" look={{ shape: "circle", hue: "grape", eyes: "pill" }} mood="thinking" size={88} />
      </div>
    );
  }
  if (offline) {
    return (
      <div className="h-full grid place-items-center p-6 text-center">
        <div>
          <Gbot seed="offline" look={{ shape: "square", hue: "sky", eyes: "dot" }} mood="blocked" size={110} className="mx-auto" />
          <h1 className="font-display font-black text-2xl mt-4">Can't reach the gbot server</h1>
          <p className="text-ink-2 mt-2">Start it with <code className="bg-surface-2 px-2 py-0.5 rounded-lg">gbot serve</code>, then refresh.</p>
        </div>
      </div>
    );
  }
  return me ? <Shell /> : <Welcome />;
}

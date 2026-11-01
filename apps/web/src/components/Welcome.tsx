import { useEffect, useState, type FormEvent } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Gbot, type Look, type Mood } from "@gbot/characters";
import { api } from "../lib/api";
import { set } from "../lib/store";
import { useGaze } from "../lib/hooks";
import { bouncy, Button, Input } from "./ui";

const crew: { look: Look; x: number; size: number; delay: number }[] = [
  { look: { shape: "circle", hue: "grape", eyes: "pill" }, x: 0, size: 112, delay: 0 },
  { look: { shape: "triangle", hue: "coral", eyes: "dot" }, x: -124, size: 88, delay: 0.08 },
  { look: { shape: "hexagon", hue: "cobalt", eyes: "pill" }, x: 124, size: 88, delay: 0.16 },
  { look: { shape: "cloud", hue: "sun", eyes: "wide" }, x: -220, size: 68, delay: 0.24 },
  { look: { shape: "square", hue: "mint", eyes: "dot" }, x: 220, size: 68, delay: 0.32 },
];

function Crew({ mood }: { mood: Mood }) {
  const [ref, gaze] = useGaze<HTMLDivElement>();
  return (
    <div ref={ref} className="relative h-[150px] w-full max-w-[520px] mx-auto origin-bottom scale-[0.64] sm:scale-100">
      {crew.map((c, i) => (
        <motion.div
          key={i}
          className="absolute left-1/2 bottom-0"
          style={{ marginLeft: c.x - c.size / 2, zIndex: 10 - i }}
          initial={{ y: 40, scale: 0.4, opacity: 0 }}
          animate={{ y: 0, scale: 1, opacity: 1 }}
          transition={{ ...bouncy, delay: 0.15 + c.delay }}
        >
          <Gbot look={c.look} seed={"crew" + i} size={c.size} lookAt={gaze} mood={i === 0 ? mood : mood === "happy" ? "happy" : "idle"} />
        </motion.div>
      ))}
    </div>
  );
}

export function Welcome() {
  const [mode, setMode] = useState<"signup" | "login">("signup");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [mood, setMood] = useState<Mood>("idle");

  useEffect(() => {
    api.setup().then((s) => setMode(s.fresh ? "signup" : "login")).catch(() => {});
  }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    setMood("thinking");
    try {
      const u = mode === "signup" ? await api.signup({ email, password, name }) : await api.login({ email, password });
      setMood("happy");
      setTimeout(() => set(() => ({ me: u })), 650);
    } catch (err) {
      setError((err as Error).message);
      setMood("blocked");
      setTimeout(() => setMood("idle"), 1800);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="min-h-full flex flex-col items-center justify-center px-4 py-10">
      <Crew mood={mood} />
      <motion.div
        initial={{ y: 24, opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        transition={{ ...bouncy, delay: 0.45 }}
        className="w-full max-w-[400px] mt-6"
      >
        <h1 className="font-semibold tracking-[-0.3px] text-[34px] leading-tight text-center tracking-tight">
          {mode === "signup" ? "Meet your teammates" : "Welcome back!"}
        </h1>
        <p className="text-center text-ink-2 mt-2 text-[14px]">
          Little AI friends that remember, work and grow with you.
          <br />Running 100% on your own machine.
        </p>
        <form onSubmit={submit} className="mt-7 space-y-3 bg-surface rounded-2xl p-5 hairline border border-line">
          {mode === "signup" && (
            <Input placeholder="Your name" value={name} onChange={(e) => setName(e.target.value)} autoComplete="name" />
          )}
          <Input type="email" required placeholder="Email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" onFocus={() => setMood("waiting")} onBlur={() => setMood("idle")} />
          <Input type="password" required minLength={10} placeholder={mode === "signup" ? "Password (10+ characters)" : "Password"} value={password}
            onChange={(e) => setPassword(e.target.value)} autoComplete={mode === "signup" ? "new-password" : "current-password"}
            onFocus={() => setMood("sleepy")} onBlur={() => setMood("idle")} />
          <AnimatePresence>
            {error && (
              <motion.p initial={{ opacity: 0, y: -4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} className="text-danger text-[14px] px-1" role="alert">
                {error}
              </motion.p>
            )}
          </AnimatePresence>
          <Button type="submit" disabled={busy} className="w-full h-12 text-[14px]">
            {mode === "signup" ? "Create account" : "Sign in"}
          </Button>
        </form>
        <p className="text-center text-[14px] text-ink-2 mt-4">
          {mode === "signup" ? "Already have an account? " : "New here? "}
          <button className="font-bold text-ink underline underline-offset-2 cursor-pointer" onClick={() => { setMode(mode === "signup" ? "login" : "signup"); setError(""); }}>
            {mode === "signup" ? "Sign in" : "Create an account"}
          </button>
        </p>
      </motion.div>
    </div>
  );
}

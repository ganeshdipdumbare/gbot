import { useEffect, useState, type FormEvent } from "react";
import { Gbot, type Look, type Mood } from "@gbot/characters";
import { api } from "../lib/api";
import { set } from "../lib/store";
import { useGaze } from "../lib/hooks";
import { Button, Input, Label, PasswordInput } from "./ui";

const crew: { look: Look; seed: string }[] = [
  { look: { shape: "triangle", hue: "coral", eyes: "dot" }, seed: "a" },
  { look: { shape: "circle", hue: "grape", eyes: "pill" }, seed: "b" },
  { look: { shape: "hexagon", hue: "cobalt", eyes: "pill" }, seed: "c" },
];

export function Welcome() {
  const [mode, setMode] = useState<"signup" | "login">("login");
  const [canSignUp, setCanSignUp] = useState(false);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  // Set when the server requires its setup token to create the first account.
  const [needsToken, setNeedsToken] = useState(false);
  const [setupToken, setSetupToken] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [mood, setMood] = useState<Mood>("idle");
  const [ref, gaze] = useGaze<HTMLDivElement>();

  useEffect(() => {
    api.setup().then((s) => { setCanSignUp(s.signup); setNeedsToken(!!s.setupToken); setMode(s.fresh ? "signup" : "login"); }).catch(() => {});
  }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    setMood("thinking");
    try {
      const u = mode === "signup"
        ? await api.signup({ email, password, name, setupToken: needsToken ? setupToken : undefined })
        : await api.login({ email, password });
      setMood("happy");
      setTimeout(() => set(() => ({ me: u })), 280);
    } catch (err) {
      setError((err as Error).message);
      setMood("blocked");
      setTimeout(() => setMood("idle"), 1600);
    } finally {
      setBusy(false);
    }
  }

  const signup = mode === "signup";

  return (
    <div className="min-h-full grid place-items-center px-4 py-12">
      <main className="w-full max-w-[360px] entry-in">
        <div ref={ref} className="flex justify-center items-end gap-2 mb-7" aria-hidden>
          {crew.map((c, i) => (
            <Gbot key={c.seed} look={c.look} seed={c.seed} size={i === 1 ? 92 : 68} lookAt={gaze} mood={i === 1 ? mood : mood === "happy" ? "happy" : "idle"} />
          ))}
        </div>
        <h1 className="text-[22px] font-semibold tracking-[-0.4px] text-center">{signup ? "Create your gbot account" : "Sign in to gbot"}</h1>
        <p className="text-[14px] text-ink-2 text-center mt-1.5">AI teammates that run on your own machine.</p>

        <form onSubmit={submit} className="mt-7 space-y-3" noValidate={false}>
          {signup && (
            <div>
              <Label>Name</Label>
              <Input value={name} onChange={(e) => setName(e.target.value)} autoComplete="name" />
            </div>
          )}
          {signup && needsToken && (
            <div>
              <Label hint="Set by the server owner as GBOT_SETUP_TOKEN">Setup token</Label>
              <Input value={setupToken} onChange={(e) => setSetupToken(e.target.value)} autoComplete="off" className="h-10!" />
            </div>
          )}
          <div>
            <Label>Email</Label>
            <Input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" autoFocus className="h-10!" />
          </div>
          <div>
            <Label hint={signup ? "10+ characters" : undefined}>Password</Label>
            <PasswordInput required minLength={signup ? 10 : 1} value={password} onChange={(e) => setPassword(e.target.value)}
              autoComplete={signup ? "new-password" : "current-password"} className="h-10!" />
          </div>
          {error && <p className="text-[13px] text-danger" role="alert">{error}</p>}
          <Button type="submit" disabled={busy || !email || !password} className="w-full h-10! text-[14px]! mt-1">
            {busy ? (signup ? "Creating…" : "Signing in…") : signup ? "Create account" : "Sign in"}
          </Button>
        </form>

        {canSignUp && (
          <p className="text-[13px] text-ink-3 text-center mt-5">
            {signup ? "Already have an account?" : "New here?"}{" "}
            <button className="text-ink hover:underline underline-offset-2 cursor-pointer" onClick={() => { setMode(signup ? "login" : "signup"); setError(""); }}>
              {signup ? "Sign in" : "Create an account"}
            </button>
          </p>
        )}
      </main>
    </div>
  );
}

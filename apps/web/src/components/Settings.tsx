import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Gbot } from "@gbot/characters";
import { api, type ApprovalMode, type MCPServer, type Provider, type ProviderKind, type Skill, type ToolRule, type VoiceInfo } from "../lib/api";
import { set, useStore } from "../lib/store";
import { voiceInfo } from "../lib/voice";
import { attempt, toast } from "../lib/toast";
import { Modal } from "./Modal";
import { ModelSelect, useModels } from "./ModelSelect";
import { invalidateSkills } from "./Composer";
import { Toggle } from "./SidePanel";
import { TeamSection } from "./Sharing";
import { bouncy, Button, Icon, Input, Label, leave, spring, TextArea } from "./ui";

type Tab = "general" | "providers" | "team" | "integrations" | "skills" | "voice";
const tabs: { id: Tab; label: string }[] = [
  { id: "general", label: "General" },
  { id: "providers", label: "Providers" },
  { id: "team", label: "Team" },
  { id: "integrations", label: "Integrations" },
  { id: "skills", label: "Skills" },
  { id: "voice", label: "Voice" },
];

export function Settings({ open, onClose, initialTab = "general" }: { open: boolean; onClose: () => void; initialTab?: Tab }) {
  const [tab, setTab] = useState<Tab>(initialTab);
  useEffect(() => { if (open) setTab(initialTab); }, [open, initialTab]);
  return (
    <Modal open={open} onClose={onClose} title="Settings" width={600}>
      <div className="px-5">
        <div className="flex gap-1 border-b border-line -mx-5 px-5 pb-2 overflow-x-auto">
          {tabs.map((t) => (
            <button key={t.id} onClick={() => setTab(t.id)}
              className={`relative h-9 px-3.5 rounded-full text-[14px] font-semibold cursor-pointer whitespace-nowrap transition-colors ${tab === t.id ? "text-ink" : "text-ink-2 hover:text-ink"}`}>
              {tab === t.id && <motion.span layoutId="settings-tab" className="absolute inset-0 rounded-full bg-surface-2" transition={bouncy} />}
              <span className="relative">{t.label}</span>
            </button>
          ))}
        </div>
      </div>
      <div className="px-5 py-5 min-h-[420px]">
        {tab === "general" && <General />}
        {tab === "providers" && <Providers />}
        {tab === "team" && <TeamSection />}
        {tab === "integrations" && <Integrations />}
        {tab === "skills" && <Skills />}
        {tab === "voice" && <Voice />}
      </div>
    </Modal>
  );
}

const modes: { id: ApprovalMode; label: string; blurb: string }[] = [
  { id: "ask", label: "Ask", blurb: "Ask before anything that changes something." },
  { id: "auto", label: "Auto", blurb: "Ask only for risky steps, or once outside content is involved." },
  { id: "never", label: "Never ask", blurb: "Run everything without waiting. Blocked tools stay blocked." },
];

function General() {
  const me = useStore((s) => s.me);
  const models = useModels();
  const [error, setError] = useState("");
  const [rules, setRules] = useState<ToolRule[]>([]);
  useEffect(() => { api.rules().then((r) => setRules(r.rules)).catch(() => {}); }, []);
  const hasLocal = models?.some((m) => m.local);

  const update = async (b: Parameters<typeof api.updateMe>[0]) => {
    try { const u = await api.updateMe(b); set(() => ({ me: u })); setError(""); } catch (e) { setError((e as Error).message); }
  };

  return (
    <div className="space-y-6">
      <section>
        <Label hint="Bots use this unless you pick one for them">Default model</Label>
        <ModelSelect value={me?.defaultModel ?? ""} onChange={(v) => update({ defaultModel: v })} allowDefault="Best available" />
        {models && !hasLocal && (
          <div className="mt-3 rounded-[10px] bg-surface-2 p-4 flex gap-3">
            <Gbot seed="plug" look={{ shape: "pill", hue: "sky", eyes: "dot" }} mood="waiting" size={52} />
            <div className="text-[14px]">
              <div className="font-semibold">Connect a model provider</div>
              <p className="text-ink-2 mt-1">Run <a className="underline" href="https://ollama.com" target="_blank" rel="noreferrer">Ollama</a>, LM Studio, vLLM or any OpenAI-compatible server, or add Anthropic or Gemini in the Providers tab. For Ollama, pull a small model with tools:</p>
              <code className="block mt-2 bg-surface rounded-xl px-3 py-2 text-[13px]">ollama pull qwen3.5:4b</code>
            </div>
          </div>
        )}
      </section>

      <section>
        <Label>Approvals</Label>
        <div className="grid grid-cols-3 gap-1 p-1 rounded-full bg-surface-2">
          {modes.map((m) => (
            <button key={m.id} onClick={() => update({ approvalMode: m.id })}
              className={`relative h-9 rounded-full text-[14px] font-semibold cursor-pointer ${me?.approvalMode === m.id ? "text-ink" : "text-ink-2"}`}>
              {me?.approvalMode === m.id && <motion.span layoutId="mode" className="absolute inset-0 rounded-full bg-surface hairline" transition={bouncy} />}
              <span className="relative">{m.label}</span>
            </button>
          ))}
        </div>
        <p className="text-[13px] text-ink-2 mt-2">{modes.find((m) => m.id === me?.approvalMode)?.blurb}</p>
        {rules.length > 0 && (
          <ul className="mt-3 divide-y divide-line rounded-2xl border border-line">
            {rules.map((r) => (
              <li key={r.tool} className="flex items-center gap-3 px-3 py-2">
                <span className={`text-[11px] font-bold uppercase tracking-wide w-12 ${r.rule === "allow" ? "text-emerald-600 dark:text-emerald-400" : "text-danger"}`}>{r.rule}</span>
                <code className="flex-1 text-[13px] truncate">{r.tool.replace(/^m[0-9a-f]{8}__/, "")}</code>
                <button className="text-ink-2 hover:text-ink cursor-pointer" aria-label={`Remove rule for ${r.tool}`}
                  onClick={async () => { const r2 = await attempt(() => api.setRule(r.tool, ""), "Rule removed"); if (r2) setRules(r2.rules); }}><Icon.close /></button>
              </li>
            ))}
          </ul>
        )}
        <p className="text-[12.5px] text-ink-2 mt-2">“Always allow” on an approval card adds a rule here.</p>
      </section>

      {error && <p className="text-danger text-sm">{error}</p>}
      <section className="flex items-center justify-between border-t border-line pt-4">
        <div className="text-[13px] text-ink-2">Signed in as <span className="font-semibold text-ink">{me?.email}</span></div>
        <Button variant="ghost" onClick={async () => { await api.logout(); set(() => ({ me: null, bots: [], groups: [], messages: {}, presence: {} })); }}>
          <Icon.logout /> Sign out
        </Button>
      </section>
    </div>
  );
}

type ProviderEdit = {
  id?: string; name: string; kind: ProviderKind; baseUrl: string;
  apiKey: string; removeKey: boolean; local: boolean; modelsText: string;
};

const providerKinds: { id: ProviderKind; label: string; hint: string; placeholder: string }[] = [
  { id: "openai", label: "OpenAI-compatible", hint: "vLLM, LM Studio, llama.cpp, LiteLLM…", placeholder: "http://localhost:1234/v1" },
  { id: "anthropic", label: "Anthropic", hint: "Claude models", placeholder: "https://api.anthropic.com" },
  { id: "gemini", label: "Gemini", hint: "Google AI Studio", placeholder: "https://generativelanguage.googleapis.com/v1beta" },
];

const blankProvider = (): ProviderEdit => ({
  name: "", kind: "openai", baseUrl: "", apiKey: "", removeKey: false, local: true, modelsText: "",
});

function Providers() {
  const [list, setList] = useState<Provider[] | null>(null);
  const [edit, setEdit] = useState<ProviderEdit | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = () => api.providers().then((r) => setList(r.providers)).catch(() => setList([]));
  useEffect(() => { load(); }, []);
  const refreshModels = () => api.models().then((r) => set(() => ({ models: r.models }))).catch(() => {});

  function start(p?: Provider) {
    setError(""); setNotice("");
    if (!p) {
      setEdit(blankProvider());
      return;
    }
    setEdit({
      id: p.id, name: p.name, kind: p.kind, baseUrl: p.baseUrl, apiKey: "",
      removeKey: false, local: p.local, modelsText: p.models.join("\n"),
    });
  }

  async function save() {
    if (!edit) return;
    setBusy(true); setError(""); setNotice("");
    const models = edit.modelsText.split(/[\n,]/).map((m) => m.trim()).filter(Boolean);
    const body = { name: edit.name.trim(), kind: edit.kind, baseUrl: edit.baseUrl.trim(), local: edit.local, models };
    try {
      if (edit.id) await api.updateProvider(edit.id, { ...body, apiKey: edit.apiKey || undefined, removeKey: edit.removeKey });
      else await api.createProvider({ ...body, apiKey: edit.apiKey || undefined });
      toast(edit.id ? "Provider updated" : "Provider connected");
      setEdit(null);
      load(); refreshModels();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }

  async function discover() {
    if (!edit) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const r = await api.discoverProvider({ id: edit.id, kind: edit.kind, baseUrl: edit.baseUrl.trim(), apiKey: edit.apiKey || undefined });
      setEdit({ ...edit, modelsText: r.models.join("\n") });
      setNotice(r.models.length ? `Found ${r.models.length} models.` : "No models returned.");
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }

  if (edit) {
    const kind = providerKinds.find((k) => k.id === edit.kind)!;
    return (
      <div className="space-y-3">
        <div className="grid sm:grid-cols-2 gap-3">
          <div><Label>Name</Label><Input value={edit.name} onChange={(e) => setEdit({ ...edit, name: e.target.value })} placeholder="My GPU box" /></div>
          <div>
            <Label>Protocol</Label>
            <select value={edit.kind} onChange={(e) => setEdit({ ...edit, kind: e.target.value as ProviderKind, baseUrl: "" })}
              className="w-full h-9 rounded-[10px] bg-surface hairline px-3 text-[14px] text-ink outline-none focus:shadow-[inset_0_0_0_1px_var(--accent)] cursor-pointer">
              {providerKinds.map((k) => <option key={k.id} value={k.id}>{k.label}</option>)}
            </select>
          </div>
        </div>
        <div>
          <Label hint={kind.hint}>Endpoint</Label>
          <Input value={edit.baseUrl} onChange={(e) => setEdit({ ...edit, baseUrl: e.target.value })} placeholder={kind.placeholder} />
        </div>
        <div className="grid sm:grid-cols-2 gap-3">
          <div>
            <Label hint={edit.id && !edit.removeKey ? "leave blank to keep" : "optional for local servers"}>API key</Label>
            <Input type="password" value={edit.apiKey} onChange={(e) => setEdit({ ...edit, apiKey: e.target.value })}
              placeholder={edit.id ? "••••••••" : "sk-…"} autoComplete="off" disabled={edit.removeKey} />
          </div>
          <div className="flex flex-col justify-end gap-2 pb-1">
            <label className="flex items-center gap-2 text-[13px] text-ink-2 cursor-pointer">
              <input type="checkbox" checked={edit.local} onChange={(e) => setEdit({ ...edit, local: e.target.checked })} />
              Runs on this machine
            </label>
            {edit.id && (
              <label className="flex items-center gap-2 text-[13px] text-ink-2 cursor-pointer">
                <input type="checkbox" checked={edit.removeKey} onChange={(e) => setEdit({ ...edit, removeKey: e.target.checked, apiKey: "" })} />
                Remove stored key
              </label>
            )}
          </div>
        </div>
        <div>
          <Label hint="one per line or comma separated; used when discovery can't reach the endpoint">Models</Label>
          <TextArea rows={5} className="font-mono text-[13px]!" value={edit.modelsText}
            onChange={(e) => setEdit({ ...edit, modelsText: e.target.value })} placeholder={"llama3.3:70b\nqwen3.5:4b"} />
        </div>
        {notice && <p className="text-live text-[13px]">{notice}</p>}
        {error && <p className="text-danger text-[13px]">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => setEdit(null)}>Cancel</Button>
          <Button variant="soft" onClick={discover} disabled={busy}>{busy ? "Checking…" : "Discover models"}</Button>
          <Button onClick={save} disabled={busy || !edit.name.trim()}>{busy ? "Saving…" : edit.id ? "Save" : "Connect"}</Button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-start gap-3">
        <div className="flex-1">
          <div className="font-semibold text-[14px]">Model providers</div>
          <p className="text-[13px] text-ink-2">Connect any OpenAI-compatible server, Anthropic or Gemini. Keys are encrypted and only your account can use the models.</p>
        </div>
        <Button variant="soft" onClick={() => start()}><Icon.plus /> Add</Button>
      </div>
      {list?.length === 0 && <Empty text="No providers yet." />}
      <ul className="space-y-2">
        {list?.map((p) => {
          const status = p.status === "connected" ? "text-live" : p.status === "off" ? "text-ink-2" : "text-danger";
          return (
            <li key={p.id} className="rounded-2xl border border-line p-3">
              <div className="flex items-center gap-3">
                <span className={p.enabled ? "text-live" : "text-ink-2"}><Icon.plug /></span>
                <button className="min-w-0 flex-1 text-left cursor-pointer" onClick={() => start(p)}>
                  <div className="font-semibold text-[14px]">{p.name} <span className="text-[11px] font-bold uppercase tracking-wide text-ink-3">{p.kind}</span></div>
                  <div className="text-[12.5px] text-ink-2 truncate">{p.baseUrl}</div>
                </button>
                <Toggle on={p.enabled} label={p.enabled ? "Turn off" : "Turn on"}
                  onChange={async (v) => { await attempt(() => api.updateProvider(p.id, { enabled: v }), v ? `${p.name} on` : `${p.name} off`); load(); refreshModels(); }} />
                <button className="text-ink-2 hover:text-ink cursor-pointer" aria-label={`Refresh ${p.name} models`}
                  onClick={async () => { await attempt(() => api.refreshProvider(p.id), "Models refreshed"); load(); refreshModels(); }}><Icon.spark /></button>
                <button className="text-ink-2 hover:text-danger cursor-pointer" aria-label={`Remove ${p.name}`}
                  onClick={async () => { await attempt(() => api.deleteProvider(p.id), `${p.name} removed`); load(); refreshModels(); }}><Icon.trash /></button>
              </div>
              <div className="mt-2 text-[12.5px]">
                <span className={status}>{p.status === "connected" ? `${p.models.length} models` : p.status}</span>
                {p.models.length > 0 && <span className="text-ink-2">: {p.models.slice(0, 6).join(", ")}{p.models.length > 6 ? "…" : ""}</span>}
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function Integrations() {
  const [list, setList] = useState<MCPServer[] | null>(null);
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [token, setToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const load = () => api.mcp().then((r) => setList(r.servers)).catch(() => setList([]));
  useEffect(() => { load(); }, []);
  // Servers connect in the background; poll while any is still connecting
  // instead of leaving the row stuck on the first response.
  useEffect(() => {
    if (!list?.some((m) => m.status === "connecting" || (m.status === "off" && m.enabled))) return;
    const t = window.setInterval(load, 700);
    return () => window.clearInterval(t);
  }, [list]); // eslint-disable-line react-hooks/exhaustive-deps

  async function add() {
    setBusy(true);
    setError("");
    try {
      await api.addMcp({ name, url, token: token || undefined });
      setAdding(false); setName(""); setUrl(""); setToken("");
      load();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-start gap-3">
        <div className="flex-1">
          <div className="font-semibold text-[14px]">MCP servers</div>
          <p className="text-[13px] text-ink-2">Connect tools over the Model Context Protocol. Only your bots can use what you add, and results count as outside content.</p>
        </div>
        {!adding && <Button variant="soft" onClick={() => setAdding(true)}><Icon.plus /> Add</Button>}
      </div>
      <AnimatePresence initial={false}>
        {adding && (
          <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0, transition: leave }} transition={spring} className="overflow-hidden">
            <div className="rounded-2xl border border-line p-3 space-y-3">
              <div className="grid sm:grid-cols-2 gap-3">
                <div><Label>Name</Label><Input value={name} onChange={(e) => setName(e.target.value)} placeholder="GitHub" /></div>
                <div><Label hint="optional">Token</Label><Input type="password" value={token} onChange={(e) => setToken(e.target.value)} placeholder="Sent as a Bearer token" autoComplete="off" /></div>
              </div>
              <div><Label>URL</Label><Input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://mcp.example.com/mcp" /></div>
              <p className="text-[12.5px] text-ink-2">Tokens are encrypted on this server and never shown to bots.</p>
              {error && <p className="text-danger text-[13px]">{error}</p>}
              <div className="flex justify-end gap-2">
                <Button variant="ghost" onClick={() => setAdding(false)}>Cancel</Button>
                <Button onClick={add} disabled={busy || !name.trim() || !url.trim()}>{busy ? "Connecting…" : "Connect"}</Button>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
      {list?.length === 0 && !adding && <Empty text="No integrations yet." />}
      <ul className="space-y-2">
        {list?.map((m) => (
          <li key={m.id} className="rounded-2xl border border-line p-3">
            <div className="flex items-center gap-3">
              <span className={m.status === "connected" ? "text-live" : "text-ink-2"}><Icon.plug /></span>

              <div className="min-w-0 flex-1">
                <div className="font-semibold text-[14px]">{m.name}</div>
                <div className="text-[12.5px] text-ink-2 truncate">{m.url}</div>
              </div>
              <Toggle on={m.enabled} label={m.enabled ? "Turn off" : "Turn on"} onChange={async (v) => { await attempt(() => api.toggleMcp(m.id, v), v ? `${m.name} on` : `${m.name} off`); load(); }} />
              <button className="text-ink-2 hover:text-danger cursor-pointer" aria-label={`Remove ${m.name}`} onClick={async () => { await attempt(() => api.deleteMcp(m.id), `${m.name} removed`); load(); }}><Icon.trash /></button>
            </div>
            <div className="mt-2 text-[12.5px]">
              {m.status === "connected" ? (
                <span className="text-ink-2">{m.tools.length} tools: {m.tools.map((t) => t.replace(/^m[0-9a-f]{8}__/, "")).slice(0, 6).join(", ")}{m.tools.length > 6 ? "…" : ""}</span>
              ) : m.enabled ? (
                <span className={m.status === "connecting" || m.status === "off" ? "text-ink-2" : "text-danger"}>
                  {m.status === "connecting" || m.status === "off" ? "Connecting…" : m.status}
                </span>
              ) : <span className="text-ink-2">Off</span>}
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}

function parseSkillMd(text: string): Partial<Skill> {
  const m = /^---\n([\s\S]*?)\n---\n?([\s\S]*)$/.exec(text.trim());
  if (!m) return { body: text };
  const meta = Object.fromEntries(m[1].split("\n").map((l) => l.split(/:\s*/, 2)).filter((p) => p.length === 2)) as Record<string, string>;
  return { name: meta.name, description: meta.description, body: m[2].trim() };
}

function Skills() {
  const [list, setList] = useState<Skill[] | null>(null);
  const [edit, setEdit] = useState<Partial<Skill> | null>(null);
  const [error, setError] = useState("");
  const load = () => api.skills().then((r) => setList(r.skills)).catch(() => setList([]));
  useEffect(() => { load(); }, []);

  async function save() {
    setError("");
    try {
      await api.saveSkill({ name: edit!.name ?? "", description: edit!.description ?? "", body: edit!.body ?? "" });
      invalidateSkills();
      toast("Skill saved");
      setEdit(null);
      load();
    } catch (e) { setError((e as Error).message); }
  }

  if (edit) {
    return (
      <div className="space-y-3">
        <div className="grid sm:grid-cols-2 gap-3">
          <div><Label>Name</Label><Input value={edit.name ?? ""} onChange={(e) => setEdit({ ...edit, name: e.target.value })} placeholder="weekly-report" /></div>
          <div><Label>When to use it</Label><Input value={edit.description ?? ""} onChange={(e) => setEdit({ ...edit, description: e.target.value })} placeholder="Writing the Friday team update" /></div>
        </div>
        <div>
          <Label hint="Markdown, or paste a SKILL.md">Instructions</Label>
          <TextArea rows={12} className="font-mono text-[13px]!" value={edit.body ?? ""}
            onChange={(e) => { const p = parseSkillMd(e.target.value); setEdit({ ...edit, ...(p.name ? p : { body: e.target.value }) }); }} />
        </div>
        {error && <p className="text-danger text-[13px]">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => setEdit(null)}>Cancel</Button>
          <Button onClick={save} disabled={!edit.name?.trim() || !edit.body?.trim()}>Save skill</Button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-start gap-3">
        <div className="flex-1">
          <div className="font-semibold text-[14px]">Skills</div>
          <p className="text-[13px] text-ink-2">Know-how your bots load when a task needs it. Type / in a chat to use one.</p>
        </div>
        <Button variant="soft" onClick={() => setEdit({})}><Icon.plus /> New</Button>
      </div>
      {list?.length === 0 && <Empty text="No skills yet." />}
      <ul className="space-y-2">
        {list?.map((k) => (
          <li key={k.id} className="rounded-2xl border border-line p-3 flex items-center gap-3">
            <span className="text-live"><Icon.spark /></span>
            <button className="min-w-0 flex-1 text-left cursor-pointer" onClick={() => setEdit(k)}>
              <div className="font-semibold text-[14px]">/{k.name}</div>
              <div className="text-[12.5px] text-ink-2 truncate">{k.description || "No description"}</div>
            </button>
            <button className="text-ink-2 hover:text-danger cursor-pointer" aria-label={`Delete ${k.name}`}
              onClick={async () => { await attempt(() => api.deleteSkill(k.id), "Skill deleted"); invalidateSkills(); load(); }}><Icon.trash /></button>
          </li>
        ))}
      </ul>
    </div>
  );
}

function Voice() {
  const [v, setV] = useState<VoiceInfo | null>(null);
  useEffect(() => { voiceInfo().then(setV); }, []);
  const browserSR = "SpeechRecognition" in window || "webkitSpeechRecognition" in window;
  return (
    <div className="space-y-4 text-[14px]">
      <Row title="Dictation" ok={!!v?.transcribe || browserSR}
        detail={v?.transcribe ? `Transcribed by ${v.transcribeBy}.` : browserSR ? "Using your browser's speech recognition. Some browsers send audio to their vendor." : "Not available in this browser."} />
      <Row title="Read aloud" ok
        detail={v?.speak ? `Spoken by ${v.speakBy}.` : "Using your computer's built-in voices. Pick one per bot in its settings."} />
      <div className="rounded-2xl bg-surface-2 p-4 text-[13px] text-ink-2 leading-relaxed">
        To keep speech entirely on this machine, run an OpenAI-compatible speech server (for example faster-whisper or Kokoro) and set
        <code className="mx-1">GBOT_STT_URL</code>and<code className="mx-1">GBOT_TTS_URL</code>on the gbot server.
      </div>
    </div>
  );
}

function Row({ title, ok, detail }: { title: string; ok: boolean; detail: string }) {
  return (
    <div className="flex items-start gap-3 rounded-2xl border border-line p-3">
      <span className={`mt-0.5 size-5 rounded-full grid place-items-center ${ok ? "bg-emerald-500 text-white" : "bg-line text-ink-2"}`}>{ok ? <Icon.check /> : <Icon.close />}</span>
      <div><div className="font-semibold">{title}</div><div className="text-ink-2 text-[13px]">{detail}</div></div>
    </div>
  );
}

function Empty({ text }: { text: string }) {
  return (
    <div className="text-center py-8">
      <Gbot seed="empty-int" look={{ shape: "cloud", hue: "cream", eyes: "dot" }} mood="sleepy" size={52} className="mx-auto" />
      <p className="text-[13px] text-ink-2 mt-2">{text}</p>
    </div>
  );
}

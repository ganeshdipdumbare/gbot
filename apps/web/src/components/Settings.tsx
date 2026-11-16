import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { motion } from "motion/react";
import { Gbot } from "@gbot/characters";
import { api, type ApprovalMode, type CodexLogin, type MCPServer, type Provider, type ProviderKind, type Skill, type ToolRule, type VaultSecret, type VoiceInfo } from "../lib/api";
import { set, useStore } from "../lib/store";
import { voiceInfo } from "../lib/voice";
import { getTheme, setTheme, themes, type Theme } from "../lib/theme";
import { attempt, toast, toastError } from "../lib/toast";
import { Modal } from "./Modal";
import { ModelSelect, useModels } from "./ModelSelect";
import { ModelList } from "./ModelList";
import { invalidateSkills } from "./Composer";
import { Toggle } from "./SidePanel";
import { TeamSection } from "./Sharing";
import { bouncy, Button, Icon, Input, Label, PasswordInput, TextArea } from "./ui";

type Tab = "general" | "appearance" | "providers" | "team" | "integrations" | "vault" | "skills" | "voice";
export type SettingsTab = Tab;

/** Drop our internal MCP prefix; keep the server's own tool name otherwise. */
function toolLabel(name: string) {
  return name.replace(/^m[0-9a-f]{8}__/, "");
}
const tabs: { id: Tab; label: string }[] = [
  { id: "general", label: "General" },
  { id: "appearance", label: "Appearance" },
  { id: "providers", label: "Models" },
  { id: "team", label: "Team" },
  { id: "integrations", label: "Integrations" },
  { id: "vault", label: "Vault" },
  { id: "skills", label: "Skills" },
  { id: "voice", label: "Voice" },
];

export function Settings({ open, onClose, initialTab = "general" }: { open: boolean; onClose: () => void; initialTab?: Tab }) {
  const [tab, setTab] = useState<Tab>(initialTab);
  useEffect(() => { if (open) setTab(initialTab); }, [open, initialTab]);
  return (
    <Modal open={open} onClose={onClose} title="Settings" width={680}>
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
      <div key={tab} className="px-5 py-5 min-h-[420px] fade-in">
        {tab === "general" && <General />}
        {tab === "appearance" && <Appearance />}
        {tab === "providers" && <Providers />}
        {tab === "team" && <TeamSection />}
        {tab === "integrations" && <Integrations />}
        {tab === "vault" && <Vault />}
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
  const [error, setError] = useState("");
  const [rules, setRules] = useState<ToolRule[]>([]);
  useEffect(() => { api.rules().then((r) => setRules(r.rules)).catch(() => {}); }, []);

  const update = async (b: Parameters<typeof api.updateMe>[0]) => {
    try { const u = await api.updateMe(b); set(() => ({ me: u })); setError(""); } catch (e) { setError((e as Error).message); }
  };

  return (
    <div className="space-y-6">
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
                <code className="flex-1 text-[13px] truncate">{toolLabel(r.tool)}</code>
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

// Light or dark, like the Android app's Appearance screen. The choice lives
// on this device, not on the account, so one person's laptop and phone can
// differ.
function Appearance() {
  const [theme, setPick] = useState<Theme>(getTheme);
  const choose = (t: Theme) => { setTheme(t); setPick(t); };
  return (
    <div className="space-y-6">
      <section>
        <Label>Theme</Label>
        <ul className="rounded-2xl border border-line divide-y divide-line">
          {themes.map((t) => (
            <li key={t.id}>
              <button onClick={() => choose(t.id)} aria-pressed={theme === t.id}
                className="w-full flex items-center gap-3 px-3.5 h-12 text-left text-[14px] font-medium cursor-pointer hover:bg-hover transition-colors duration-[120ms] first:rounded-t-2xl last:rounded-b-2xl">
                <span className="flex-1">{t.label}</span>
                {theme === t.id && <span className="text-live [&_svg]:size-4.5"><Icon.check /></span>}
              </button>
            </li>
          ))}
        </ul>
        <p className="text-[13px] text-ink-2 mt-2">{themes.find((t) => t.id === theme)?.blurb}</p>
        <p className="text-[12.5px] text-ink-3 mt-1">gbot keeps its own palette either way, so your bots look the same everywhere.</p>
      </section>
    </div>
  );
}

type ProviderEdit = {
  id?: string; name: string; kind: ProviderKind; baseUrl: string;
  apiKey: string; removeKey: boolean; local: boolean; models: string[]; caps: Record<string, string[]>;
};

const providerKinds: { id: Exclude<ProviderKind, "codex">; label: string; hint: string; placeholder: string }[] = [
  { id: "openai", label: "OpenAI", hint: "vLLM, LM Studio, llama.cpp, LiteLLM…", placeholder: "http://localhost:1234/v1" },
  { id: "anthropic", label: "Anthropic", hint: "Claude models", placeholder: "https://api.anthropic.com" },
  { id: "gemini", label: "Gemini", hint: "Google AI Studio", placeholder: "https://generativelanguage.googleapis.com/v1beta" },
];

const blankProvider = (): ProviderEdit => ({
  name: "", kind: "openai", baseUrl: "", apiKey: "", removeKey: false, local: true, models: [], caps: {},
});

function Providers() {
  const me = useStore((s) => s.me);
  const models = useModels();
  const hasLocal = models?.some((m) => m.local);
  const update = async (b: Parameters<typeof api.updateMe>[0]) => {
    try { const u = await api.updateMe(b); set(() => ({ me: u })); setError(""); } catch (e) { setError((e as Error).message); }
  };
  const [list, setList] = useState<Provider[] | null>(null);
  const [edit, setEdit] = useState<ProviderEdit | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [codex, setCodex] = useState<CodexLogin | null>(null);
  const [removing, setRemoving] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  const load = () => api.providers().then((r) => { setList(r.providers); setCodex(r.codex ?? null); }).catch(() => setList([]));
  useEffect(() => { load(); }, []);
  const refreshModels = () => api.models().then((r) => set(() => ({ models: r.models }))).catch(() => {});

  async function useCodex() {
    setBusy(true);
    await attempt(() => api.createProvider({ name: "Codex (ChatGPT)", kind: "codex", baseUrl: "" }), "Codex models connected");
    setBusy(false); load(); refreshModels();
  }

  function start(p?: Provider) {
    if (p?.kind === "codex") return;
    setError(""); setNotice("");
    if (!p) {
      setEdit(blankProvider());
      return;
    }
    setEdit({
      id: p.id, name: p.name, kind: p.kind, baseUrl: p.baseUrl, apiKey: "",
      removeKey: false, local: p.local, models: p.models, caps: p.caps ?? {},
    });
  }

  async function save() {
    if (!edit) return;
    setBusy(true); setError(""); setNotice("");
    const body = { name: edit.name.trim(), kind: edit.kind, baseUrl: edit.baseUrl.trim(), local: edit.local, models: edit.models };
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
      setEdit({ ...edit, models: r.models, caps: r.caps ?? {} });
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
            <select value={edit.kind} onChange={(e) => setEdit({ ...edit, kind: e.target.value as ProviderKind, baseUrl: "", local: e.target.value === "openai" })}
              className="w-full h-9 rounded-[10px] bg-surface hairline px-3 text-[14px] text-ink outline-none focus:shadow-[inset_0_0_0_1px_var(--accent)] cursor-pointer">
              {providerKinds.map((k) => <option key={k.id} value={k.id}>{k.label}</option>)}
            </select>
          </div>
        </div>
        <div>
          <Label hint={edit.kind === "openai" ? kind.hint : `${kind.hint}; leave blank for the default`}>Endpoint</Label>
          <Input value={edit.baseUrl} onChange={(e) => setEdit({ ...edit, baseUrl: e.target.value })} placeholder={kind.placeholder} />
        </div>
        <div className="grid sm:grid-cols-2 gap-3">
          <div>
            <Label hint={edit.id && !edit.removeKey ? "leave blank to keep" : "optional for local servers"}>API key</Label>
            <PasswordInput value={edit.apiKey} onChange={(e) => setEdit({ ...edit, apiKey: e.target.value })}
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
          <Label hint="search, filter by what they can do, or add one by name">Models</Label>
          <ModelList models={edit.models} caps={edit.caps} onChange={(models) => setEdit({ ...edit, models })} />
        </div>
        {notice && <p className="text-live text-[13px]">{notice}</p>}
        {error && <p className="text-danger text-[13px]">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => setEdit(null)}>Cancel</Button>
          <Button variant="soft" onClick={discover} disabled={busy || (edit.kind === "openai" && !edit.baseUrl.trim())}>{busy ? "Checking…" : "Discover models"}</Button>
          <Button onClick={save} disabled={busy || !edit.name.trim()}>{busy ? "Saving…" : edit.id ? "Save" : "Connect"}</Button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <section>
        <Label hint="Bots use this unless you pick one for them">Default model</Label>
        <ModelSelect value={me?.defaultModel ?? ""} onChange={(v) => update({ defaultModel: v })} allowDefault="Best available" />
        <p className="text-[12.5px] text-ink-2 mt-2">A bot uses its own model if you set one, otherwise this. If a model isn't connected, it falls back to the best available.</p>
        {models && !hasLocal && (
          <div className="mt-3 rounded-[10px] bg-surface-2 p-4 flex gap-3">
            <Gbot seed="plug" look={{ shape: "pill", hue: "sky", eyes: "dot" }} mood="waiting" size={52} />
            <div className="text-[14px]">
              <div className="font-semibold">Connect a model provider</div>
              <p className="text-ink-2 mt-1">Run <a className="underline" href="https://ollama.com" target="_blank" rel="noreferrer">Ollama</a>, LM Studio, vLLM or any OpenAI-compatible server, or add Anthropic or Gemini below. For Ollama, pull a small model with tools:</p>
              <code className="block mt-2 bg-surface rounded-xl px-3 py-2 text-[13px]">ollama pull lfm3-omni</code>
            </div>
          </div>
        )}
      </section>

    <div className="space-y-4">
      <div className="flex items-start gap-3">
        <div className="flex-1">
          <div className="font-semibold text-[14px]">Model providers</div>
          <p className="text-[13px] text-ink-2">Connect any OpenAI-compatible server, Anthropic or Gemini. Keys are encrypted and only your account can use the models.</p>
        </div>
        <Button variant="soft" onClick={() => start()}><Icon.plus /> Add</Button>
      </div>
      {codex?.allowed && list && !list.some((p) => p.kind === "codex") && (
        <div className="rounded-2xl border border-line p-3 flex items-center gap-3">
          <div className="flex-1 min-w-0">
            <div className="font-semibold text-[14px]">OpenAI with your Codex login</div>
            <p className="text-[12.5px] text-ink-2">{codex.available
              ? "Use the ChatGPT account Codex is signed in to on this server. No API key needed."
              : "Sign in to Codex on the server (codex login) to use your ChatGPT plan here."}</p>
          </div>
          <Button variant="soft" disabled={!codex.available || busy} onClick={useCodex}>Use Codex login</Button>
        </div>
      )}
      {list?.length === 0 && <Empty text="No providers yet." />}
      <ul className="space-y-2">
        {list?.map((p) => {
          const status = p.status === "connected" ? "text-live" : p.status === "off" ? "text-ink-2" : "text-danger";
          return (
            <li key={p.id} className="rounded-2xl border border-line p-3">
              <div className="flex items-center gap-3">
                <span className={p.enabled ? "text-live" : "text-ink-2"}><Icon.plug /></span>
                <button className={`min-w-0 flex-1 text-left ${p.kind === "codex" ? "cursor-default" : "cursor-pointer"}`} onClick={() => start(p)}>
                  <div className="font-semibold text-[14px]">{p.name} <span className="text-[11px] font-bold uppercase tracking-wide text-ink-3">{p.kind}</span></div>
                  <div className="text-[12.5px] text-ink-2 truncate">{p.kind === "codex" ? "Uses this server's Codex sign-in" : p.baseUrl}</div>
                </button>
                <Toggle on={p.enabled} label={p.enabled ? "Turn off" : "Turn on"}
                  onChange={async (v) => { await attempt(() => api.updateProvider(p.id, { enabled: v }), v ? `${p.name} on` : `${p.name} off`); load(); refreshModels(); }} />
                <button className="text-ink-2 hover:text-ink cursor-pointer" aria-label={`Refresh ${p.name} models`}
                  onClick={async () => { await attempt(() => api.refreshProvider(p.id), "Models refreshed"); load(); refreshModels(); }}><Icon.spark /></button>
                <Button variant="danger" aria-label={`Remove ${p.name}`} onClick={() => setRemoving(p.id)}><Icon.trash /> Remove</Button>
              </div>
              {removing === p.id && (
                <div className="mt-2 flex items-center gap-2 rounded-xl bg-danger/5 px-3 py-2">
                  <span className="flex-1 text-[13px]">Remove {p.name}? Bots using its models will need another model.</span>
                  <Button variant="ghost" onClick={() => setRemoving(null)}>Keep</Button>
                  <Button variant="danger" onClick={async () => { setRemoving(null); await attempt(() => api.deleteProvider(p.id), `${p.name} removed`); load(); refreshModels(); }}>Remove</Button>
                </div>
              )}
              <div className="mt-2 text-[12.5px]">
                <span className={status}>{p.status === "connected" ? `${p.models.length} models` : p.status}</span>
                {p.models.length > 0 && (
                  <button className="ml-2 text-ink-2 underline cursor-pointer" onClick={() => setOpen(open === p.id ? null : p.id)}>
                    {open === p.id ? "Hide" : "Show"}
                  </button>
                )}
              </div>
              {open === p.id && <div className="mt-2"><ModelList models={p.models} caps={p.caps} /></div>}
            </li>
          );
        })}
      </ul>
    </div>
    </div>
  );
}

type Category = "Work" | "Design" | "Data" | "Developer" | "Finance";
type CatalogApp = { name: string; blurb: string; url: string; tint: string; category: Category; token?: boolean; open?: boolean };

const categories: Category[] = ["Work", "Design", "Data", "Developer", "Finance"];
const filters: (Category | "All")[] = ["All", ...categories];

// Apps that publish an MCP endpoint and support dynamic registration, so
// Connect is one sign-in. GitHub's remote server needs a personal access
// token instead.
const catalog: CatalogApp[] = [
  { name: "Notion", blurb: "Pages, databases and notes", url: "https://mcp.notion.com/mcp", tint: "#1f1f1f", category: "Work" },
  { name: "Linear", blurb: "Issues, projects and cycles", url: "https://mcp.linear.app/mcp", tint: "#5E6AD2", category: "Work" },
  { name: "Jira", blurb: "Issues and Confluence pages", url: "https://mcp.atlassian.com/v1/mcp", tint: "#0052CC", category: "Work" },
  { name: "Monday", blurb: "Boards, items and timelines", url: "https://mcp.monday.com/mcp", tint: "#6161FF", category: "Work" },
  { name: "ClickUp", blurb: "Tasks, docs and goals", url: "https://mcp.clickup.com/mcp", tint: "#7B68EE", category: "Work" },
  { name: "Airtable", blurb: "Bases, records and views", url: "https://mcp.airtable.com/mcp", tint: "#F82B60", category: "Work" },
  { name: "Attio", blurb: "CRM records and pipelines", url: "https://mcp.attio.com/mcp", tint: "#2B2B2B", category: "Work" },
  { name: "Zapier", blurb: "Automate thousands of apps", url: "https://mcp.zapier.com/api/v1/connect", tint: "#FF4F00", category: "Work" },
  { name: "Calendly", blurb: "Schedule meetings", url: "https://mcp.calendly.com/", tint: "#006BFF", category: "Work" },
  { name: "Buffer", blurb: "Schedule social posts", url: "https://mcp.buffer.com/mcp", tint: "#168EEA", category: "Work" },
  { name: "Canva", blurb: "Designs, folders and assets", url: "https://mcp.canva.com/mcp", tint: "#00C4CC", category: "Design" },
  { name: "Miro", blurb: "Boards, frames and stickies", url: "https://mcp.miro.com/mcp", tint: "#4262FF", category: "Design" },
  { name: "Webflow", blurb: "Sites, pages and CMS items", url: "https://mcp.webflow.com/mcp", tint: "#146EF5", category: "Design" },
  { name: "Figma", blurb: "Inspect designs and files", url: "https://mcp.figma.com/mcp", tint: "#F24E1E", category: "Design" },
  { name: "Wix", blurb: "Manage your Wix site", url: "https://mcp.wix.com/mcp", tint: "#0C6EFC", category: "Design" },
  { name: "Gamma", blurb: "Generate presentations", url: "https://mcp.gamma.app/mcp", tint: "#6C3BF2", category: "Design" },
  { name: "HeyGen", blurb: "Create AI videos", url: "https://mcp.heygen.com/mcp", tint: "#6D5DF6", category: "Design" },
  { name: "Excalidraw", blurb: "Sketch diagrams", url: "https://mcp.excalidraw.com/mcp", tint: "#6965DB", category: "Design", open: true },
  { name: "Supabase", blurb: "Projects, tables and SQL", url: "https://mcp.supabase.com/mcp", tint: "#1F9D6B", category: "Data" },
  { name: "Amplitude", blurb: "Events, charts and cohorts", url: "https://mcp.amplitude.com/mcp", tint: "#1E61F0", category: "Data" },
  { name: "Mixpanel", blurb: "Query product analytics", url: "https://mcp.mixpanel.com/mcp", tint: "#7856FF", category: "Data" },
  { name: "Metricool", blurb: "Track social and ad analytics", url: "https://ai.metricool.com/mcp", tint: "#6F4EF2", category: "Data" },
  { name: "Parallel", blurb: "Web search and research", url: "https://mcp.parallel.ai/mcp", tint: "#1F2937", category: "Data", open: true },
  { name: "GitHub", blurb: "Repos, issues and pull requests", url: "https://api.githubcopilot.com/mcp/", tint: "#24292F", category: "Developer", token: true },
  { name: "Sentry", blurb: "Errors, projects and releases", url: "https://mcp.sentry.dev/mcp", tint: "#362D59", category: "Developer" },
  { name: "Postman", blurb: "Collections, APIs and environments", url: "https://mcp.postman.com/mcp", tint: "#FF6C37", category: "Developer" },
  { name: "Vercel", blurb: "Manage your deploys", url: "https://mcp.vercel.com/", tint: "#000000", category: "Developer" },
  { name: "Netlify", blurb: "Manage your deploys", url: "https://mcp.netlify.com/mcp", tint: "#00C7B7", category: "Developer" },
  { name: "Datadog", blurb: "Query metrics and logs", url: "https://mcp.datadoghq.com/api/unstable/mcp-server/mcp", tint: "#632CA6", category: "Developer" },
  { name: "Stripe", blurb: "Payments, customers and invoices", url: "https://mcp.stripe.com", tint: "#635BFF", category: "Finance" },
  { name: "PayPal", blurb: "Transactions and invoices", url: "https://mcp.paypal.com/mcp", tint: "#003087", category: "Finance" },
  { name: "Finary", blurb: "Track your portfolio", url: "https://public-api.finary.com/mcp", tint: "#1A1A2E", category: "Finance" },
  { name: "Scalable Capital", blurb: "Track your investments", url: "https://mcp.scalable.capital/mcp", tint: "#00B389", category: "Finance" },
  { name: "Whop", blurb: "Manage your communities and payments", url: "https://mcp.whop.com/mcp", tint: "#FF6243", category: "Finance" },
  { name: "Daloopa", blurb: "Financial model data", url: "https://mcp.daloopa.com/mcp", tint: "#1F3A93", category: "Finance" },
];

const sameUrl = (a: string, b: string) => a.replace(/\/+$/, "") === b.replace(/\/+$/, "");

const iconUrls = import.meta.glob("../assets/integrations/*.png", { eager: true, query: "?url", import: "default" }) as Record<string, string>;
const iconSlug = (name: string) => name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");

/** The app's own logo on a white rounded tile, or its first letter. */
function AppIcon({ app, size = 36 }: { app: CatalogApp; size?: number }) {
  const src = iconUrls[`../assets/integrations/${iconSlug(app.name)}.png`];
  return (
    <span className="grid shrink-0 place-items-center overflow-hidden rounded-xl bg-white shadow-sm" style={{ width: size, height: size }} aria-hidden>
      {src
        ? <img src={src} alt="" className="size-[68%] object-contain" />
        : <span className="font-bold" style={{ color: app.tint, fontSize: size * 0.42 }}>{app.name[0]}</span>}
    </span>
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
  const [search, setSearch] = useState("");
  const [cat, setCat] = useState<Category | "All">("All");
  const [connecting, setConnecting] = useState("");
  const [connectingUrl, setConnectingUrl] = useState("");
  const [authUrl, setAuthUrl] = useState("");
  const popupRef = useRef<Window | null>(null);
  const load = () => api.mcp().then((r) => setList(r.servers)).catch(() => setList([]));
  useEffect(() => { load(); }, []);

  const closeAuth = () => {
    popupRef.current?.close();
    popupRef.current = null;
    setConnecting("");
    setConnectingUrl("");
    setAuthUrl("");
  };

  // The callback page posts here, so the card flips to Connected without
  // the person guessing whether the popup worked.
  useEffect(() => {
    const on = (e: MessageEvent) => {
      if (e.origin !== location.origin) return;
      const d = e.data as { type?: string; ok?: boolean; name?: string; error?: string } | null;
      if (d?.type !== "gbot-integration") return;
      setConnecting("");
      setConnectingUrl("");
      setAuthUrl("");
      popupRef.current = null;
      if (d.ok) toast(`${d.name} connected`);
      else setError(d.error || "Couldn't connect that app.");
      load();
    };
    window.addEventListener("message", on);
    return () => window.removeEventListener("message", on);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Servers connect in the background; poll while any is still connecting
  // instead of leaving the row stuck on the first response.
  useEffect(() => {
    if (!connecting && !list?.some((m) => m.status === "connecting" || (m.status === "off" && m.enabled))) return;
    const t = window.setInterval(load, 900);
    return () => window.clearInterval(t);
  }, [list, connecting]); // eslint-disable-line react-hooks/exhaustive-deps

  // If the callback can't post (a different origin), the new row is still
  // proof the sign-in finished; stop the banner either way.
  useEffect(() => {
    if (connecting && list?.some((m) => sameUrl(m.url, connectingUrl))) closeAuth();
  }, [list, connecting, connectingUrl]); // eslint-disable-line react-hooks/exhaustive-deps

  // A closed popup leaves no event; stop waiting after a few minutes.
  useEffect(() => {
    if (!connecting) return;
    const t = window.setTimeout(closeAuth, 300000);
    return () => window.clearTimeout(t);
  }, [connecting]); // eslint-disable-line react-hooks/exhaustive-deps

  // Escape closes the token dialog, not the whole settings modal.
  useEffect(() => {
    if (!adding) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      e.stopPropagation();
      setAdding(false);
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [adding]);

  // Center the sign-in window over the app and put a note in it, so a
  // blank popup never looks like a broken button.
  function openAuth(target = "") {
    const w = 520, h = 680;
    const left = Math.max(0, Math.round((window.screenX || 0) + ((window.outerWidth || w) - w) / 2));
    const top = Math.max(0, Math.round((window.screenY || 0) + ((window.outerHeight || h) - h) / 2));
    const p = window.open(target || "about:blank", "gbot-integration", `popup,width=${w},height=${h},left=${left},top=${top}`);
    if (p && !target) {
      try {
        p.document.title = "gbot";
        p.document.body.style.cssText = "margin:0;min-height:100vh;display:grid;place-items:center;font:15px system-ui,sans-serif;color:#666;background:#fafafa";
        p.document.body.textContent = "Preparing sign-in…";
      } catch {
        // A reused window may still be on the provider's origin.
      }
    }
    return p;
  }

  async function connect(app: CatalogApp) {
    setError("");
    if (app.token) {
      setName(app.name); setUrl(app.url); setToken(""); setAdding(true);
      return;
    }
    if (app.open) {
      try { await api.addMcp({ name: app.name, url: app.url }); toast(`${app.name} connected`); load(); }
      catch (e) { setError((e as Error).message); }
      return;
    }
    setConnecting(app.name);
    setConnectingUrl(app.url);
    // Open before awaiting: an await loses the click's popup permission.
    popupRef.current = openAuth();
    try {
      const r = await api.startMcpOAuth({ name: app.name, url: app.url });
      setAuthUrl(r.url);
      if (popupRef.current) {
        popupRef.current.location.href = r.url;
        popupRef.current.focus();
      }
    } catch (e) {
      closeAuth();
      setError((e as Error).message);
    }
  }

  async function add() {
    setBusy(true);
    setError("");
    try {
      await api.addMcp({ name, url, token: token || undefined });
      setAdding(false); setName(""); setUrl(""); setToken("");
      load();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }

  // The token form opened from a catalog app (GitHub), which needs its token.
  const fromCatalog = adding && catalog.some((a) => a.token && a.name === name && sameUrl(a.url, url));
  const q = search.trim().toLowerCase();
  const apps = catalog.filter((a) => (cat === "All" || a.category === cat) && `${a.name} ${a.blurb}`.toLowerCase().includes(q));
  const groups = categories.map((c) => ({ c, apps: apps.filter((a) => a.category === c) })).filter((g) => g.apps.length > 0);
  const taken = (u: string) => !!list?.some((m) => sameUrl(m.url, u));

  const card = (a: CatalogApp) => {
    const on = taken(a.url);
    const waiting = connecting === a.name;
    return (
      <button key={a.name} type="button" onClick={() => connect(a)} disabled={on || waiting || !!connecting}
        className={`group flex items-center gap-2.5 rounded-2xl border border-line p-2.5 text-left transition-colors duration-[120ms] cursor-pointer disabled:cursor-default ${on ? "bg-surface-2/60" : "hover:bg-hover"}`}>
        <AppIcon app={a} />
        <span className="min-w-0 flex-1">
          <span className="block font-semibold text-[13.5px] leading-5 truncate">{a.name}</span>
          <span className="block text-[12px] leading-4 text-ink-2 truncate">{a.blurb}</span>
        </span>
        <span className={`shrink-0 text-[11.5px] font-medium ${on ? "text-live" : waiting ? "text-ink-2" : "text-ink-3 group-hover:text-ink-2"}`}>
          {on ? <Icon.check /> : waiting ? "Signing in…" : "Connect"}
        </span>
      </button>
    );
  };

  return (
    <>
      {/* Both dialogs go to the page itself: inside the settings panel,
          which animates with a transform, "fixed" would pin them to the
          scrolled list, out of view on a phone. */}
      {connecting && createPortal(
        <div className="fixed inset-0 z-[70] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-black/40" onClick={closeAuth} aria-hidden />
          <div role="dialog" aria-modal="true" aria-label={`Signing in to ${connecting}`}
            className="relative w-full max-w-[420px] rounded-2xl bg-bg p-4 shadow-pop hairline pop-in space-y-4">
            <div className="flex items-center gap-3">
              <AppIcon app={catalog.find((a) => a.name === connecting) ?? { name: connecting, blurb: "", url: "", tint: "#888", category: "Work" }} size={40} />
              <div className="min-w-0 flex-1">
                <div className="text-[15px] font-semibold">Signing in to {connecting}</div>
                <div className="flex items-center gap-1.5 text-[12.5px] leading-4 text-ink-2">
                  <span className="size-2 shrink-0 rounded-full bg-live animate-pulse" />
                  {authUrl ? "Finish in the window that opened, then come back here." : "Preparing the sign-in…"}
                </div>
              </div>
            </div>
            {error && <p className="text-danger text-[13px]" role="alert">{error}</p>}
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={closeAuth}>Cancel</Button>
              {authUrl && (
                <Button variant="soft" onClick={() => { popupRef.current = openAuth(authUrl); popupRef.current?.focus(); }}>Open sign-in again</Button>
              )}
            </div>
          </div>
        </div>,
        document.body,
      )}
      {adding && createPortal(
        <div className="fixed inset-0 z-[66] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-black/40" onClick={() => !busy && setAdding(false)} aria-hidden />
          <div role="dialog" aria-modal="true" aria-label={`Connect ${name || "a server"}`}
            className="relative w-full max-w-[420px] rounded-2xl bg-bg p-4 shadow-pop hairline pop-in">
            <div className="mb-3 flex items-start justify-between gap-3">
              <div>
                <div className="text-[15px] font-semibold">Connect {name || "a server"}</div>
                <div className="text-[12.5px] text-ink-2">{fromCatalog ? `${name} connects with a personal access token. Make one in its settings and paste it here.` : "Give it a name and URL, and a token if it needs one."}</div>
              </div>
              <button type="button" aria-label="Close" onClick={() => setAdding(false)}
                className="grid size-8 shrink-0 place-items-center rounded-lg text-ink-2 hover:text-ink hover:bg-hover cursor-pointer"><Icon.close /></button>
            </div>
            <div className="space-y-3">
              <div><Label>Name</Label><Input value={name} onChange={(e) => setName(e.target.value)} placeholder="GitHub" autoFocus={!url} /></div>
              <div><Label>URL</Label><Input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://mcp.example.com/mcp" /></div>
              <div><Label hint={fromCatalog ? undefined : "optional"}>Token</Label><PasswordInput value={token} onChange={(e) => setToken(e.target.value)} placeholder="Personal access token" autoComplete="off" autoFocus={!!url} /></div>
              <p className="text-[12.5px] text-ink-2">Tokens are encrypted on this server and never shown to bots.</p>
              {error && <p className="text-danger text-[13px]" role="alert">{error}</p>}
              <div className="flex justify-end gap-2 pt-1">
                <Button variant="ghost" onClick={() => setAdding(false)}>Cancel</Button>
                <Button onClick={add} disabled={busy || !name.trim() || !url.trim() || (fromCatalog && !token.trim())}>{busy ? "Connecting…" : "Connect"}</Button>
              </div>
            </div>
          </div>
        </div>,
        document.body,
      )}
      <div className="space-y-5">
      <div className="flex items-start gap-3">
        <div className="flex-1">
          <div className="font-semibold text-[14px]">Apps</div>
          <p className="text-[13px] text-ink-2">Let your bots work in the apps you already use. Each account stays yours, and you can remove an app any time.</p>
        </div>
        {!adding && <Button variant="soft" onClick={() => { setName(""); setUrl(""); setToken(""); setError(""); setAdding(true); }}><Icon.plus /> Custom</Button>}
      </div>

      <div className="relative">
        <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-3"><Icon.search /></span>
        <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search apps" className="pl-9" aria-label="Search apps" />
      </div>

      <div className="flex gap-1.5 overflow-x-auto scroll-soft -mx-0.5 px-0.5 pb-0.5">
        {filters.map((c) => (
          <button key={c} type="button" onClick={() => setCat(c)} aria-pressed={cat === c}
            className={`h-7 shrink-0 rounded-full px-3 text-[12.5px] font-medium transition-colors cursor-pointer ${cat === c ? "bg-surface-2 text-ink" : "text-ink-2 hover:text-ink hover:bg-hover"}`}>
            {c}
          </button>
        ))}
      </div>

      {error && <p className="text-danger text-[13px]" role="alert">{error}</p>}

      <div className="space-y-3">
        {groups.map((g) => (
          <div key={g.c} className="space-y-2">
            {cat === "All" && <div className="px-0.5 text-[11px] font-semibold uppercase tracking-[0.08em] text-ink-3">{g.c}</div>}
            <div className="grid sm:grid-cols-2 gap-2">
              {g.apps.map(card)}
            </div>
          </div>
        ))}
      </div>
      {apps.length === 0 && <p className="text-center text-[13px] text-ink-2 py-4">No apps match “{search}”. You can add it as a custom server above.</p>}

      <div>
        <div className="font-semibold text-[13px] text-ink-2 mb-2">Connected</div>
        {list?.length === 0 && !adding && <p className="text-[13px] text-ink-3">Nothing connected yet.</p>}
        <ul className="space-y-2">
          {list?.map((m) => (
            <li key={m.id} className="rounded-2xl border border-line p-3">
              <div className="flex items-center gap-3">
                <span className={m.status === "connected" ? "text-live" : "text-ink-2"}><Icon.plug /></span>

                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2 font-semibold text-[14px]">
                    <span className="truncate">{m.name}</span>
                    {m.oauth && <span className="rounded-full bg-surface-2 px-2 py-0.5 text-[10.5px] font-semibold text-ink-2">Signed in</span>}
                  </div>
                  <div className="text-[12.5px] text-ink-2 truncate">{m.url}</div>
                </div>
                <Toggle on={m.enabled} label={m.enabled ? "Turn off" : "Turn on"} onChange={async (v) => { await attempt(() => api.toggleMcp(m.id, v), v ? `${m.name} on` : `${m.name} off`); load(); }} />
                <button className="text-ink-2 hover:text-danger cursor-pointer" aria-label={`Remove ${m.name}`} onClick={async () => { await attempt(() => api.deleteMcp(m.id), `${m.name} removed`); load(); }}><Icon.trash /></button>
              </div>
              <div className="mt-2 h-[92px] overflow-y-auto scroll-soft rounded-xl bg-surface-2/40 px-2.5 py-2 text-[12.5px] leading-5">
                {m.status === "connected" ? (
                  <>
                    <div className="mb-1 text-ink-3">{m.tools.length} tools</div>
                    {m.tools.map((t) => (
                      <div key={t} className="truncate text-ink-2">{t}</div>
                    ))}
                  </>
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
      </div>
    </>
  );
}

function parseSkillMd(text: string): Partial<Skill> {
  const m = /^---\n([\s\S]*?)\n---\n?([\s\S]*)$/.exec(text.trim());
  if (!m) return { body: text };
  // Split on the first colon only, so values like "Use when: X" keep their rest.
  const meta: Record<string, string> = {};
  for (const line of m[1].split("\n")) {
    const i = line.indexOf(":");
    if (i > 0) meta[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  }
  return { name: meta.name, description: meta.description, body: m[2].trim() };
}

function Vault() {
  const [list, setList] = useState<VaultSecret[] | null>(null);
  const [form, setForm] = useState<{ id?: string; name: string; origins: string; value: string } | null>(null);
  const [error, setError] = useState("");
  const load = () => api.vault().then((r) => setList(r.secrets)).catch(() => setList([]));
  useEffect(() => { load(); }, []);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (!form) return;
    const origins = form.origins.split(/[\n,]+/).map((s) => s.trim()).filter(Boolean);
    if (!form.name.trim() || origins.length === 0 || (!form.id && !form.value)) {
      setError("Add a name, the value, and at least one site.");
      return;
    }
    setError("");
    try {
      if (form.id) {
        await api.updateVault(form.id, { name: form.name.trim(), origins, ...(form.value ? { value: form.value } : {}) });
      } else {
        await api.addVault({ name: form.name.trim(), value: form.value, origins });
      }
      setForm(null);
      load();
      toast("Secret saved");
    } catch (err) { setError((err as Error).message); }
  }

  async function remove(v: VaultSecret) {
    if (!confirm(`Delete the ${v.name} secret?`)) return;
    try { await api.deleteVault(v.id); load(); toast("Secret deleted"); } catch (err) { toastError(err, "Couldn't delete"); }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-start gap-3 rounded-2xl bg-surface-2 p-4">
        <span className="mt-0.5 text-ink-2"><Icon.lock /></span>
        <p className="text-[13px] text-ink-2 leading-relaxed">
          Values are sealed on the server and never shown again. A bot can type one into a <strong>password field</strong> on the sites you list; it never sees the value.
        </p>
      </div>

      {list?.map((v) => (
        <div key={v.id} className="flex items-center gap-3 rounded-2xl border border-line p-3">
          <span className="size-8 rounded-full bg-surface-2 grid place-items-center text-ink-2 [&_svg]:size-4"><Icon.lock /></span>
          <div className="min-w-0 flex-1">
            <div className="font-semibold text-[14px] truncate">{v.name}</div>
            <div className="text-[12px] text-ink-3 truncate">{v.origins.join(", ")}</div>
          </div>
          <Button variant="ghost" onClick={() => setForm({ id: v.id, name: v.name, origins: v.origins.join("\n"), value: "" })}>Edit</Button>
          <Button variant="danger" onClick={() => remove(v)}>Delete</Button>
        </div>
      ))}
      {list?.length === 0 && !form && <Empty text="No saved secrets yet." />}

      {form ? (
        <form onSubmit={save} className="rounded-2xl border border-line p-4 space-y-3">
          <div><Label>Name</Label><Input autoFocus value={form.name} maxLength={60} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="LinkedIn" /></div>
          <div>
            <Label hint="One per line; subdomains are included">Sites</Label>
            <TextArea rows={2} value={form.origins} onChange={(e) => setForm({ ...form, origins: e.target.value })} placeholder={"linkedin.com\nindeed.com"} />
          </div>
          <div>
            <Label hint={form.id ? "Leave blank to keep the current value" : undefined}>{form.id ? "New value" : "Value"}</Label>
            <PasswordInput autoComplete="new-password" value={form.value} onChange={(e) => setForm({ ...form, value: e.target.value })} placeholder={form.id ? "••••••••" : "Password or token"} />
          </div>
          {error && <p className="text-danger text-[13px]" role="alert">{error}</p>}
          <div className="flex justify-end gap-2">
            <Button type="button" variant="soft" onClick={() => { setForm(null); setError(""); }}>Cancel</Button>
            <Button type="submit">Save secret</Button>
          </div>
        </form>
      ) : (
        <Button variant="soft" onClick={() => setForm({ name: "", origins: "", value: "" })}><Icon.plus /> Add secret</Button>
      )}
    </div>
  );
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
      await api.saveSkill({ id: edit!.id, name: edit!.name ?? "", description: edit!.description ?? "", body: edit!.body ?? "" });
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

import { useMemo, useState } from "react";

export type Item = { id: string; label: string; sub?: string; caps: string[]; local?: boolean };

// Filters combine: a model must match every one that's switched on.
const FILTERS: { id: string; label: string; test: (i: Item) => boolean }[] = [
  { id: "tools", label: "Tool calling", test: (i) => i.caps.includes("tools") },
  { id: "vision", label: "Vision", test: (i) => i.caps.includes("vision") },
  { id: "reasoning", label: "Reasoning", test: (i) => i.caps.includes("reasoning") },
  { id: "local", label: "Local", test: (i) => i.local === true },
  { id: "cloud", label: "Cloud", test: (i) => i.local === false },
];

const inputCls = "h-8 rounded-[10px] bg-surface hairline px-3 text-[13px] outline-none focus:shadow-[inset_0_0_0_1px_var(--accent)]";

function CapTags({ caps }: { caps: string[] }) {
  const tags = [
    caps.includes("tools") && { id: "tools", tone: "text-live" },
    caps.includes("vision") && { id: "vision", tone: "text-ink-2" },
    caps.includes("reasoning") && { id: "reasoning", tone: "text-ink-2" },
  ].filter(Boolean) as { id: string; tone: string }[];
  if (!tags.length) return null;
  return (
    <span className="flex items-center gap-1.5 shrink-0">
      {tags.map((t) => (
        <span key={t.id} className={`text-[10.5px] font-bold uppercase tracking-wide ${t.tone}`}>{t.id}</span>
      ))}
    </span>
  );
}

// Search + multi-select filters + a scrollable list. Pass onSelect to pick
// one (the selected row is highlighted); onRemove to delete rows.
export function ModelBrowser({ items, selected, onSelect, onRemove }: {
  items: Item[]; selected?: string; onSelect?: (id: string) => void; onRemove?: (id: string) => void;
}) {
  const [q, setQ] = useState("");
  const [on, setOn] = useState<string[]>([]);
  const filters = FILTERS.filter((f) => items.some(f.test)); // only ones that could match
  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    const active = FILTERS.filter((f) => on.includes(f.id));
    return items.filter((i) => (!needle || `${i.label} ${i.sub ?? ""}`.toLowerCase().includes(needle)) && active.every((f) => f.test(i)));
  }, [items, q, on]);
  const toggle = (id: string) => setOn((cur) => (cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id]));

  return (
    <div className="space-y-2">
      <input value={q} onChange={(e) => setQ(e.target.value)} placeholder={`Search ${items.length} models`} className={`w-full ${inputCls}`} />
      {filters.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {filters.map((f) => (
            <button key={f.id} type="button" aria-pressed={on.includes(f.id)} onClick={() => toggle(f.id)}
              className={`h-7 px-3 rounded-full text-[12px] font-semibold cursor-pointer ${on.includes(f.id) ? "bg-ink text-bg" : "bg-surface-2 text-ink-2"}`}>{f.label}</button>
          ))}
          {on.length > 0 && <button type="button" onClick={() => setOn([])} className="h-7 px-3 text-[12px] text-ink-2 underline cursor-pointer">Clear</button>}
        </div>
      )}
      <ul className="max-h-56 overflow-y-auto rounded-xl border border-line divide-y divide-line">
        {shown.length === 0 && <li className="px-3 py-2 text-[13px] text-ink-2">{items.length ? "No models match." : "No models yet."}</li>}
        {shown.map((i) => {
          const sel = selected === i.id;
          return (
            <li key={i.id} className={`flex items-center gap-2 px-3 py-1.5 text-[13px] ${sel ? "bg-surface-2" : ""}`}>
              {onSelect ? (
                <button type="button" onClick={() => onSelect(i.id)} aria-pressed={sel} className="flex-1 min-w-0 flex items-center gap-2 text-left cursor-pointer">
                  <span className={`w-3 ${sel ? "text-live font-bold" : "text-transparent"}`}>✓</span>
                  <span className="min-w-0">
                    <span className={`block truncate ${sel ? "font-semibold" : ""}`}>{i.label}</span>
                    {i.sub && <span className="block truncate text-[11.5px] text-ink-2">{i.sub}</span>}
                  </span>
                </button>
              ) : <span className="flex-1 min-w-0 truncate font-mono">{i.label}</span>}
              <CapTags caps={i.caps} />
              {onRemove && <button type="button" aria-label={`Remove ${i.label}`} className="text-ink-2 hover:text-danger cursor-pointer" onClick={() => onRemove(i.id)}>×</button>}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

// A provider's models, with optional manual add/remove.
export function ModelList({ models, caps, onChange }: {
  models: string[]; caps?: Record<string, string[]>; onChange?: (models: string[]) => void;
}) {
  const [draft, setDraft] = useState("");
  const items = useMemo(() => models.map((m) => ({ id: m, label: m, caps: caps?.[m] ?? [] })), [models, caps]);
  function add() {
    const m = draft.trim();
    if (m && !models.includes(m)) onChange?.([...models, m]);
    setDraft("");
  }
  return (
    <div className="space-y-2">
      <ModelBrowser items={items} onRemove={onChange && ((id) => onChange(models.filter((x) => x !== id)))} />
      {onChange && (
        <div className="flex gap-2">
          <input value={draft} onChange={(e) => setDraft(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); add(); } }}
            placeholder="Add a model by name, e.g. gemini-flash-latest" className={`flex-1 min-w-0 font-mono ${inputCls}`} />
          <button type="button" onClick={add} className="h-8 px-3 rounded-full bg-surface-2 text-[12px] font-semibold cursor-pointer">Add</button>
        </div>
      )}
    </div>
  );
}

// Picks one model. Collapsed it shows the current choice (name, provider and
// abilities); clicking opens the searchable, filterable list.
export function ModelPicker({ items, value, onChange, fallbackLabel }: { items: Item[]; value: string; onChange: (id: string) => void; fallbackLabel?: string }) {
  const [open, setOpen] = useState(false);
  const cur = items.find((i) => i.id === value);
  return (
    <div className="space-y-2">
      <button type="button" onClick={() => setOpen((v) => !v)} aria-expanded={open}
        className={`w-full flex items-center gap-2 rounded-2xl bg-surface hairline px-3 py-2.5 text-left cursor-pointer ${cur ? "shadow-[inset_0_0_0_1px_var(--live)]" : value ? "shadow-[inset_0_0_0_1px_var(--danger)]" : ""}`}>
        <span className="flex-1 min-w-0">
          <span className="block truncate text-[14px] font-semibold">{cur ? cur.label : value || "Pick a model"}</span>
          {cur ? cur.sub && <span className="block truncate text-[12px] text-ink-2">{cur.sub}</span>
            : value ? <span className="block text-[12px] text-danger">Not connected. {fallbackLabel ? `Using ${fallbackLabel} until you pick another.` : "Pick another model."}</span>
              : fallbackLabel && <span className="block text-[12px] text-ink-2">Now: {fallbackLabel}</span>}
        </span>
        {cur && <CapTags caps={cur.caps} />}
        <span className="text-ink-3 text-[11px]">{open ? "▲" : "▼"}</span>
      </button>
      {open && <ModelBrowser items={items} selected={value} onSelect={(id) => { onChange(id); setOpen(false); }} />}
    </div>
  );
}

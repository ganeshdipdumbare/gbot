import { useEffect } from "react";
import { api } from "../lib/api";
import { set, useStore } from "../lib/store";

export function useModels() {
  const models = useStore((s) => s.models);
  useEffect(() => {
    api.models().then((r) => set(() => ({ models: r.models }))).catch(() => set(() => ({ models: [] })));
  }, []);
  return models;
}

export function ModelSelect({ value, onChange, allowDefault }: { value: string; onChange: (v: string) => void; allowDefault?: string }) {
  const models = useModels();
  const local = models?.filter((m) => m.local) ?? [];
  const paid = models?.filter((m) => !m.local) ?? [];
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className="w-full h-11 rounded-2xl bg-surface border-2 border-line px-3 text-[14px] outline-none focus:border-live cursor-pointer"
    >
      {allowDefault !== undefined && <option value="">{allowDefault}</option>}
      {local.length > 0 && (
        <optgroup label="On this machine (free, private)">
          {local.map((m) => <option key={m.id} value={m.id}>{m.label}{m.tools ? "" : " (chat only)"}</option>)}
        </optgroup>
      )}
      {paid.length > 0 && (
        <optgroup label="Cloud providers">
          {paid.map((m) => <option key={m.id} value={m.id}>{m.provider} · {m.label}</option>)}
        </optgroup>
      )}
      {value && models && !models.some((m) => m.id === value) && <option value={value}>{value} (unavailable)</option>}
    </select>
  );
}

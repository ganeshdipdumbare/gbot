import { useEffect, useMemo } from "react";
import { ModelPicker, type Item } from "./ModelList";
import { api } from "../lib/api";
import { set, useStore } from "../lib/store";

export function useModels() {
  const models = useStore((s) => s.models);
  useEffect(() => {
    api.models().then((r) => set(() => ({ models: r.models }))).catch(() => set(() => ({ models: [] })));
  }, []);
  return models;
}

// fallback is the model id the default row stands for right now; without
// one it stands for whatever "best available" picks.
export function ModelSelect({ value, onChange, allowDefault, fallback }: { value: string; onChange: (v: string) => void; allowDefault?: string; fallback?: string }) {
  const models = useModels();
  const now = models?.find((m) => m.id === fallback) ?? models?.find((m) => m.best);
  const items = useMemo<Item[]>(() => [
    ...(allowDefault !== undefined ? [{ id: "", label: allowDefault, sub: now ? `Now: ${now.label} · ${now.provider}` : "No model connected yet", caps: now?.caps ?? [] }] : []),
    ...(models ?? []).map((m) => ({ id: m.id, label: m.label, sub: m.provider, caps: m.caps ?? (m.tools ? ["tools"] : []), local: m.local })),
  ], [models, allowDefault, now]);
  return <ModelPicker items={items} value={value} onChange={onChange} fallbackLabel={now?.label} />;
}

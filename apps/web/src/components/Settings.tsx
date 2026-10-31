import { useState } from "react";
import { Gbot } from "@gbot/characters";
import { api } from "../lib/api";
import { set, useStore } from "../lib/store";
import { Modal } from "./Modal";
import { ModelSelect, useModels } from "./ModelSelect";
import { Button, Icon, Label } from "./ui";

export function Settings({ open, onClose }: { open: boolean; onClose: () => void }) {
  const me = useStore((s) => s.me);
  const models = useModels();
  const [error, setError] = useState("");
  const hasLocal = models?.some((m) => m.local);

  return (
    <Modal open={open} onClose={onClose} title="Settings" width={520}>
      <div className="px-5 pb-6 space-y-5">
        <div>
          <Label hint="Bots use this unless you pick one for them">Default model</Label>
          <ModelSelect
            value={me?.defaultModel ?? ""}
            onChange={async (v) => {
              try { const u = await api.updateMe({ defaultModel: v }); set(() => ({ me: u })); setError(""); }
              catch (e) { setError((e as Error).message); }
            }}
            allowDefault="Best available"
          />
          {error && <p className="text-danger text-sm mt-2">{error}</p>}
        </div>

        {models && !hasLocal && (
          <div className="rounded-[22px] bg-surface-2 p-4 flex gap-3">
            <Gbot seed="plug" look={{ shape: "bean", hue: "sky", accessory: "antenna" }} mood="waiting" size={64} />
            <div className="text-[14px]">
              <div className="font-display font-extrabold text-[15px]">Run models on your own machine</div>
              <p className="text-ink-2 mt-1">Install <a className="underline" href="https://ollama.com" target="_blank" rel="noreferrer">Ollama</a>, then pull a small model with tools:</p>
              <code className="block mt-2 bg-surface rounded-xl px-3 py-2 text-[13px]">ollama pull qwen3.5:4b</code>
              <p className="text-ink-2 mt-2">Free, private, and nothing leaves your computer.</p>
            </div>
          </div>
        )}

        <div className="rounded-[22px] border border-line p-4 text-[14px]">
          <div className="font-display font-extrabold">Cloud models (optional)</div>
          <p className="text-ink-2 mt-1">
            Set <code>ANTHROPIC_API_KEY</code>, <code>OPENAI_API_KEY</code>, <code>GEMINI_API_KEY</code> or others in the server's environment and restart. Keys stay on the server and are never shown to bots.
          </p>
        </div>

        <div className="flex items-center justify-between border-t border-line pt-4">
          <div className="text-[13px] text-ink-2">Signed in as <span className="font-semibold text-ink">{me?.email}</span></div>
          <Button variant="ghost" onClick={async () => { await api.logout(); set(() => ({ me: null, bots: [], messages: {}, presence: {} })); }}>
            <Icon.logout /> Sign out
          </Button>
        </div>
      </div>
    </Modal>
  );
}

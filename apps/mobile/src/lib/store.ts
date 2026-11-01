import { useSyncExternalStore } from "react";
import { api, liveEvents, type Bot, type Group, type Message, type Presence, type User } from "./api";

// One live store for the app, fed by the same event stream as the web.

interface State {
  me: User | null;
  bots: Bot[];
  groups: Group[];
  presence: Record<string, Presence>;
  messages: Record<string, Message[]>;
}
let state: State = { me: null, bots: [], groups: [], presence: {}, messages: {} };
const subs = new Set<() => void>();
export const set = (fn: (s: State) => Partial<State>) => { state = { ...state, ...fn(state) }; subs.forEach((f) => f()); };
export const get = () => state;
export const useStore = <T,>(sel: (s: State) => T) => useSyncExternalStore((l) => (subs.add(l), () => subs.delete(l)), () => sel(state));

const keyOf = (m: Message) => (m.groupId ? "g:" + m.groupId : m.botId!);
const patch = (k: string, id: string, fn: (m: Message) => Message) =>
  set((s) => (s.messages[k] ? { messages: { ...s.messages, [k]: s.messages[k].map((m) => (m.id === id ? fn(m) : m)) } } : {}));

export async function loadAll() {
  const [{ bots, presence }, { groups }] = await Promise.all([api.bots(), api.groups()]);
  set(() => ({ bots, presence, groups }));
}

export async function loadMessages(k: string) {
  const { messages } = await api.messages(k);
  set((s) => ({ messages: { ...s.messages, [k]: messages } }));
}

export function upsertMessage(m: Message) {
  const k = keyOf(m);
  set((s) => {
    const list = s.messages[k];
    if (!list) return {};
    if (list.some((x) => x.id === m.id)) return { messages: { ...s.messages, [k]: list.map((x) => (x.id === m.id ? m : x)) } };
    const i = m.role === "user" ? list.findIndex((x) => x.pending && x.content === m.content) : -1;
    return { messages: { ...s.messages, [k]: i >= 0 ? list.map((x, j) => (j === i ? m : x)) : [...list, m] } };
  });
}

export async function send(k: string, text: string) {
  const tmp: Message = { id: "tmp" + Date.now(), botId: k.startsWith("g:") ? undefined : k, groupId: k.startsWith("g:") ? k.slice(2) : undefined, role: "user", content: text, createdAt: new Date().toISOString(), pending: true };
  set((s) => ({ messages: { ...s.messages, [k]: [...(s.messages[k] ?? []), tmp] } }));
  try {
    upsertMessage(await api.send(k, text));
  } catch (e) {
    patch(k, tmp.id, (m) => ({ ...m, pending: false, data: { failed: true, error: (e as Error).message } }));
  }
}

export function connect() {
  return liveEvents((e) => {
    switch (e.type) {
      case "presence":
        set((s) => ({ presence: { ...s.presence, [e.botId!]: e.data } }));
        break;
      case "message":
        upsertMessage({ ...e.data, streaming: e.data.role === "bot" });
        break;
      case "message_update":
        upsertMessage(e.data);
        break;
      case "delta":
        patch(e.groupId ? "g:" + e.groupId : e.botId!, e.data.id, (m) => ({ ...m, content: m.content + e.data.text }));
        break;
      case "message_done":
        patch(keyOf(e.data), e.data.id, (m) => ({ ...m, content: e.data.content, streaming: false }));
        loadAll().catch(() => {});
        break;
      case "reset":
        loadAll().catch(() => {});
        Object.keys(get().messages).forEach((k) => loadMessages(k).catch(() => {}));
        break;
      case "bot":
      case "group":
      case "bot_removed":
      case "group_removed":
        loadAll().catch(() => {});
    }
  });
}

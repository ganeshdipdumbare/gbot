import { useSyncExternalStore } from "react";
import { api, type Bot, type Message, type Model, type Presence, type User } from "./api";

export interface State {
  me: User | null;
  bots: Bot[];
  presence: Record<string, Presence>;
  messages: Record<string, Message[]>;
  activeId: string | null;
  models: Model[] | null;
  connected: boolean;
}

function savedActive() {
  try { return localStorage.getItem("gbot.active"); } catch { return null; }
}

let state: State = { me: null, bots: [], presence: {}, messages: {}, activeId: savedActive(), models: null, connected: false };
const listeners = new Set<() => void>();

export function set(fn: (s: State) => Partial<State>) {
  state = { ...state, ...fn(state) };
  listeners.forEach((l) => l());
}
export const get = () => state;

export function useStore<T>(sel: (s: State) => T): T {
  return useSyncExternalStore((l) => (listeners.add(l), () => listeners.delete(l)), () => sel(state));
}

const sortBots = (bots: Bot[]) =>
  [...bots].sort((a, b) =>
    a.pinned !== b.pinned ? (a.pinned ? -1 : 1) :
    (b.lastMessage?.createdAt ?? b.createdAt).localeCompare(a.lastMessage?.createdAt ?? a.createdAt));

export function upsertBot(bot: Bot) {
  set((s) => {
    const old = s.bots.find((b) => b.id === bot.id);
    const merged = { ...old, ...bot, lastMessage: bot.lastMessage ?? old?.lastMessage };
    return { bots: sortBots(old ? s.bots.map((b) => (b.id === bot.id ? merged : b)) : [...s.bots, merged]) };
  });
}

function touchLast(botId: string, m: Message) {
  if (m.role === "event") return;
  set((s) => ({ bots: sortBots(s.bots.map((b) => (b.id === botId ? { ...b, lastMessage: m } : b))) }));
}

export function addMessage(m: Message) {
  set((s) => {
    const list = s.messages[m.botId];
    if (!list) return {};
    if (list.some((x) => x.id === m.id)) return {};
    // Swap an optimistic copy for the real one.
    const i = m.role === "user" ? list.findIndex((x) => x.pending && x.content === m.content) : -1;
    const next = i >= 0 ? list.map((x, j) => (j === i ? m : x)) : [...list, m];
    return { messages: { ...s.messages, [m.botId]: next } };
  });
  touchLast(m.botId, m);
}

function patchMessage(botId: string, id: string, fn: (m: Message) => Message) {
  set((s) => {
    const list = s.messages[botId];
    if (!list) return {};
    return { messages: { ...s.messages, [botId]: list.map((m) => (m.id === id ? fn(m) : m)) } };
  });
}

export async function loadBots() {
  const { bots, presence } = await api.bots();
  set((s) => ({ bots: sortBots(bots), presence, activeId: s.activeId && bots.some((b) => b.id === s.activeId) ? s.activeId : bots[0]?.id ?? null }));
}

export async function loadMessages(botId: string) {
  const { messages } = await api.messages(botId);
  set((s) => ({ messages: { ...s.messages, [botId]: messages } }));
}

export async function sendMessage(botId: string, text: string) {
  const tmp: Message = { id: "tmp-" + Math.random().toString(36).slice(2), botId, role: "user", content: text, createdAt: new Date().toISOString(), pending: true };
  set((s) => ({
    messages: { ...s.messages, [botId]: [...(s.messages[botId] ?? []), tmp] },
    presence: { ...s.presence, [botId]: { state: "thinking" } },
  }));
  touchLast(botId, tmp);
  try {
    const real = await api.send(botId, text);
    set((s) => {
      const list = s.messages[botId] ?? [];
      const has = list.some((m) => m.id === real.id);
      return { messages: { ...s.messages, [botId]: has ? list.filter((m) => m.id !== tmp.id) : list.map((m) => (m.id === tmp.id ? real : m)) } };
    });
  } catch (e) {
    patchMessage(botId, tmp.id, (m) => ({ ...m, pending: false, data: { failed: true, error: (e as Error).message } }));
    set((s) => ({ presence: { ...s.presence, [botId]: { state: "idle" } } }));
  }
}

type LiveEvent =
  | { type: "presence"; botId: string; data: Presence }
  | { type: "message"; botId: string; data: Message }
  | { type: "delta"; botId: string; data: { id: string; text: string } }
  | { type: "message_done"; botId: string; data: Message }
  | { type: "bot"; botId: string; data: Bot }
  | { type: "bot_removed"; botId: string };

export function connectLive() {
  let es: EventSource | null = null;
  let hadError = false;
  const open = () => {
    es = new EventSource("/api/events");
    es.onopen = () => {
      set(() => ({ connected: true }));
      if (hadError) {
        // Catch up on anything missed while disconnected.
        loadBots().catch(() => {});
        const id = get().activeId;
        if (id) loadMessages(id).catch(() => {});
      }
    };
    es.onerror = () => { hadError = true; set(() => ({ connected: false })); };
    es.onmessage = (ev) => {
      const e = JSON.parse(ev.data) as LiveEvent;
      switch (e.type) {
        case "presence":
          set((s) => ({ presence: { ...s.presence, [e.botId]: e.data } }));
          break;
        case "message":
          addMessage({ ...e.data, streaming: e.data.role === "bot" });
          break;
        case "delta":
          patchMessage(e.botId, e.data.id, (m) => ({ ...m, content: m.content + e.data.text }));
          break;
        case "message_done":
          patchMessage(e.botId, e.data.id, (m) => ({ ...m, content: e.data.content, streaming: false }));
          touchLast(e.botId, e.data);
          break;
        case "bot":
          upsertBot(e.data);
          break;
        case "bot_removed":
          set((s) => {
            const bots = s.bots.filter((b) => b.id !== e.botId);
            return { bots, activeId: s.activeId === e.botId ? bots[0]?.id ?? null : s.activeId };
          });
      }
    };
  };
  open();
  return () => es?.close();
}

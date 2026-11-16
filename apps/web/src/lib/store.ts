import { useSyncExternalStore } from "react";
import { askToNotify, notify } from "./notify";
import { api, type Bot, type ComputerStatus, type Group, type Message, type Model, type Presence, type QueueItem, type User } from "./api";

// A chat key is a bot id (its own chat) or "g:<group id>".
export type ChatKey = string;
export const groupKey = (id: string) => "g:" + id;
export const isGroupKey = (k: string | null) => !!k && k.startsWith("g:");

export interface State {
  me: User | null;
  bots: Bot[];
  groups: Group[];
  presence: Record<string, Presence>;
  computers: Record<string, ComputerStatus>;
  messages: Record<ChatKey, Message[]>;
  activeId: ChatKey | null;
  models: Model[] | null;
  connected: boolean;
  loaded: boolean;                       // first roster load finished
  unread: Record<ChatKey, number>;       // new replies in chats you're not looking at
  hasMore: Record<ChatKey, boolean>;     // older messages exist on the server
  queue: Record<ChatKey, QueueItem[]>;   // messages waiting for a bot
}

function savedActive() {
  try { return localStorage.getItem("gbot.active"); } catch { return null; }
}

let state: State = { me: null, bots: [], groups: [], presence: {}, computers: {}, messages: {}, activeId: savedActive(), models: null, connected: false, loaded: false, unread: {}, hasMore: {}, queue: {} };
const listeners = new Set<() => void>();

export function set(fn: (s: State) => Partial<State>) {
  state = { ...state, ...fn(state) };
  listeners.forEach((l) => l());
}
export const get = () => state;

export function useStore<T>(sel: (s: State) => T): T {
  return useSyncExternalStore((l) => (listeners.add(l), () => listeners.delete(l)), () => sel(state));
}

const lastAt = (x: { lastMessage?: Message; createdAt: string }) => x.lastMessage?.createdAt ?? x.createdAt;
const sortBots = (bots: Bot[]) =>
  [...bots].sort((a, b) => (a.pinned !== b.pinned ? (a.pinned ? -1 : 1) : lastAt(b).localeCompare(lastAt(a))));
const sortGroups = (gs: Group[]) => [...gs].sort((a, b) => lastAt(b).localeCompare(lastAt(a)));

export function upsertBot(bot: Bot) {
  set((s) => {
    const old = s.bots.find((b) => b.id === bot.id);
    const merged = { ...old, ...bot, lastMessage: bot.lastMessage ?? old?.lastMessage };
    return { bots: sortBots(old ? s.bots.map((b) => (b.id === bot.id ? merged : b)) : [...s.bots, merged]) };
  });
}

export function upsertGroup(g: Group) {
  set((s) => {
    const old = s.groups.find((x) => x.id === g.id);
    const merged = { ...old, ...g, lastMessage: g.lastMessage ?? old?.lastMessage };
    return { groups: sortGroups(old ? s.groups.map((x) => (x.id === g.id ? merged : x)) : [...s.groups, merged]) };
  });
}

export const keyOf = (m: Message): ChatKey => (m.groupId ? groupKey(m.groupId) : m.botId!);

function touchLast(m: Message) {
  if (m.role !== "user" && m.role !== "bot") return;
  if (m.groupId) set((s) => ({ groups: sortGroups(s.groups.map((g) => (g.id === m.groupId ? { ...g, lastMessage: m } : g))) }));
  else set((s) => ({ bots: sortBots(s.bots.map((b) => (b.id === m.botId ? { ...b, lastMessage: m } : b))) }));
}

export function addMessage(m: Message) {
  const k = keyOf(m);
  set((s) => {
    const list = s.messages[k];
    if (!list) return {};
    if (list.some((x) => x.id === m.id)) return {};
    // Swap an optimistic copy for the real one, but only when it is
    // unambiguous: two identical in-flight sends could otherwise be paired
    // with the wrong one. The send's own response reconciles the rest.
    const candidates = m.role === "user" ? list.filter((x) => x.pending && x.content === m.content) : [];
    const i = candidates.length === 1 ? list.indexOf(candidates[0]) : -1;
    const next = i >= 0 ? list.map((x, j) => (j === i ? m : x)) : [...list, m];
    return { messages: { ...s.messages, [k]: next } };
  });
  touchLast(m);
  // Count replies that land outside the open chat (or while the tab is hidden).
  if ((m.role === "bot" || m.role === "card") && (k !== get().activeId || document.visibilityState === "hidden")) {
    set((s) => ({ unread: { ...s.unread, [k]: (s.unread[k] ?? 0) + 1 } }));
  }
}

export function markRead(k: ChatKey | null) {
  if (k && get().unread[k]) set((s) => { const u = { ...s.unread }; delete u[k]; return { unread: u }; });
}

export function patchMessage(k: ChatKey, id: string, fn: (m: Message) => Message) {
  set((s) => {
    const list = s.messages[k];
    if (!list) return {};
    return { messages: { ...s.messages, [k]: list.map((m) => (m.id === id ? fn(m) : m)) } };
  });
}

// When each bot's status last changed through a live event. A bot list
// fetched before that moment carries an older status and must not replace
// it, or a bot that has finished looks busy until its next change.
const presenceAt: Record<string, number> = {};

function freshPresence(snapshot: Record<string, Presence>, current: Record<string, Presence>, since: number) {
  const out = { ...snapshot };
  for (const id of Object.keys(presenceAt)) {
    if (presenceAt[id] < since) continue;
    if (current[id]) out[id] = current[id];
    else delete out[id];
  }
  return out;
}

export async function loadAll() {
  const since = Date.now();
  const [{ bots, presence }, { groups }] = await Promise.all([api.bots(), api.groups()]);
  set((s) => {
    const keys = new Set([...bots.map((b) => b.id), ...groups.map((g) => groupKey(g.id))]);
    return { bots: sortBots(bots), groups: sortGroups(groups), presence: freshPresence(presence, s.presence, since), loaded: true, activeId: s.activeId && keys.has(s.activeId) ? s.activeId : bots[0]?.id ?? null };
  });
}

const PAGE = 100;

export async function loadMessages(k: ChatKey) {
  const { messages } = isGroupKey(k) ? await api.groupMessages(k.slice(2)) : await api.messages(k);
  set((s) => ({ messages: { ...s.messages, [k]: messages }, hasMore: { ...s.hasMore, [k]: messages.length >= PAGE } }));
}

/** Loads the page before the oldest message we have; returns how many arrived. */
export async function loadOlder(k: ChatKey): Promise<number> {
  const list = get().messages[k];
  if (!list?.length || !get().hasMore[k]) return 0;
  const before = list[0].createdAt;
  const { messages } = isGroupKey(k) ? await api.groupMessages(k.slice(2), before) : await api.messages(k, before);
  set((s) => {
    const cur = s.messages[k] ?? [];
    const seen = new Set(cur.map((m) => m.id));
    return { messages: { ...s.messages, [k]: [...messages.filter((m) => !seen.has(m.id)), ...cur] }, hasMore: { ...s.hasMore, [k]: messages.length >= PAGE } };
  });
  return messages.length;
}

export async function loadQueue(k: ChatKey) {
  const { items } = isGroupKey(k) ? await api.groupQueue(k.slice(2)) : await api.queue(k);
  set((s) => ({ queue: { ...s.queue, [k]: items } }));
}

function setQueue(k: ChatKey, items: QueueItem[]) {
  set((s) => ({ queue: { ...s.queue, [k]: items } }));
}

export async function queueAction(k: ChatKey, qid: string, action: "pause" | "resume" | "cancel") {
  const { items } = await api.queueAction(qid, action);
  setQueue(k, items);
}

/** Stops the current reply and drops everything waiting. */
// A bot's chat was cleared, here or on another device: show it empty.
export function clearChat(botId: string) {
  set((s) => ({
    messages: { ...s.messages, [botId]: [] },
    queue: { ...s.queue, [botId]: [] },
    hasMore: { ...s.hasMore, [botId]: false },
    bots: s.bots.map((b) => (b.id === botId ? { ...b, lastMessage: undefined } : b)),
  }));
}

export async function clearQueue(k: ChatKey) {
  const { items } = isGroupKey(k) ? await api.clearGroupQueue(k.slice(2)) : await api.clearQueue(k);
  setQueue(k, items);
}

/** Resends a message that failed to send. */
export function retry(k: ChatKey, m: Message) {
  set((s) => ({ messages: { ...s.messages, [k]: (s.messages[k] ?? []).filter((x) => x.id !== m.id) } }));
  return sendMessage(k, m.content);
}

export async function sendMessage(k: ChatKey, text: string) {
  askToNotify();
  const group = isGroupKey(k) ? k.slice(2) : undefined;
  const tmp: Message = { id: "tmp-" + Math.random().toString(36).slice(2), botId: group ? undefined : k, groupId: group, role: "user", content: text, createdAt: new Date().toISOString(), pending: true };
  set((s) => ({ messages: { ...s.messages, [k]: [...(s.messages[k] ?? []), tmp] } }));
  if (!group) set((s) => ({ presence: { ...s.presence, [k]: { ...s.presence[k], state: "thinking" } } }));
  touchLast(tmp);
  try {
    const real = group ? await api.sendGroup(group, text) : await api.send(k, text);
    set((s) => {
      const list = s.messages[k] ?? [];
      const has = list.some((m) => m.id === real.id);
      const placeholder = list.some((m) => m.id === tmp.id);
      if (has) return { messages: { ...s.messages, [k]: list.filter((m) => m.id !== tmp.id) } };
      // The placeholder may already have been swapped for another copy.
      if (!placeholder) return { messages: { ...s.messages, [k]: [...list, real] } };
      return { messages: { ...s.messages, [k]: list.map((m) => (m.id === tmp.id ? real : m)) } };
    });
    loadQueue(k).catch(() => {});
  } catch (e) {
    patchMessage(k, tmp.id, (m) => ({ ...m, pending: false, data: { failed: true, error: (e as Error).message } }));
    if (!group) set((s) => ({ presence: { ...s.presence, [k]: { state: "idle" } } }));
  }
}

type LiveEvent =
  | { type: "presence"; botId: string; data: Presence }
  | { type: "message" | "message_done" | "message_update"; botId?: string; groupId?: string; data: Message }
  | { type: "delta"; botId: string; groupId?: string; data: { id: string; text: string } }
  | { type: "bot"; botId: string; data: Bot }
  | { type: "bot_removed"; botId: string }
  | { type: "cleared"; botId: string }
  | { type: "group"; groupId: string; data: Group }
  | { type: "group_removed"; groupId: string }
  | { type: "queue"; botId?: string; groupId?: string; data: QueueItem[] }
  | { type: "routine"; botId: string }
  | { type: "computer"; botId: string; data: ComputerStatus };

type Listener = (e: LiveEvent) => void;
const extra = new Set<Listener>();
export function onLive(fn: Listener) { extra.add(fn); return () => { extra.delete(fn); }; }

function handle(e: LiveEvent) {
  extra.forEach((fn) => fn(e));
  switch (e.type) {
    case "presence": {
      const prev = get().presence[e.botId]?.state;
      presenceAt[e.botId] = Date.now();
      set((s) => ({ presence: { ...s.presence, [e.botId]: e.data } }));
      const b = get().bots.find((x) => x.id === e.botId);
      const open = () => set(() => ({ activeId: e.botId }));
      if (b && e.data.state === "waiting" && prev !== "waiting") notify(`${b.name} needs you`, e.data.detail ?? "Open gbot to answer.", open);
      if (b && e.data.state === "done" && prev && prev !== "done") notify(`${b.name} is done`, b.lastMessage?.content.slice(0, 120) ?? "", open);
      break;
    }
    case "message":
      addMessage({ ...e.data, streaming: e.data.role === "bot" });
      break;
    case "delta": {
      const k = e.groupId ? groupKey(e.groupId) : e.botId;
      patchMessage(k, e.data.id, (m) => ({ ...m, content: m.content + e.data.text }));
      break;
    }
    case "message_done":
      patchMessage(keyOf(e.data), e.data.id, (m) => ({ ...m, content: e.data.content, streaming: false }));
      touchLast(e.data);
      break;
    case "message_update":
      patchMessage(keyOf(e.data), e.data.id, () => e.data);
      break;
    case "bot":
      upsertBot(e.data);
      break;
    case "bot_removed":
      set((s) => {
        const bots = s.bots.filter((b) => b.id !== e.botId);
        return { bots, activeId: s.activeId === e.botId ? bots[0]?.id ?? null : s.activeId };
      });
      break;
    case "cleared":
      clearChat(e.botId);
      break;
    case "group":
      upsertGroup(e.data);
      break;
    case "group_removed":
      set((s) => ({ groups: s.groups.filter((g) => g.id !== e.groupId), activeId: s.activeId === groupKey(e.groupId) ? s.bots[0]?.id ?? null : s.activeId }));
      break;
    case "queue":
      set((s) => ({ queue: { ...s.queue, [e.groupId ? groupKey(e.groupId) : e.botId!]: e.data } }));
      break;
    case "computer":
      set((s) => ({ computers: { ...s.computers, [e.botId]: e.data } }));
  }
}

function catchUp() {
  loadAll().catch(() => {});
  const id = get().activeId;
  if (id) {
    loadMessages(id).catch(() => {});
    loadQueue(id).catch(() => {});
  }
}

// Live updates use a server-sent event stream. Some proxies and tunnels
// (e.g. Cloudflare quick tunnels) hold a stream back until it ends, so if
// the server's hello doesn't arrive promptly we switch to long-polling,
// which works through anything.
export function connectLive() {
  let stopped = false;
  let es: EventSource | null = new EventSource("/api/events");
  let hadError = false;
  let gotHello = false;
  const fallback = setTimeout(() => {
    if (gotHello || stopped) return;
    es?.close();
    es = null;
    longPoll();
  }, 4000);

  es.onopen = () => {
    if (hadError && gotHello) catchUp();
  };
  es.onerror = () => { hadError = true; if (gotHello) set(() => ({ connected: false })); };
  es.onmessage = (ev) => {
    let e: LiveEvent | { type: "hello" };
    try {
      e = JSON.parse(ev.data) as LiveEvent | { type: "hello" };
    } catch {
      return; // a malformed frame is dropped; the next catch-up resyncs
    }
    if (e.type === "hello") {
      gotHello = true;
      set(() => ({ connected: true }));
      return;
    }
    handle(e as LiveEvent);
  };

  async function longPoll() {
    let after = 0;
    while (!stopped) {
      try {
        const res = await fetch(`/api/events/poll?after=${after}`, { credentials: "same-origin" });
        if (res.status === 401) {
          // Same as every other request: an expired session signs the person out.
          window.dispatchEvent(new Event("gbot:signed-out"));
          return;
        }
        if (!res.ok) throw new Error(String(res.status));
        const { events, next, reset } = (await res.json()) as { events: LiveEvent[]; next: number; reset: boolean };
        set(() => ({ connected: true }));
        // A reset means we missed events (or just started): refetch.
        if (reset) catchUp();
        events.forEach(handle);
        after = next;
      } catch {
        set(() => ({ connected: false }));
        await new Promise((r) => setTimeout(r, 2000));
      }
    }
  }

  return () => { stopped = true; clearTimeout(fallback); es?.close(); };
}

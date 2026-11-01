import * as SecureStore from "expo-secure-store";
import EventSource from "react-native-sse";
import type { Look } from "../components/Gbot";

// The phone talks to your own gbot server. The session cookie lives in
// the OS cookie store; the server address is kept in the keychain.

export interface User { id: string; name: string; email: string; approvalMode: string }
export interface Message { id: string; botId?: string; groupId?: string; role: "user" | "bot" | "event" | "card"; content: string; data?: Record<string, unknown>; createdAt: string; streaming?: boolean; pending?: boolean }
export interface Bot { id: string; name: string; title: string; look: Look; computerNetwork: boolean; lastMessage?: Message; createdAt: string }
export interface Group { id: string; name: string; botIds: string[]; lastMessage?: Message; createdAt: string }
export interface Presence { state: string; detail?: string; live?: boolean }
export interface Routine { id: string; name: string; prompt: string; when: string; active: boolean; nextRunAt?: string; kind: string }

let base = "http://127.0.0.1:8080";
export const getBase = () => base;
export async function loadBase() {
  base = (await SecureStore.getItemAsync("server")) ?? base;
  return base;
}
export async function setBase(url: string) {
  base = url.replace(/\/$/, "");
  await SecureStore.setItemAsync("server", base);
}

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

async function req<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(base + path, {
    method,
    credentials: "include",
    headers: method === "GET" ? {} : { "Content-Type": "application/json", "X-GBot": "1" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(res.status, data.error ?? "Something went wrong");
  return data as T;
}

export const api = {
  me: () => req<User>("GET", "/api/me"),
  login: (email: string, password: string) => req<User>("POST", "/api/auth/login", { email, password }),
  logout: () => req("POST", "/api/auth/logout", {}),
  bots: () => req<{ bots: Bot[]; presence: Record<string, Presence> }>("GET", "/api/bots"),
  groups: () => req<{ groups: Group[] }>("GET", "/api/groups"),
  messages: (k: string) => req<{ messages: Message[] }>("GET", k.startsWith("g:") ? `/api/groups/${k.slice(2)}/messages` : `/api/bots/${k}/messages`),
  send: (k: string, text: string) => req<Message>("POST", k.startsWith("g:") ? `/api/groups/${k.slice(2)}/messages` : `/api/bots/${k}/messages`, { text }),
  stop: (k: string) => req("POST", k.startsWith("g:") ? `/api/groups/${k.slice(2)}/stop` : `/api/bots/${k}/stop`, {}),
  answer: (id: string, decision: string, text?: string) => req<Message>("POST", `/api/cards/${id}`, { decision, text }),
  routines: (id: string) => req<{ routines: Routine[] }>("GET", `/api/bots/${id}/routines`),
  setRoutine: (id: string, rid: string, active: boolean) => req<Routine>("PATCH", `/api/bots/${id}/routines/${rid}`, { active }),
  deleteRoutine: (id: string, rid: string) => req("DELETE", `/api/bots/${id}/routines/${rid}`),
  computer: (id: string) => req<{ state: string; controller: string }>("GET", `/api/bots/${id}/computer`),
  screen: (id: string) => `${base}/api/bots/${id}/computer/screen?t=${Date.now()}`,
};

// Live updates: an event stream, or long-polling when the network path
// holds the stream back (Cloudflare quick tunnels do). A "reset" event
// tells the store to refetch.
export function liveEvents(onEvent: (e: { type: string; botId?: string; groupId?: string; data: any }) => void) {
  let stopped = false;
  let gotHello = false;
  let es: EventSource | null = new EventSource(base + "/api/events", { withCredentials: true, pollingInterval: 1500 });
  es.addEventListener("message", (ev) => {
    if (!ev.data) return;
    try {
      const e = JSON.parse(ev.data);
      if (e.type === "hello") { gotHello = true; return; }
      onEvent(e);
    } catch { /* ignore */ }
  });
  const fallback = setTimeout(async () => {
    if (gotHello || stopped) return;
    es?.close();
    es = null;
    let after = 0;
    while (!stopped) {
      try {
        const res = await fetch(`${base}/api/events/poll?after=${after}`, { credentials: "include" });
        if (!res.ok) throw new Error(String(res.status));
        const { events, next, reset } = await res.json();
        if (reset) onEvent({ type: "reset", data: null });
        for (const e of events) onEvent(e);
        after = next;
      } catch {
        await new Promise((r) => setTimeout(r, 2000));
      }
    }
  }, 4000);
  return () => { stopped = true; clearTimeout(fallback); es?.close(); };
}

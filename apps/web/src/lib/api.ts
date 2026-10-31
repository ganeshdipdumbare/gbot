import type { Look } from "@gbot/characters";

export interface User { id: string; email: string; name: string; defaultModel: string; spaceId: string }
export interface Message {
  id: string; botId: string; role: "user" | "bot" | "event"; content: string;
  data?: Record<string, unknown>; createdAt: string; pending?: boolean; streaming?: boolean;
}
export interface Bot {
  id: string; spaceId: string; name: string; title: string; instructions: string; model: string;
  look: Look; pinned: boolean; createdAt: string; lastMessage?: Message;
}
export interface Presence { state: "idle" | "thinking" | "working" | "waiting" | "blocked" | "done"; detail?: string; queued?: number }
export interface Model { id: string; label: string; provider: string; local: boolean; tools: boolean; caps?: string[] }

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

async function req<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    credentials: "same-origin",
    headers: method === "GET" ? {} : { "Content-Type": "application/json", "X-GBot": "1" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(res.status, data.error ?? "Something went wrong");
  return data as T;
}

export const api = {
  setup: () => req<{ fresh: boolean; signup: boolean }>("GET", "/api/setup"),
  signup: (b: { email: string; password: string; name?: string }) => req<User>("POST", "/api/auth/signup", b),
  login: (b: { email: string; password: string }) => req<User>("POST", "/api/auth/login", b),
  logout: () => req("POST", "/api/auth/logout", {}),
  me: () => req<User>("GET", "/api/me"),
  updateMe: (b: { defaultModel: string }) => req<User>("PATCH", "/api/me", b),
  models: () => req<{ models: Model[]; ollamaHost: string }>("GET", "/api/models"),
  bots: () => req<{ bots: Bot[]; presence: Record<string, Presence> }>("GET", "/api/bots"),
  createBot: (b: Partial<Bot>) => req<Bot>("POST", "/api/bots", b),
  updateBot: (id: string, b: Partial<Bot>) => req<Bot>("PATCH", `/api/bots/${id}`, b),
  archiveBot: (id: string) => req("DELETE", `/api/bots/${id}`),
  messages: (id: string, before?: string) =>
    req<{ messages: Message[] }>("GET", `/api/bots/${id}/messages${before ? `?before=${encodeURIComponent(before)}` : ""}`),
  send: (id: string, text: string) => req<Message>("POST", `/api/bots/${id}/messages`, { text }),
  stop: (id: string) => req("POST", `/api/bots/${id}/stop`, {}),
};

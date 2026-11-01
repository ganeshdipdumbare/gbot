import type { Look } from "@gbot/characters";

export interface User { id: string; email: string; name: string; defaultModel: string; approvalMode: ApprovalMode; spaceId: string }
export type ApprovalMode = "ask" | "auto" | "never";
export interface Message {
  id: string; botId?: string; groupId?: string; role: "user" | "bot" | "event" | "card"; content: string;
  data?: Record<string, unknown>; runId?: string; createdAt: string; pending?: boolean; streaming?: boolean;
}
export interface Bot {
  id: string; spaceId: string; name: string; title: string; instructions: string; model: string;
  look: Look; pinned: boolean; computerNetwork: boolean; voice: string; readAloud: boolean;
  createdAt: string; lastMessage?: Message;
}
export interface Group { id: string; spaceId: string; name: string; botIds: string[]; createdAt: string; lastMessage?: Message }
export interface Presence { state: "idle" | "thinking" | "working" | "waiting" | "blocked" | "done"; detail?: string; queued?: number; live?: boolean }
export interface Model { id: string; label: string; provider: string; local: boolean; tools: boolean; caps?: string[] }
export interface ComputerStatus { state: "off" | "starting" | "on"; controller: "bot" | "user"; network: boolean; available: boolean; backend: "microsandbox" | "docker" }
export interface Routine {
  id: string; botId: string; name: string; prompt: string; kind: "schedule" | "webhook"; schedule?: string; when: string;
  active: boolean; nextRunAt?: string; lastRunAt?: string; webhookUrl?: string; secret?: string;
}
export interface ToolRule { tool: string; rule: "allow" | "block" }
export interface MCPServer { id: string; name: string; url: string; hasAuth: boolean; enabled: boolean; status: string; tools: string[] }
export interface Skill { id: string; name: string; description: string; body: string }
export interface VoiceInfo { transcribe: boolean; speak: boolean; transcribeBy?: string; speakBy?: string }

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

const bot = (id: string) => `/api/bots/${id}`;

export const api = {
  setup: () => req<{ fresh: boolean; signup: boolean }>("GET", "/api/setup"),
  signup: (b: { email: string; password: string; name?: string }) => req<User>("POST", "/api/auth/signup", b),
  login: (b: { email: string; password: string }) => req<User>("POST", "/api/auth/login", b),
  logout: () => req("POST", "/api/auth/logout", {}),
  me: () => req<User>("GET", "/api/me"),
  updateMe: (b: { defaultModel?: string; approvalMode?: ApprovalMode }) => req<User>("PATCH", "/api/me", b),
  models: () => req<{ models: Model[]; ollamaHost: string }>("GET", "/api/models"),
  rules: () => req<{ rules: ToolRule[] }>("GET", "/api/settings/rules"),
  setRule: (tool: string, rule: "allow" | "block" | "") => req<{ rules: ToolRule[] }>("PUT", "/api/settings/rules", { tool, rule }),

  bots: () => req<{ bots: Bot[]; presence: Record<string, Presence> }>("GET", "/api/bots"),
  createBot: (b: Partial<Bot>) => req<Bot>("POST", "/api/bots", b),
  updateBot: (id: string, b: Partial<Bot>) => req<Bot>("PATCH", bot(id), b),
  archiveBot: (id: string) => req("DELETE", bot(id)),
  messages: (id: string) => req<{ messages: Message[] }>("GET", `${bot(id)}/messages`),
  send: (id: string, text: string) => req<Message>("POST", `${bot(id)}/messages`, { text }),
  stop: (id: string) => req("POST", `${bot(id)}/stop`, {}),
  answer: (cardId: string, b: { decision: "approve" | "reject" | "answer"; text?: string; always?: boolean }) =>
    req<Message>("POST", `/api/cards/${cardId}`, b),

  computer: (id: string) => req<ComputerStatus>("GET", `${bot(id)}/computer`),
  computerAction: (id: string, action: "start" | "stop" | "control" | "input" | "show", body: unknown = {}) =>
    req<ComputerStatus>("POST", `${bot(id)}/computer/${action}`, body),
  screenUrl: (id: string) => `${bot(id)}/computer/screen?t=${Date.now()}`,

  routines: (id: string) => req<{ routines: Routine[] }>("GET", `${bot(id)}/routines`),
  createRoutine: (id: string, b: { name: string; prompt: string; kind: string; schedule?: string }) => req<Routine>("POST", `${bot(id)}/routines`, b),
  updateRoutine: (id: string, rid: string, b: { active: boolean }) => req<Routine>("PATCH", `${bot(id)}/routines/${rid}`, b),
  deleteRoutine: (id: string, rid: string) => req("DELETE", `${bot(id)}/routines/${rid}`),
  runRoutine: (id: string, rid: string) => req("POST", `${bot(id)}/routines/${rid}/run`, {}),

  mcp: () => req<{ servers: MCPServer[] }>("GET", "/api/mcp"),
  addMcp: (b: { name: string; url: string; token?: string }) => req<MCPServer>("POST", "/api/mcp", b),
  toggleMcp: (id: string, enabled: boolean) => req<MCPServer>("PATCH", `/api/mcp/${id}`, { enabled }),
  deleteMcp: (id: string) => req("DELETE", `/api/mcp/${id}`),
  skills: () => req<{ skills: Skill[] }>("GET", "/api/skills"),
  saveSkill: (b: { name: string; description: string; body: string }) => req<Skill>("POST", "/api/skills", b),
  deleteSkill: (id: string) => req("DELETE", `/api/skills/${id}`),

  groups: () => req<{ groups: Group[] }>("GET", "/api/groups"),
  createGroup: (b: { name: string; botIds: string[] }) => req<Group>("POST", "/api/groups", b),
  archiveGroup: (id: string) => req("DELETE", `/api/groups/${id}`),
  groupMessages: (id: string) => req<{ messages: Message[] }>("GET", `/api/groups/${id}/messages`),
  sendGroup: (id: string, text: string) => req<Message>("POST", `/api/groups/${id}/messages`, { text }),
  stopGroup: (id: string) => req("POST", `/api/groups/${id}/stop`, {}),

  voice: () => req<VoiceInfo>("GET", "/api/voice"),
  transcribe: async (audio: Blob) => {
    const res = await fetch("/api/voice/transcribe", { method: "POST", headers: { "Content-Type": audio.type || "audio/webm", "X-GBot": "1" }, body: audio });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new ApiError(res.status, data.error ?? "Couldn't transcribe");
    return data as { text: string };
  },
  speak: async (text: string, voice?: string) => {
    const res = await fetch("/api/voice/speak", { method: "POST", headers: { "Content-Type": "application/json", "X-GBot": "1" }, body: JSON.stringify({ text, voice }) });
    if (!res.ok) throw new ApiError(res.status, "Couldn't read aloud");
    return res.blob();
  },
};

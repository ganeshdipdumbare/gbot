import type { Look } from "@gbot/characters";

export interface User { id: string; email: string; name: string; defaultModel: string; approvalMode: ApprovalMode; spaceId: string }
export type ApprovalMode = "ask" | "auto" | "never";
export interface Message {
  id: string; botId?: string; groupId?: string; role: "user" | "bot" | "event" | "card"; content: string;
  data?: Record<string, unknown>; runId?: string; createdAt: string; pending?: boolean; streaming?: boolean;
}
export interface Bot {
  id: string; spaceId: string; ownerId: string; name: string; title: string; instructions: string; model: string;
  look: Look; pinned: boolean; computerNetwork: boolean; allowedHosts: string[]; voice: string; readAloud: boolean;
  /** The person's main bot: first in the roster, wears the app's mark, can't be deleted. */
  main: boolean;
  visibility: Visibility; role?: BotRole; createdAt: string; lastMessage?: Message;
}
export type Visibility = "private" | "team" | "invited";
export type BotRole = "owner" | "manager" | "collaborator";
export interface Member { userId: string; email: string; name: string; role: string }
export interface Sharing { visibility: Visibility; members: Member[]; spaceMembers: Member[] }
export interface Space { id: string; name: string; role: "owner" | "member" }
export interface Group { id: string; spaceId: string; userId: string; name: string; botIds: string[]; createdAt: string; lastMessage?: Message }
export interface Presence { state: "idle" | "thinking" | "working" | "waiting" | "blocked" | "done"; detail?: string; queued?: number; live?: boolean }
export interface Model { id: string; label: string; provider: string; local: boolean; tools: boolean; caps?: string[]; best?: boolean }
export interface QueueItem { id: string; prompt: string; status: "queued" | "paused" | "running" | "waiting" | "done" | "stopped" | "error" | "cancelled"; error?: string; createdAt: string }
export type ProviderKind = "openai" | "anthropic" | "gemini" | "codex";
export interface CodexLogin { available: boolean; allowed: boolean }
export interface Provider {
  id: string; name: string; kind: ProviderKind; baseUrl: string; hasKey: boolean;
  local: boolean; models: string[]; caps?: Record<string, string[]>; enabled: boolean; status: string; createdAt: string;
}
export interface ProviderInput {
  name: string; kind: ProviderKind; baseUrl: string; apiKey?: string; local?: boolean;
  models?: string[]; enabled?: boolean; removeKey?: boolean;
}
export interface ComputerStatus { state: "off" | "starting" | "asleep" | "on"; controller: "bot" | "user"; network: boolean; available: boolean; backend: "microsandbox" | "docker" }
export interface Routine {
  id: string; botId: string; name: string; prompt: string; kind: "schedule" | "webhook"; schedule?: string; when: string;
  active: boolean; nextRunAt?: string; lastRunAt?: string; webhookUrl?: string; secret?: string;
}
export interface ToolRule { tool: string; rule: "allow" | "block" }
export interface MCPServer { id: string; name: string; url: string; hasAuth: boolean; oauth: boolean; enabled: boolean; status: string; tools: string[] }
export interface Skill { id: string; name: string; description: string; body: string }
export interface VaultSecret { id: string; name: string; origins: string[]; updatedAt: string }
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
  if (!res.ok) {
    // A session that expired mid-use signs the person out cleanly.
    if (res.status === 401 && !path.startsWith("/api/auth/") && path !== "/api/me") window.dispatchEvent(new Event("gbot:signed-out"));
    throw new ApiError(res.status, data.error ?? "Something went wrong");
  }
  return data as T;
}

const bot = (id: string) => `/api/bots/${id}`;

export const api = {
  setup: () => req<{ fresh: boolean; signup: boolean; setupToken?: boolean }>("GET", "/api/setup"),
  signup: (b: { email: string; password: string; name?: string; setupToken?: string }) => req<User>("POST", "/api/auth/signup", b),
  login: (b: { email: string; password: string }) => req<User>("POST", "/api/auth/login", b),
  logout: () => req("POST", "/api/auth/logout", {}),
  me: () => req<User>("GET", "/api/me"),
  updateMe: (b: { defaultModel?: string; approvalMode?: ApprovalMode }) => req<User>("PATCH", "/api/me", b),
  models: () => req<{ models: Model[]; ollamaHost: string }>("GET", "/api/models"),
  providers: () => req<{ providers: Provider[]; codex?: CodexLogin }>("GET", "/api/providers"),
  createProvider: (b: ProviderInput) => req<Provider>("POST", "/api/providers", b),
  updateProvider: (id: string, b: Partial<ProviderInput>) => req<Provider>("PATCH", `/api/providers/${id}`, b),
  deleteProvider: (id: string) => req("DELETE", `/api/providers/${id}`, {}),
  refreshProvider: (id: string) => req<Provider>("POST", `/api/providers/${id}/refresh`, {}),
  discoverProvider: (b: { id?: string; kind: ProviderKind; baseUrl?: string; apiKey?: string }) =>
    req<{ models: string[]; caps?: Record<string, string[]> }>("POST", "/api/providers/discover", b),
  rules: () => req<{ rules: ToolRule[] }>("GET", "/api/settings/rules"),
  setRule: (tool: string, rule: "allow" | "block" | "") => req<{ rules: ToolRule[] }>("PUT", "/api/settings/rules", { tool, rule }),

  bots: () => req<{ bots: Bot[]; presence: Record<string, Presence> }>("GET", "/api/bots"),
  createBot: (b: Partial<Bot>) => req<Bot>("POST", "/api/bots", b),
  updateBot: (id: string, b: Partial<Bot>) => req<Bot>("PATCH", bot(id), b),
  archiveBot: (id: string) => req("DELETE", bot(id)),
  messages: (id: string, before?: string) => req<{ messages: Message[] }>("GET", `${bot(id)}/messages${before ? `?before=${encodeURIComponent(before)}` : ""}`),
  send: (id: string, text: string) => req<Message>("POST", `${bot(id)}/messages`, { text }),
  stop: (id: string) => req("POST", `${bot(id)}/stop`, {}),
  queue: (id: string) => req<{ items: QueueItem[] }>("GET", `${bot(id)}/queue`),
  groupQueue: (id: string) => req<{ items: QueueItem[] }>("GET", `/api/groups/${id}/queue`),
  queueAction: (qid: string, action: "pause" | "resume" | "cancel") => req<{ items: QueueItem[] }>("POST", `/api/queue/${qid}`, { action }),
  clearQueue: (id: string) => req<{ items: QueueItem[] }>("POST", `${bot(id)}/queue/clear`, {}),
  // Delete this chat with a bot and start it fresh; memory also forgets what it learned.
  clearChat: (id: string, memory: boolean) => req("DELETE", `${bot(id)}/messages${memory ? "?memory=1" : ""}`),
  clearGroupQueue: (id: string) => req<{ items: QueueItem[] }>("POST", `/api/groups/${id}/queue/clear`, {}),
  answer: (cardId: string, b: { decision: "approve" | "reject" | "answer"; text?: string; always?: boolean }) =>
    req<Message>("POST", `/api/cards/${cardId}`, b),

  computer: (id: string) => req<ComputerStatus>("GET", `${bot(id)}/computer`),
  computerAction: (id: string, action: "start" | "stop" | "sleep" | "wake" | "control" | "input" | "show", body: unknown = {}) =>
    req<ComputerStatus>("POST", `${bot(id)}/computer/${action}`, body),
  screenUrl: (id: string) => `${bot(id)}/computer/screen?t=${Date.now()}`,
  streamUrl: (id: string) => `${bot(id)}/computer/stream?t=${Date.now()}`,

  sharing: (id: string) => req<Sharing>("GET", `${bot(id)}/sharing`),
  setVisibility: (id: string, visibility: Visibility) => req<Sharing>("PUT", `${bot(id)}/sharing`, { visibility }),
  addBotMember: (id: string, email: string, role: "manager" | "collaborator") => req<Sharing>("POST", `${bot(id)}/sharing/members`, { email, role }),
  removeBotMember: (id: string, userId: string) => req<Sharing>("DELETE", `${bot(id)}/sharing/members/${userId}`),

  spaces: () => req<{ spaces: Space[]; current: string }>("GET", "/api/spaces"),
  createSpace: (name: string) => req<Space>("POST", "/api/spaces", { name }),
  selectSpace: (spaceId: string) => req<{ spaceId: string }>("PUT", "/api/me/space", { spaceId }),
  spaceMembers: (id: string) => req<{ members: Member[] }>("GET", `/api/spaces/${id}/members`),
  addSpaceMember: (id: string, email: string) => req<{ members: Member[] }>("POST", `/api/spaces/${id}/members`, { email }),
  removeSpaceMember: (id: string, userId: string) => req<{ members: Member[] }>("DELETE", `/api/spaces/${id}/members/${userId}`),

  routines: (id: string) => req<{ routines: Routine[] }>("GET", `${bot(id)}/routines`),
  createRoutine: (id: string, b: { name: string; prompt: string; kind: string; schedule?: string }) => req<Routine>("POST", `${bot(id)}/routines`, b),
  updateRoutine: (id: string, rid: string, b: { active: boolean }) => req<Routine>("PATCH", `${bot(id)}/routines/${rid}`, b),
  deleteRoutine: (id: string, rid: string) => req("DELETE", `${bot(id)}/routines/${rid}`),
  runRoutine: (id: string, rid: string) => req("POST", `${bot(id)}/routines/${rid}/run`, {}),

  mcp: () => req<{ servers: MCPServer[] }>("GET", "/api/mcp"),
  startMcpOAuth: (b: { name: string; url: string }) => req<{ url: string }>("POST", "/api/mcp/oauth/start", b),
  addMcp: (b: { name: string; url: string; token?: string }) => req<MCPServer>("POST", "/api/mcp", b),
  toggleMcp: (id: string, enabled: boolean) => req<MCPServer>("PATCH", `/api/mcp/${id}`, { enabled }),
  deleteMcp: (id: string) => req("DELETE", `/api/mcp/${id}`),
  vault: () => req<{ secrets: VaultSecret[] }>("GET", "/api/vault"),
  addVault: (b: { name: string; value: string; origins: string[] }) => req<VaultSecret>("POST", "/api/vault", b),
  updateVault: (id: string, b: { name?: string; value?: string; origins?: string[] }) => req<VaultSecret>("PATCH", `/api/vault/${id}`, b),
  deleteVault: (id: string) => req("DELETE", `/api/vault/${id}`, {}),
  skills: () => req<{ skills: Skill[] }>("GET", "/api/skills"),
  saveSkill: (b: { id?: string; name: string; description: string; body: string }) => req<Skill>("POST", "/api/skills", b),
  deleteSkill: (id: string) => req("DELETE", `/api/skills/${id}`),

  groups: () => req<{ groups: Group[] }>("GET", "/api/groups"),
  createGroup: (b: { name: string; botIds: string[] }) => req<Group>("POST", "/api/groups", b),
  archiveGroup: (id: string) => req("DELETE", `/api/groups/${id}`),
  groupMessages: (id: string, before?: string) => req<{ messages: Message[] }>("GET", `/api/groups/${id}/messages${before ? `?before=${encodeURIComponent(before)}` : ""}`),
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

import { useEffect, useState } from "react";
import { api, type BotRole, type Member, type Sharing, type Space, type Visibility } from "../lib/api";
import { attempt, toastError } from "../lib/toast";
import { Button, Input, Label } from "./ui";

const visibilities: { id: Visibility; label: string; blurb: string }[] = [
  { id: "private", label: "Only me", blurb: "Only you can use this bot." },
  { id: "team", label: "Whole team", blurb: "Everyone on the team can chat with it. Each chat stays private." },
  { id: "invited", label: "People you invite", blurb: "Only the people you add below can chat with it." },
];

/** Who can use a bot, for its owner and managers. */
export function ShareSection({ botId, role }: { botId: string; role: BotRole }) {
  const [data, setData] = useState<Sharing | null>(null);
  const [as, setAs] = useState<"collaborator" | "manager">("collaborator");
  const [email, setEmail] = useState("");
  const isOwner = role === "owner";

  useEffect(() => {
    api.sharing(botId).then(setData).catch(toastError);
  }, [botId]);

  if (!data) return null;

  // Each change returns the new sharing state.
  const run = async (fn: () => Promise<Sharing>, ok: string) => {
    const next = await attempt(fn, ok);
    if (next) setData(next);
    return next;
  };

  const notMembers = data.spaceMembers.filter((m) => m.role !== "owner" && !data.members.some((x) => x.userId === m.userId));
  const canRemove = (m: Member) => isOwner || m.role !== "manager";

  return (
    <div className="space-y-4">
      <div>
        <Label>Who can use it</Label>
        <div className="grid grid-cols-3 gap-2">
          {visibilities.map((v) => (
            <button key={v.id} type="button" aria-pressed={data.visibility === v.id}
              onClick={() => run(() => api.setVisibility(botId, v.id), "Sharing updated")}
              className={`rounded-xl p-2.5 text-left text-[13px] cursor-pointer hairline ${data.visibility === v.id ? "bg-surface-2 font-semibold" : "bg-surface text-ink-2 hover:text-ink"}`}>
              {v.label}
            </button>
          ))}
        </div>
        <p className="text-[12.5px] text-ink-2 mt-1.5">{visibilities.find((v) => v.id === data.visibility)?.blurb}</p>
      </div>

      {data.visibility === "invited" && (
        <div className="space-y-2">
          <Label hint="Managers can change the bot, collaborators can only chat">People</Label>
          {data.members.length === 0 && <p className="text-[13px] text-ink-3">Nobody yet.</p>}
          {data.members.map((m) => (
            <div key={m.userId} className="flex items-center gap-2 text-[14px]">
              <span className="flex-1 truncate">{m.name} <span className="text-ink-3">{m.email}</span></span>
              <span className="text-[12px] text-ink-2 capitalize">{m.role}</span>
              {canRemove(m) && (
                <Button variant="ghost" aria-label={`Remove ${m.name}`}
                  onClick={() => run(() => api.removeBotMember(botId, m.userId), "Removed")}>Remove</Button>
              )}
            </div>
          ))}
          {notMembers.length > 0 && (
            <div className="pt-1 space-y-2">
              <div className="text-[12.5px] text-ink-2">Team members you can add</div>
              {notMembers.map((m) => (
                <div key={m.userId} className="flex items-center gap-2 text-[14px]">
                  <span className="flex-1 truncate">{m.name} <span className="text-ink-3">{m.email}</span></span>
                  <Button variant="soft" onClick={() => run(() => api.addBotMember(botId, m.email, as), `Added ${m.name}`)}>Add</Button>
                </div>
              ))}
            </div>
          )}
          <div className="flex items-center gap-2 pt-1">
            <Input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="teammate@example.com" aria-label="Teammate email" />
            <select value={as} onChange={(e) => setAs(e.target.value as "collaborator" | "manager")} aria-label="Role"
              className="h-9 rounded-xl bg-surface-2 px-2 text-[14px] outline-none cursor-pointer">
              <option value="collaborator">Collaborator</option>
              <option value="manager">Manager</option>
            </select>
            <Button variant="soft" disabled={!email.trim()}
              onClick={async () => { if (await run(() => api.addBotMember(botId, email.trim(), as), "Added")) setEmail(""); }}>Add</Button>
          </div>
        </div>
      )}
    </div>
  );
}

/** The team: switch between teams, make one, and manage its members. */
export function TeamSection() {
  const [spaces, setSpaces] = useState<Space[]>([]);
  const [current, setCurrent] = useState("");
  const [members, setMembers] = useState<Member[]>([]);
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");

  const load = () => api.spaces().then((r) => { setSpaces(r.spaces); setCurrent(r.current); }).catch(toastError);
  useEffect(() => { load(); }, []);
  const active = spaces.find((s) => s.id === current);
  useEffect(() => {
    if (current) api.spaceMembers(current).then((r) => setMembers(r.members)).catch(() => setMembers([]));
  }, [current]);

  // A new team comes with fresh data, so switching reloads the app.
  const switchTo = async (id: string) => {
    if (await attempt(() => api.selectSpace(id))) window.location.reload();
  };

  return (
    <div className="space-y-5">
      <div className="space-y-2">
        <Label hint="Bots shared with a team are visible to its members">Your teams</Label>
        {spaces.map((s) => (
          <div key={s.id} className="flex items-center gap-2 text-[14px]">
            <span className="flex-1 truncate">{s.name}</span>
            <span className="text-[12px] text-ink-3 capitalize">{s.role}</span>
            {s.id === current
              ? <span className="text-[12px] text-ink-2">Current</span>
              : <Button variant="soft" onClick={() => switchTo(s.id)}>Switch</Button>}
          </div>
        ))}
        <div className="flex items-center gap-2 pt-1">
          <Input value={name} maxLength={40} onChange={(e) => setName(e.target.value)} placeholder="New team name" aria-label="New team name" />
          <Button variant="soft" disabled={!name.trim()}
            onClick={async () => { const sp = await attempt(() => api.createSpace(name.trim())); if (sp) await switchTo(sp.id); }}>Create</Button>
        </div>
      </div>

      {active && (
        <div className="space-y-2 border-t border-line pt-4">
          <Label hint={active.role === "owner" ? "You can add anyone who has signed up" : undefined}>Members of {active.name}</Label>
          {members.map((m) => (
            <div key={m.userId} className="flex items-center gap-2 text-[14px]">
              <span className="flex-1 truncate">{m.name} <span className="text-ink-3">{m.email}</span></span>
              <span className="text-[12px] text-ink-2 capitalize">{m.role}</span>
              {active.role === "owner" && m.role !== "owner" && (
                <Button variant="ghost" onClick={() => attempt(() => api.removeSpaceMember(current, m.userId), "Removed").then((r) => r && setMembers(r.members))}>Remove</Button>
              )}
            </div>
          ))}
          {active.role === "owner" && (
            <div className="flex items-center gap-2 pt-1">
              <Input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="teammate@example.com" aria-label="Teammate email" />
              <Button variant="soft" disabled={!email.trim()}
                onClick={async () => { const r = await attempt(() => api.addSpaceMember(current, email.trim()), "Added"); if (r) { setMembers(r.members); setEmail(""); } }}>Add</Button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

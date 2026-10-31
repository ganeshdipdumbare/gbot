import { useEffect, useRef, useState, type RefObject } from "react";
import type { Mood } from "@gbot/characters";
import type { Bot, Presence } from "./api";

/** Gaze toward the pointer, relative to an element's center (-1..1). */
export function useGaze<T extends Element>(): [RefObject<T | null>, { x: number; y: number } | null] {
  const ref = useRef<T>(null);
  const [gaze, setGaze] = useState<{ x: number; y: number } | null>(null);
  useEffect(() => {
    let raf = 0;
    let idle: ReturnType<typeof setTimeout>;
    const onMove = (e: PointerEvent) => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => {
        const el = ref.current;
        if (!el) return;
        const r = el.getBoundingClientRect();
        const dx = (e.clientX - (r.left + r.width / 2)) / 260;
        const dy = (e.clientY - (r.top + r.height / 2)) / 260;
        setGaze({ x: Math.max(-1, Math.min(1, dx)), y: Math.max(-1, Math.min(1, dy)) });
      });
      clearTimeout(idle);
      idle = setTimeout(() => setGaze(null), 4000);
    };
    window.addEventListener("pointermove", onMove, { passive: true });
    return () => { window.removeEventListener("pointermove", onMove); cancelAnimationFrame(raf); clearTimeout(idle); };
  }, []);
  return [ref, gaze];
}

export function moodOf(p: Presence | undefined, bot?: Bot): Mood {
  const s = p?.state ?? "idle";
  if (s !== "idle") return s;
  const last = bot?.lastMessage?.createdAt ?? bot?.createdAt;
  if (last && Date.now() - new Date(last).getTime() > 12 * 3600_000) return "sleepy";
  return "idle";
}

export const presenceText: Record<Presence["state"], string> = {
  idle: "",
  thinking: "Thinking…",
  working: "Working…",
  waiting: "Needs you",
  blocked: "Stuck",
  done: "Done!",
};

export function timeAgo(iso?: string) {
  if (!iso) return "";
  const s = (Date.now() - new Date(iso).getTime()) / 1000;
  if (s < 60) return "now";
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  if (s < 86400) return `${Math.floor(s / 3600)}h`;
  if (s < 604800) return `${Math.floor(s / 86400)}d`;
  return new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

export function useMediaQuery(q: string) {
  const [m, setM] = useState(() => window.matchMedia(q).matches);
  useEffect(() => {
    const mq = window.matchMedia(q);
    const on = () => setM(mq.matches);
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, [q]);
  return m;
}

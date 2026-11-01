import { useEffect, useId, useRef, type CSSProperties } from "react";
import { hues } from "@gbot/ui-tokens";
import { resolveLook, type Look } from "./traits";
import { type Mood } from "./pose";
import { blend, BLEND, headTransform, poseAt, rig, type Pose } from "./motion";

export type { Mood };

export interface GbotProps {
  look?: Look;
  /** Seed used when look has none (e.g. the bot id). */
  seed?: string;
  mood?: Mood;
  size?: number;
  /** Where the eyes look, each axis -1..1. */
  lookAt?: { x: number; y: number } | null;
  /** The bot's computer is running. */
  live?: boolean;
  /** Kept for compatibility; the flat style draws no props. */
  props?: boolean;
  className?: string;
  style?: CSSProperties;
  title?: string;
}

// One animation frame loop for every avatar on the page.
const subscribers = new Set<(now: number) => void>();
let frame = 0;
function loop(now: number) {
  subscribers.forEach((fn) => fn(now));
  frame = subscribers.size ? requestAnimationFrame(loop) : 0;
}
function subscribe(fn: (now: number) => void) {
  subscribers.add(fn);
  if (!frame) frame = requestAnimationFrame(loop);
  return () => { subscribers.delete(fn); };
}

const reducedMotion = () => typeof matchMedia !== "undefined" && matchMedia("(prefers-reduced-motion: reduce)").matches;
const clamp = (v: number) => Math.max(-1, Math.min(1, v));

// The head is one solid shape; the eyes are holes masked out of it, so
// whatever the bot sits on shows through. Each frame the motion rig turns
// the mood and the time into a pose, written straight to the SVG.
export function Gbot({ look, seed = "gbot", mood = "idle", size = 48, lookAt, live, className, style, title }: GbotProps) {
  const t = resolveLook(look, seed);
  const r = rig(t.shape, t.eyes, look?.seed || seed);
  const color = hues[t.hue].body;
  const mask = useId().replace(/:/g, "");

  const svg = useRef<SVGSVGElement>(null);
  const head = useRef<SVGGElement>(null);
  const eyes = [useRef<SVGRectElement>(null), useRef<SVGRectElement>(null)];
  const dots = [useRef<SVGGElement>(null), useRef<SVGGElement>(null)];
  const bang = useRef<SVGRectElement>(null);

  const state = useRef({ mood, since: 0, from: null as Pose | null, last: null as Pose | null, gaze: lookAt, rig: r });
  state.current.gaze = lookAt ? { x: clamp(lookAt.x), y: clamp(lookAt.y) } : null;
  state.current.rig = r;

  const draw = (p: Pose) => {
    head.current?.setAttribute("transform", headTransform(p.head));
    head.current?.setAttribute("opacity", p.head.o.toFixed(3));
    p.eyes.forEach((e, i) => {
      const el = eyes[i].current;
      if (!el) return;
      el.setAttribute("x", (e.x - e.w / 2).toFixed(2));
      el.setAttribute("y", (e.y - e.h / 2).toFixed(2));
      el.setAttribute("width", Math.max(e.w, 0).toFixed(2));
      el.setAttribute("height", Math.max(e.h, 0).toFixed(2));
      el.setAttribute("rx", (Math.min(e.w, e.h) / 2).toFixed(2));
      el.setAttribute("transform", `rotate(${e.rot.toFixed(2)} ${e.x.toFixed(2)} ${e.y.toFixed(2)})`);
    });
    p.dots.forEach((d, i) => {
      const el = dots[i].current;
      el?.setAttribute("transform", `translate(${d.x} ${d.y}) scale(${d.s}) translate(-50 -50)`);
      el?.setAttribute("opacity", d.o.toFixed(3));
    });
    bang.current?.setAttribute("opacity", p.bang.o.toFixed(3));
    bang.current?.setAttribute("transform", `rotate(${p.bang.rot.toFixed(2)} 50 17.6)`);
  };

  // A new mood blends in from wherever the bot is now.
  useEffect(() => {
    const s = state.current;
    if (s.mood === mood && s.since) return;
    s.from = s.last;
    s.mood = mood;
    s.since = performance.now();
  }, [mood]);

  useEffect(() => {
    const s = state.current;
    if (!s.since) s.since = performance.now();
    const tick = (now: number) => {
      const el = (now - s.since) / 1000;
      let p = poseAt(s.rig, s.mood, el, s.gaze);
      if (s.from && el < BLEND) p = blend(s.from, p, el / BLEND);
      s.last = p;
      draw(p);
    };
    if (reducedMotion()) {
      tick(s.since + 1200);
      return;
    }
    // Only animate while on screen.
    let off: (() => void) | null = null;
    const io = new IntersectionObserver(([e]) => {
      if (e.isIntersecting && !off) off = subscribe(tick);
      else if (!e.isIntersecting && off) { off(); off = null; }
    });
    if (svg.current) io.observe(svg.current);
    tick(performance.now());
    return () => { io.disconnect(); off?.(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mood]);

  return (
    <svg
      ref={svg}
      viewBox="-7 -7 114 114"
      width={size}
      height={size}
      className={`gbot ${className ?? ""}`}
      data-mood={mood}
      style={style}
      role="img"
      aria-label={title ?? "bot"}
    >
      <defs>
        <mask id={mask} maskUnits="userSpaceOnUse" x="-200" y="-200" width="500" height="500">
          <rect x="-200" y="-200" width="500" height="500" fill="#fff" />
          <rect ref={eyes[0]} fill="#000" />
          <rect ref={eyes[1]} fill="#000" />
        </mask>
      </defs>
      <g ref={dots[0]} opacity="0"><path d={r.body.d} fill={color} /></g>
      <g ref={dots[1]} opacity="0"><path d={r.body.d} fill={color} /></g>
      <rect ref={bang} x="43.5" y="17.6" width="13" height="42" rx="6.5" fill={color} opacity="0" />
      <g ref={head}>
        <path d={r.body.d} fill={color} mask={`url(#${mask})`} />
      </g>
      {live && <circle className="gbot-live" cx="88" cy="88" r="9" fill="#8B5CF6" stroke="var(--bg, #fff)" strokeWidth="4" />}
    </svg>
  );
}

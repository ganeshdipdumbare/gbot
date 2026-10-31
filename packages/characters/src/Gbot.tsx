import { useEffect, useRef, useState, type CSSProperties } from "react";
import { hues } from "@gbot/ui-tokens";
import { bodies, eyeSizes } from "./parts";
import { resolveLook, type Look } from "./traits";

export type Mood = "idle" | "thinking" | "working" | "waiting" | "blocked" | "done" | "sleepy" | "happy";

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

interface EyePose { tx: number; ty: number; sx: number; sy: number; rot: number }

// How each mood shapes the eyes. Everything a bot feels is carried by
// two eyes and the way its body moves; nothing else is drawn.
function pose(mood: Mood, side: -1 | 1, gx: number, gy: number): EyePose {
  switch (mood) {
    case "thinking":
      return { tx: 3, ty: -4.5, sx: 1, sy: 0.82, rot: 0 };
    case "working":
      return { tx: gx * 0.4, ty: 1.2, sx: 1.05, sy: 0.5, rot: 0 };
    case "waiting":
      return { tx: 0, ty: -0.6, sx: 1.18, sy: 1.18, rot: 0 };
    case "blocked":
      return { tx: 0, ty: 1.5, sx: 1.25, sy: 0.42, rot: side * 24 };
    case "sleepy":
      return { tx: 0, ty: 3, sx: 1.1, sy: 0.16, rot: 0 };
    default:
      return { tx: gx, ty: gy, sx: 1, sy: 1, rot: 0 };
  }
}

function useBlink(enabled: boolean) {
  const [blink, setBlink] = useState(false);
  useEffect(() => {
    if (!enabled) return;
    let t: ReturnType<typeof setTimeout>;
    const loop = () => {
      t = setTimeout(() => {
        setBlink(true);
        setTimeout(() => setBlink(false), 120);
        loop();
      }, 2600 + Math.random() * 4400);
    };
    loop();
    return () => clearTimeout(t);
  }, [enabled]);
  return blink;
}

const clamp = (v: number) => Math.max(-1, Math.min(1, v));

export function Gbot({ look, seed = "gbot", mood = "idle", size = 48, lookAt, live, className, style, title }: GbotProps) {
  const t = resolveLook(look, seed);
  const color = hues[t.hue];
  const body = bodies[t.shape];
  const happy = mood === "done" || mood === "happy";
  const blink = useBlink(!happy && mood !== "sleepy");

  // At rest the eyes wander now and then: calm, slightly curious.
  const [drift, setDrift] = useState({ x: 0, y: 0 });
  const steered = useRef(false);
  steered.current = !!lookAt;
  useEffect(() => {
    if (mood !== "idle") return;
    const id = setInterval(() => {
      if (steered.current) return;
      const r = Math.random();
      setDrift(r < 0.45 ? { x: 0, y: 0 } : { x: (Math.random() - 0.5) * 1.6, y: (Math.random() - 0.5) * 0.8 });
    }, 2800 + Math.random() * 2400);
    return () => clearInterval(id);
  }, [mood]);

  const gaze = lookAt ? { x: clamp(lookAt.x), y: clamp(lookAt.y) } : drift;
  const gx = gaze.x * 3.4;
  const gy = gaze.y * 2.4;

  const [fx, fy] = body.face;
  const s = body.eyeScale;
  const [ew, eh] = eyeSizes[t.eyes];
  const w = ew * s;
  const h = eh * s;
  const gap = 13.5 * s;
  const edge = t.hue === "ink" ? "rgba(255,255,255,.14)" : t.hue === "cream" ? "rgba(30,26,22,.08)" : "none";

  return (
    <svg
      viewBox="0 0 100 100"
      width={size}
      height={size}
      className={`gbot ${className ?? ""}`}
      data-mood={mood}
      data-small={size < 48 ? "" : undefined}
      style={style}
      role="img"
      aria-label={title ?? "bot"}
    >
      <g className="gbot-body" style={{ "--tilt": `${gaze.x * 3}deg` } as CSSProperties}>
        <path d={body.d} fill={color.body} stroke={edge} strokeWidth="1.5" />
        <g className={`gbot-eyes${blink ? " is-blink" : ""}`}>
          {([-1, 1] as const).map((side) => {
            const cx = fx + side * gap;
            const p = pose(mood, side, gx, gy);
            return (
              <g key={side}>
                <rect
                  className="gbot-eye"
                  x={cx - w / 2}
                  y={fy - h / 2}
                  width={w}
                  height={h}
                  rx={Math.min(w, h) / 2}
                  fill={color.eye}
                  style={{
                    transform: `translate(${p.tx}px, ${p.ty}px) rotate(${p.rot}deg) scale(${p.sx}, ${p.sy})`,
                    opacity: happy ? 0 : 1,
                  }}
                />
                <path
                  className="gbot-eye"
                  d={`M${cx - 6.5 * s} ${fy + 3}Q${cx} ${fy - 6.5 * s} ${cx + 6.5 * s} ${fy + 3}`}
                  fill="none"
                  stroke={color.eye}
                  strokeWidth={4.6 * s}
                  strokeLinecap="round"
                  style={{ opacity: happy ? 1 : 0 }}
                />
              </g>
            );
          })}
        </g>
      </g>
      {live && <circle className="gbot-live" cx="84" cy="84" r="7.5" fill="#8B5CF6" stroke="var(--bg, #fff)" strokeWidth="3" />}
    </svg>
  );
}

import { useEffect, useId, useRef, useState, type CSSProperties } from "react";
import { hues } from "@gbot/ui-tokens";
import { bodies, eyeLayout, eyeSize } from "./parts";
import { resolveLook, type Look } from "./traits";
import { pose, type Mood } from "./pose";

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

// The eyes are real holes, masked out of the head, so whatever the bot
// sits on shows through them.
export function Gbot({ look, seed = "gbot", mood = "idle", size = 48, lookAt, live, className, style, title }: GbotProps) {
  const t = resolveLook(look, seed);
  const body = bodies[t.shape];
  const layout = eyeLayout[t.eyes];
  const mask = useId().replace(/:/g, "");
  const blink = useBlink(mood !== "sleepy" && mood !== "done" && mood !== "happy");

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
  const p = pose(mood, gaze.x * 6, gaze.y * 4);
  const [fx, fy] = body.face[t.eyes];
  const w = eyeSize[0] * body.k;
  const h = eyeSize[1] * body.k;

  return (
    <svg
      viewBox="-7 -7 114 114"
      width={size}
      height={size}
      className={`gbot ${className ?? ""}`}
      data-mood={mood}
      data-small={size < 48 ? "" : undefined}
      style={style}
      role="img"
      aria-label={title ?? "bot"}
    >
      <defs>
        <mask id={mask} maskUnits="userSpaceOnUse" x="-7" y="-7" width="114" height="114">
          <rect x="-7" y="-7" width="114" height="114" fill="#fff" />
          <g className={`gbot-eyes${blink ? " is-blink" : ""}`} style={{ transform: `translate(${p.tx}px, ${p.ty}px)` }}>
            {([0, 1] as const).map((i) => {
              const side = i === 0 ? -1 : 1;
              const cx = fx + side * layout.gap * body.k;
              const cy = fy + side * layout.lift * body.k;
              return (
                <g key={i} transform={`rotate(${layout.rot[i]} ${cx} ${cy})`}>
                  <rect
                    className="gbot-eye"
                    x={cx - w / 2}
                    y={cy - h / 2}
                    width={w}
                    height={h}
                    rx={w / 2}
                    fill="#000"
                    style={{ "--sx": p.sx, "--sy": p.sy } as CSSProperties}
                  />
                </g>
              );
            })}
          </g>
          {live && <circle cx="88" cy="88" r="13.5" fill="#000" />}
        </mask>
      </defs>
      <g className="gbot-body" style={{ "--tilt": `${gaze.x * 3}deg` } as CSSProperties}>
        <path d={body.d} fill={hues[t.hue].body} mask={`url(#${mask})`} />
      </g>
      {live && <circle className="gbot-live" cx="88" cy="88" r="9" fill="#8B5CF6" />}
    </svg>
  );
}

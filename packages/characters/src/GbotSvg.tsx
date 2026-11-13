import { memo, useEffect, useId, useRef, useState, type CSSProperties } from "react";
import { hues } from "@gbot/ui-tokens";
import { bodies, eyeSizes, mix } from "./parts";
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
  /** More blink, wander and sway, for the creation preview. */
  lively?: boolean;
  /** Kept for compatibility; the bots draw no props. */
  props?: boolean;
  className?: string;
  style?: CSSProperties;
  title?: string;
}

function useBlink(enabled: boolean, lively = false) {
  const [blink, setBlink] = useState(false);
  useEffect(() => {
    if (!enabled) return;
    let t: ReturnType<typeof setTimeout>;
    let t2: ReturnType<typeof setTimeout>;
    const loop = () => {
      t = setTimeout(() => {
        setBlink(true);
        t2 = setTimeout(() => setBlink(false), 110);
        // A preview bot sometimes blinks twice, which reads as curious.
        if (lively && Math.random() < 0.35) {
          setTimeout(() => {
            setBlink(true);
            setTimeout(() => setBlink(false), 95);
          }, 190);
        }
        loop();
      }, lively ? 1500 + Math.random() * 2400 : 2600 + Math.random() * 4400);
    };
    loop();
    return () => { clearTimeout(t); clearTimeout(t2); };
  }, [enabled, lively]);
  return blink;
}

const clamp = (v: number) => Math.max(-1, Math.min(1, v));

// Geometric bots with soft, quiet volume: a gentle light from above, the
// form easing into shade below, soft inner edges and a diffuse highlight,
// resting on a faint contact shadow. The eyes stay flat, like Grok Bot's:
// simple upright pills in a light colour. When a bot looks somewhere its
// face slides that way while the light stays put.
export const GbotSvg = memo(function GbotSvg({ look, seed = "gbot", mood = "idle", size = 48, lookAt, live, lively, className, style, title }: GbotProps) {
  const t = resolveLook(look, seed);
  const color = hues[t.hue];
  const body = bodies[t.shape];
  const uid = useId().replace(/:/g, "");
  const happy = mood === "done" || mood === "happy";
  const blink = useBlink(!happy && mood !== "sleepy", lively);

  // At rest the eyes wander now and then: calm, slightly curious.
  const [drift, setDrift] = useState({ x: 0, y: 0 });
  const steered = useRef(false);
  steered.current = !!lookAt;
  useEffect(() => {
    if (mood !== "idle") return;
    const id = setInterval(() => {
      if (steered.current) return;
      const r = Math.random();
      const spread = lively ? 2.5 : 1.6;
      setDrift(r < (lively ? 0.35 : 0.45) ? { x: 0, y: 0 } : { x: (Math.random() - 0.5) * spread, y: (Math.random() - 0.5) * (lively ? 1.3 : 0.8) });
    }, lively ? 1400 + Math.random() * 1700 : 2800 + Math.random() * 2400);
    return () => clearInterval(id);
  }, [mood, lively]);

  const gaze = lookAt ? { x: clamp(lookAt.x), y: clamp(lookAt.y) } : drift;
  const gx = gaze.x * 2.4;
  const gy = gaze.y * 1.6;
  // The face turns with the gaze: depth by parallax.
  const px = gaze.x * 3;
  const py = gaze.y * 2;

  const [fx, fy] = body.face;
  const s = body.eyeScale;
  const [ew, eh] = eyeSizes[t.eyes];
  const w = ew * s;
  const h = eh * s;
  const gap = 12 * s;
  const id = (k: string) => `${k}${uid}`;
  const url = (k: string) => `url(#${id(k)})`;
  const lit = mix(color.body, "#ffffff", 0.2);
  const shade = mix(color.body, "#000000", 0.1);
  // Roster-size avatars skip the Gaussian edge filter and gloss: at 42px
  // it is invisible, and dozens of filtered SVGs are what makes the list
  // stutter.
  const simple = size < 56;

  return (
    <svg
      viewBox="-3 -3 106 106"
      width={size}
      height={size}
      className={`gbot ${className ?? ""}`}
      data-mood={mood}
      data-small={size < 48 ? "" : undefined}
      data-lively={lively ? "" : undefined}
      style={style}
      role="img"
      aria-label={title ?? "bot"}
    >
      <defs>
        {/* Light from above, easing into shade below. */}
        <radialGradient id={id("body")} cx="0.5" cy="0.12" r="0.95">
          <stop offset="0" stopColor={lit} />
          <stop offset="0.55" stopColor={color.body} />
          <stop offset="1" stopColor={shade} />
        </radialGradient>
        <radialGradient id={id("sheen")} cx="0.5" cy="0.5" r="0.5">
          <stop offset="0" stopColor="#fff" stopOpacity={0.28} />
          <stop offset="1" stopColor="#fff" stopOpacity="0" />
        </radialGradient>
        {/* Soft inner edges: a lit rim on top, a shaded one below. */}
        <filter id={id("form")} x="-10%" y="-10%" width="120%" height="120%">
          <feGaussianBlur in="SourceAlpha" stdDeviation="3.2" result="blur" />
          <feOffset in="blur" dy="-3.5" result="up" />
          <feComposite in="SourceAlpha" in2="up" operator="out" result="bottomEdge" />
          <feFlood floodColor="#000" floodOpacity={0.16} />
          <feComposite in2="bottomEdge" operator="in" result="shade" />
          <feOffset in="blur" dy="2.5" result="down" />
          <feComposite in="SourceAlpha" in2="down" operator="out" result="topEdge" />
          <feFlood floodColor="#fff" floodOpacity={0.3} />
          <feComposite in2="topEdge" operator="in" result="light" />
          <feMerge>
            <feMergeNode in="SourceGraphic" />
            <feMergeNode in="shade" />
            <feMergeNode in="light" />
          </feMerge>
        </filter>
        <radialGradient id={id("ground")}>
          <stop offset="0" stopColor="#000" stopOpacity="0.2" />
          <stop offset="0.6" stopColor="#000" stopOpacity="0.07" />
          <stop offset="1" stopColor="#000" stopOpacity="0" />
        </radialGradient>
        <clipPath id={id("clip")}><path d={body.d} /></clipPath>
      </defs>

      <ellipse className="gbot-shadow" cx="50" cy="95.5" rx="27" ry="3.4" fill={url("ground")} />

      <g className="gbot-body" style={{ "--tilt": `${gaze.x * 3}deg` } as CSSProperties}>
        <path d={body.d} fill={url("body")} filter={simple ? undefined : url("form")} />
        {!simple && (
          <g clipPath={url("clip")}>
            <ellipse cx={42 - px * 0.3} cy={24 - py * 0.3} rx="28" ry="16" fill={url("sheen")} />
          </g>
        )}
        <g className="gbot-face" style={{ transform: `translate(${px}px, ${py}px)` }}>
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
                    d={`M${cx - 7 * s} ${fy + 3}Q${cx} ${fy - 7 * s} ${cx + 7 * s} ${fy + 3}`}
                    fill="none"
                    stroke={color.eye}
                    strokeWidth={6 * s}
                    strokeLinecap="round"
                    style={{ opacity: happy ? 1 : 0 }}
                  />
                </g>
              );
            })}
          </g>
        </g>
      </g>
      {live && <circle className="gbot-live" cx="84" cy="84" r="7.5" fill="#8B5CF6" stroke="var(--bg, #fff)" strokeWidth="3" />}
    </svg>
  );
});

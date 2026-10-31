import { useEffect, useId, useRef, useState, type CSSProperties } from "react";
import { hues } from "@gbot/ui-tokens";
import { bodies, face } from "./parts";
import { resolveLook, type Look, type Traits } from "./traits";

export type Mood = "idle" | "thinking" | "working" | "waiting" | "blocked" | "done" | "sleepy" | "happy";

export interface GbotProps {
  look?: Look;
  /** Seed used when look has none (e.g. the bot id). */
  seed?: string;
  mood?: Mood;
  size?: number;
  /** Where the eyes look, each axis -1..1. */
  lookAt?: { x: number; y: number } | null;
  /** The bot's computer is running: it wears tiny headphones. */
  live?: boolean;
  /** Small props such as the thought bubble. Off by default below 40px. */
  props?: boolean;
  className?: string;
  style?: CSSProperties;
  title?: string;
}

const clamp = (v: number) => Math.max(-1, Math.min(1, v));

function useBlink(enabled: boolean) {
  const [blink, setBlink] = useState(false);
  useEffect(() => {
    if (!enabled) return;
    let t: ReturnType<typeof setTimeout>;
    const loop = () => {
      t = setTimeout(() => {
        setBlink(true);
        setTimeout(() => setBlink(false), 130);
        // Sometimes a cute double blink.
        if (Math.random() < 0.2) setTimeout(() => { setBlink(true); setTimeout(() => setBlink(false), 110); }, 260);
        loop();
      }, 2200 + Math.random() * 4200);
    };
    loop();
    return () => clearTimeout(t);
  }, [enabled]);
  return blink;
}

export function Gbot({ look, seed = "gbot", mood = "idle", size = 48, lookAt, live, props, className, style, title }: GbotProps) {
  const t: Traits = resolveLook(look, seed);
  const uid = useId().replace(/:/g, "");
  const color = hues[t.hue];
  const body = bodies[t.shape];
  const showProps = props ?? size >= 40;
  const eyesClosed = mood === "done" || mood === "happy" || mood === "sleepy";
  const blink = useBlink(!eyesClosed);

  // Eyes drift on their own when nobody is steering them.
  const [drift, setDrift] = useState({ x: 0, y: 0 });
  const steered = useRef(false);
  steered.current = !!lookAt;
  useEffect(() => {
    const id = setInterval(() => {
      if (!steered.current) setDrift({ x: (Math.random() - 0.5) * 0.9, y: (Math.random() - 0.5) * 0.5 });
    }, 3200 + Math.random() * 2000);
    return () => clearInterval(id);
  }, []);

  let gaze = lookAt ? { x: clamp(lookAt.x), y: clamp(lookAt.y) } : drift;
  if (mood === "thinking") gaze = { x: -0.6, y: -0.9 };
  if (mood === "blocked") gaze = { x: 0, y: 0.7 };
  if (mood === "waiting") gaze = lookAt ? gaze : { x: 0, y: 0.1 };
  const gx = gaze.x * 2.6;
  const gy = gaze.y * 1.8;

  const ink = color.ink;
  const ex = 50 - face.eyeDX;
  const ex2 = 50 + face.eyeDX;
  const ey = face.y;
  const eye = t.eyes === "tall" ? { rx: 5.4, ry: 7.6 } : t.eyes === "sparkle" ? { rx: 6.6, ry: 7.4 } : { rx: 6.2, ry: 6.6 };

  return (
    <svg
      viewBox="-4 -8 108 108"
      width={size}
      height={size}
      className={`gbot ${className ?? ""}`}
      data-mood={mood}
      data-shape={t.shape}
      style={style}
      role="img"
      aria-label={title ?? "bot"}
    >
      <defs>
        <linearGradient id={`b${uid}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={color.body} />
          <stop offset="0.62" stopColor={color.body} />
          <stop offset="1" stopColor={color.shade} />
        </linearGradient>
        <radialGradient id={`c${uid}`}>
          <stop offset="0" stopColor="#FF8FA8" stopOpacity="0.75" />
          <stop offset="1" stopColor="#FF8FA8" stopOpacity="0" />
        </radialGradient>
      </defs>

      <ellipse className="gbot-shadow" cx="50" cy="95" rx="27" ry="3.4" fill={ink} opacity="0.13" />

      <g className="gbot-body">
        <Accessory kind={t.accessory} top={body.top} color={color} back />
        <path d={body.d} fill={`url(#b${uid})`} stroke={color.shade} strokeWidth="1.2" />
        {/* gloss */}
        <ellipse cx="34" cy={body.top + 15} rx="9" ry="5" fill="#fff" opacity="0.5" transform={`rotate(-24 34 ${body.top + 15})`} />
        <circle cx="45" cy={body.top + 9} r="2" fill="#fff" opacity="0.55" />
        <Accessory kind={t.accessory} top={body.top} color={color} />

        <g className="gbot-face">
          {/* cheeks */}
          <ellipse className="gbot-cheek" cx={ex - 7} cy={face.cheekY} rx="6.5" ry="4.2" fill={`url(#c${uid})`} />
          <ellipse className="gbot-cheek" cx={ex2 + 7} cy={face.cheekY} rx="6.5" ry="4.2" fill={`url(#c${uid})`} />

          {/* brows only when they say something */}
          {mood === "waiting" && (
            <g stroke={ink} strokeWidth="2" strokeLinecap="round" fill="none" opacity="0.8">
              <path d={`M${ex - 5} ${ey - 11} Q${ex} ${ey - 14} ${ex + 5} ${ey - 11.5}`} />
              <path d={`M${ex2 - 5} ${ey - 11.5} Q${ex2} ${ey - 14} ${ex2 + 5} ${ey - 11}`} />
            </g>
          )}
          {mood === "blocked" && (
            <g stroke={ink} strokeWidth="2" strokeLinecap="round" fill="none" opacity="0.8">
              <path d={`M${ex - 5} ${ey - 9} L${ex + 4.5} ${ey - 12}`} />
              <path d={`M${ex2 - 4.5} ${ey - 12} L${ex2 + 5} ${ey - 9}`} />
            </g>
          )}

          {eyesClosed ? (
            <g stroke={ink} strokeWidth="2.8" strokeLinecap="round" fill="none">
              {mood === "sleepy" ? (
                <>
                  <path d={`M${ex - 5} ${ey + 1} Q${ex} ${ey + 4.5} ${ex + 5} ${ey + 1}`} />
                  <path d={`M${ex2 - 5} ${ey + 1} Q${ex2} ${ey + 4.5} ${ex2 + 5} ${ey + 1}`} />
                </>
              ) : (
                <>
                  <path d={`M${ex - 5.5} ${ey + 2} Q${ex} ${ey - 5.5} ${ex + 5.5} ${ey + 2}`} />
                  <path d={`M${ex2 - 5.5} ${ey + 2} Q${ex2} ${ey - 5.5} ${ex2 + 5.5} ${ey + 2}`} />
                </>
              )}
            </g>
          ) : (
            <g className={`gbot-eyes${blink ? " is-blink" : ""}`} style={{ transformOrigin: `50px ${ey}px` }}>
              {[ex, ex2].map((cx) => (
                <g key={cx} className="gbot-eye" style={{ transform: `translate(${gx}px, ${gy}px)` }}>
                  <ellipse cx={cx} cy={ey} rx={eye.rx} ry={eye.ry} fill={ink} />
                  <circle cx={cx + 2} cy={ey - 2.6} r={t.eyes === "sparkle" ? 2.5 : 2.2} fill="#fff" />
                  <circle cx={cx - 2.2} cy={ey + 2.6} r="1.1" fill="#fff" opacity="0.9" />
                  {t.eyes === "sparkle" && <path d={`M${cx - 2.4} ${ey - 1.2}l.6 1.3 1.3.6-1.3.6-.6 1.3-.6-1.3-1.3-.6 1.3-.6z`} fill="#fff" />}
                </g>
              ))}
            </g>
          )}

          <MouthShape mood={mood} kind={t.mouth} ink={ink} />
        </g>

        {live && (
          <g className="gbot-headphones">
            <path d={`M20 ${ey - 2} C20 ${body.top - 6} 80 ${body.top - 6} 80 ${ey - 2}`} fill="none" stroke="#8B5CF6" strokeWidth="3.4" strokeLinecap="round" />
            <rect x="12.5" y={ey - 6} width="9" height="14" rx="4.5" fill="#8B5CF6" />
            <rect x="78.5" y={ey - 6} width="9" height="14" rx="4.5" fill="#8B5CF6" />
            <circle cx="17" cy={ey - 2} r="1.4" fill="#fff" opacity="0.7" />
          </g>
        )}
      </g>

      {showProps && <MoodProps mood={mood} ink={ink} top={body.top} />}
    </svg>
  );
}

function MouthShape({ mood, kind, ink }: { mood: Mood; kind: Traits["mouth"]; ink: string }) {
  const y = face.mouthY;
  const s = { stroke: ink, strokeWidth: 2.2, strokeLinecap: "round" as const, fill: "none" };
  if (mood === "thinking") return <ellipse cx="51" cy={y + 1} rx="1.9" ry="2.2" fill={ink} />;
  if (mood === "blocked") return <path d={`M45.5 ${y + 3} Q50 ${y - 0.5} 54.5 ${y + 3}`} {...s} />;
  if (mood === "waiting") return <ellipse cx="50" cy={y + 1.2} rx="2.4" ry="2.8" fill={ink} />;
  if (mood === "sleepy") return <ellipse cx="50" cy={y + 1.5} rx="1.6" ry="1.2" fill={ink} opacity="0.7" />;
  if (mood === "done" || mood === "happy" || kind === "grin")
    return (
      <g>
        <path d={`M44.5 ${y - 0.5} Q50 ${y + 8} 55.5 ${y - 0.5} Z`} fill={ink} stroke={ink} strokeWidth="1.2" strokeLinejoin="round" />
        <path d={`M47.2 ${y + 3.4} Q50 ${y + 1.6} 52.8 ${y + 3.4} Q50 ${y + 5.8} 47.2 ${y + 3.4}Z`} fill="#FF8FA8" />
      </g>
    );
  if (kind === "cat") return <path d={`M44 ${y} Q47 ${y + 3.6} 50 ${y} Q53 ${y + 3.6} 56 ${y}`} {...s} />;
  return <path d={`M45 ${y} Q50 ${y + 4.6} 55 ${y}`} {...s} />;
}

function Accessory({ kind, top, color, back }: { kind: Traits["accessory"]; top: number; color: (typeof hues)[keyof typeof hues]; back?: boolean }) {
  switch (kind) {
    case "cat":
      return back ? (
        <g className="gbot-ears" fill={color.body} stroke={color.shade} strokeWidth="1.2" strokeLinejoin="round">
          <path d={`M22 ${top + 16} L23 ${top - 6} L40 ${top + 4} Z`} />
          <path d={`M78 ${top + 16} L77 ${top - 6} L60 ${top + 4} Z`} />
          <path d={`M26 ${top + 9} L27 ${top - 1} L35 ${top + 4} Z`} fill="#FFB3C6" stroke="none" />
          <path d={`M74 ${top + 9} L73 ${top - 1} L65 ${top + 4} Z`} fill="#FFB3C6" stroke="none" />
        </g>
      ) : null;
    case "bear":
      return back ? (
        <g className="gbot-ears" fill={color.body} stroke={color.shade} strokeWidth="1.2">
          <circle cx="27" cy={top + 5} r="9" />
          <circle cx="73" cy={top + 5} r="9" />
          <circle cx="27" cy={top + 5} r="4.5" fill={color.shade} stroke="none" opacity="0.7" />
          <circle cx="73" cy={top + 5} r="4.5" fill={color.shade} stroke="none" opacity="0.7" />
        </g>
      ) : null;
    case "sprout":
      return back ? null : (
        <g className="gbot-sprout">
          <path d={`M50 ${top + 1} Q49 ${top - 6} 51 ${top - 10}`} stroke="#5FB37C" strokeWidth="2.2" fill="none" strokeLinecap="round" />
          <path d={`M51 ${top - 9} C44 ${top - 18} 36 ${top - 12} 39 ${top - 8} C42 ${top - 5} 48 ${top - 6} 51 ${top - 9}Z`} fill="#7DD69B" />
          <path d={`M51 ${top - 9} C57 ${top - 19} 66 ${top - 14} 63 ${top - 9} C60 ${top - 5} 54 ${top - 6} 51 ${top - 9}Z`} fill="#94E2AE" />
        </g>
      );
    case "antenna":
      return back ? (
        <g className="gbot-antenna">
          <path d={`M50 ${top + 2} L50 ${top - 9}`} stroke={color.shade} strokeWidth="2.4" strokeLinecap="round" />
          <circle className="gbot-antenna-ball" cx="50" cy={top - 11} r="4" fill="#FFD66B" stroke="#F2B937" strokeWidth="1" />
        </g>
      ) : null;
    case "bow":
      return back ? null : (
        <g className="gbot-bow" transform={`translate(66 ${top + 6}) rotate(18)`}>
          <path d="M0 0 C-9 -8 -12 4 -1 1 Z M0 0 C9 -8 12 4 1 1 Z" fill="#FF7EA6" stroke="#E85D89" strokeWidth="1" strokeLinejoin="round" />
          <circle r="2.4" fill="#E85D89" />
        </g>
      );
    default:
      return null;
  }
}

function MoodProps({ mood, ink, top }: { mood: Mood; ink: string; top: number }) {
  switch (mood) {
    case "thinking":
      return (
        <g className="gbot-think" fill={ink} opacity="0.55">
          <circle cx="78" cy={top - 2} r="3" />
          <circle cx="86" cy={top - 9} r="3.6" />
          <circle cx="95" cy={top - 4} r="3" />
        </g>
      );
    case "working":
      return (
        <g className="gbot-spark" fill="#FFC94D">
          <path d="M88 30l1.8 4 4 1.8-4 1.8-1.8 4-1.8-4-4-1.8 4-1.8z" />
          <path d="M10 40l1.2 2.6 2.6 1.2-2.6 1.2-1.2 2.6-1.2-2.6-2.6-1.2 2.6-1.2z" />
        </g>
      );
    case "waiting":
      return (
        <g className="gbot-ask">
          <circle cx="86" cy={top - 2} r="9" fill="#fff" stroke={ink} strokeOpacity="0.15" />
          <text x="86" y={top + 2} textAnchor="middle" fontSize="12" fontWeight="800" fill="#8B5CF6" fontFamily="Nunito, system-ui">?</text>
        </g>
      );
    case "blocked":
      return <path className="gbot-sweat" d="M83 34C83 34 78 41 78 44A5 5 0 0 0 88 44C88 41 83 34 83 34Z" fill="#8FD3FF" stroke="#5FB4EA" strokeWidth="1" />;
    case "done":
    case "happy":
      return (
        <g className="gbot-confetti">
          <circle cx="16" cy="22" r="2.4" fill="#FF8FA8" />
          <rect x="82" y="16" width="4" height="4" rx="1" fill="#8B5CF6" transform="rotate(20 84 18)" />
          <circle cx="90" cy="38" r="2" fill="#FFC94D" />
          <rect x="8" y="44" width="3.6" height="3.6" rx="1" fill="#7DD69B" transform="rotate(-15 10 46)" />
          <path d="M50 -2l1.4 3 3 1.4-3 1.4-1.4 3-1.4-3-3-1.4 3-1.4z" fill="#FFC94D" />
        </g>
      );
    case "sleepy":
      return (
        <g className="gbot-zzz" fill={ink} opacity="0.5" fontFamily="Nunito, system-ui" fontWeight="800">
          <text x="76" y={top + 2} fontSize="9">z</text>
          <text x="84" y={top - 6} fontSize="12">z</text>
        </g>
      );
    default:
      return null;
  }
}

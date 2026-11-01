import { useEffect, useRef, useState } from "react";
import Svg, { ClipPath, Defs, G, Path, Rect, Circle } from "react-native-svg";
import { hues } from "@gbot/ui-tokens";
import { blend, BLEND, headTransform, poseAt, resolveLook, rig, type Look, type Mood, type Pose } from "@gbot/characters/core";
import { useTheme } from "../lib/theme";

export type { Look, Mood };

// The same gbot as on the web, drawn with react-native-svg and driven by
// the shared motion rig: a solid head whose eyes are cut out in the colour
// it sits on (cut). Frames run at ~30fps, which is plenty at avatar sizes.

let clips = 0;

export function Gbot({ look, seed = "gbot", mood = "idle", size = 48, live, cut }: {
  look?: Look; seed?: string; mood?: Mood; size?: number; live?: boolean; cut?: string;
}) {
  const t = resolveLook(look, seed);
  const r = rig(t.shape, t.eyes, look?.seed || seed);
  const color = hues[t.hue].body;
  const theme = useTheme();
  const hole = cut ?? theme.bg;
  const clip = useRef(`gbot${++clips}`).current;

  const s = useRef({ mood, since: Date.now(), from: null as Pose | null, last: null as Pose | null });
  const at = () => {
    const el = (Date.now() - s.current.since) / 1000;
    let p = poseAt(r, s.current.mood, el);
    if (s.current.from && el < BLEND) p = blend(s.current.from, p, el / BLEND);
    s.current.last = p;
    return p;
  };
  const [p, setP] = useState<Pose>(at);

  useEffect(() => {
    if (s.current.mood !== mood) {
      s.current.from = s.current.last;
      s.current.mood = mood;
      s.current.since = Date.now();
    }
    let raf = 0, last = 0;
    const tick = (now: number) => {
      if (now - last > 33) { last = now; setP(at()); }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mood, seed, look?.seed, t.shape, t.eyes]);

  return (
    <Svg width={size} height={size} viewBox="-7 -7 114 114">
      <Defs>
        <ClipPath id={clip}><Path d={r.body.d} /></ClipPath>
      </Defs>
      {p.dots.map((d, i) => (
        <G key={i} opacity={d.o} transform={`translate(${d.x} ${d.y}) scale(${d.s}) translate(-50 -50)`}>
          <Path d={r.body.d} fill={color} />
        </G>
      ))}
      <Rect x={43.5} y={17.6} width={13} height={42} rx={6.5} fill={color} opacity={p.bang.o} transform={`rotate(${p.bang.rot} 50 17.6)`} />
      <G transform={headTransform(p.head)} opacity={p.head.o}>
        <Path d={r.body.d} fill={color} />
        <G clipPath={`url(#${clip})`}>
          {p.eyes.map((e, i) => e.h > 0.2 && (
            <Rect key={i} x={e.x - e.w / 2} y={e.y - e.h / 2} width={e.w} height={e.h} rx={Math.min(e.w, e.h) / 2}
              fill={hole} transform={`rotate(${e.rot} ${e.x} ${e.y})`} />
          ))}
        </G>
      </G>
      {live && <Circle cx={88} cy={88} r={9} fill="#8B5CF6" stroke={hole} strokeWidth={4} />}
    </Svg>
  );
}

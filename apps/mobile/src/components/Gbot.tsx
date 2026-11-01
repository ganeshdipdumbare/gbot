import { useEffect, useRef, useState } from "react";
import { Animated, Easing } from "react-native";
import Svg, { ClipPath, Defs, G, Path, Rect, Circle } from "react-native-svg";
import { hues } from "@gbot/ui-tokens";
import { useTheme } from "../lib/theme";
import { bodies, eyeLayout, eyeSize, pose, resolveLook, type Look, type Mood } from "@gbot/characters/core";

export type { Look, Mood };

// The same gbot as on the web, drawn with react-native-svg: a solid head
// with two cut-out eyes in the colour it sits on (cut). Eyes take the
// mood's pose; the head breathes, bounces or hops with Animated.

let clips = 0;

export function Gbot({ look, seed = "gbot", mood = "idle", size = 48, live, cut }: {
  look?: Look; seed?: string; mood?: Mood; size?: number; live?: boolean; cut?: string;
}) {
  const t = resolveLook(look, seed);
  const body = bodies[t.shape];
  const layout = eyeLayout[t.eyes];
  const bg = useTheme().bg;
  const hole = cut ?? bg;
  const clip = useRef(`gbot${++clips}`).current;
  const happy = mood === "done" || mood === "happy";
  const [blink, setBlink] = useState(false);
  const anim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    if (happy || mood === "sleepy") return;
    let timer: ReturnType<typeof setTimeout>;
    const loop = () => {
      timer = setTimeout(() => { setBlink(true); setTimeout(() => setBlink(false), 120); loop(); }, 2600 + Math.random() * 4400);
    };
    loop();
    return () => clearTimeout(timer);
  }, [happy, mood]);

  useEffect(() => {
    anim.setValue(0);
    const small = size < 48;
    if (happy) {
      Animated.sequence([
        Animated.timing(anim, { toValue: 1, duration: 260, easing: Easing.out(Easing.quad), useNativeDriver: true }),
        Animated.spring(anim, { toValue: 0, friction: 4, useNativeDriver: true }),
      ]).start();
      return;
    }
    if (small && (mood === "idle" || mood === "sleepy")) return;
    const period = mood === "working" ? 350 : mood === "sleepy" ? 3000 : 2100;
    const loop = Animated.loop(Animated.sequence([
      Animated.timing(anim, { toValue: 1, duration: period, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
      Animated.timing(anim, { toValue: 0, duration: period, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
    ]));
    loop.start();
    return () => loop.stop();
  }, [mood, happy, size, anim]);

  const lift = happy ? -0.14 : mood === "working" ? -0.05 : 0;
  const transform = [
    { translateY: anim.interpolate({ inputRange: [0, 1], outputRange: [0, size * lift] }) },
    { scaleY: anim.interpolate({ inputRange: [0, 1], outputRange: [1, mood === "idle" || mood === "sleepy" ? 0.985 : 1.02] }) },
    { rotate: anim.interpolate({ inputRange: [0, 1], outputRange: mood === "thinking" ? ["-4deg", "4deg"] : mood === "waiting" ? ["-7deg", "-4deg"] : ["0deg", "0deg"] }) },
  ];

  const p = pose(mood, 0, 0);
  const [fx, fy] = body.face[t.eyes];
  const w = eyeSize[0] * body.k, h = eyeSize[1] * body.k;
  const sy = blink ? 0.08 : p.sy;

  return (
    <Animated.View style={{ width: size, height: size, transform }}>
      <Svg width={size} height={size} viewBox="-7 -7 114 114">
        <Defs>
          <ClipPath id={clip}><Path d={body.d} /></ClipPath>
        </Defs>
        <Path d={body.d} fill={hues[t.hue].body} />
        <G clipPath={`url(#${clip})`}>
          {([0, 1] as const).map((i) => {
            const side = i === 0 ? -1 : 1;
            const cx = fx + side * layout.gap * body.k + p.tx;
            const cy = fy + side * layout.lift * body.k + p.ty;
            return (
              <G key={i} transform={`translate(${cx} ${cy}) rotate(${layout.rot[i]}) scale(${p.sx} ${sy})`}>
                <Rect x={-w / 2} y={-h / 2} width={w} height={h} rx={w / 2} fill={hole} />
              </G>
            );
          })}
        </G>
        {live && <Circle cx={88} cy={88} r={9} fill="#8B5CF6" stroke={hole} strokeWidth={4} />}
      </Svg>
    </Animated.View>
  );
}

import { useEffect, useRef, useState } from "react";
import { Animated, Easing } from "react-native";
import Svg, { G, Path, Rect, Circle } from "react-native-svg";
import { hues, visor } from "@gbot/ui-tokens";
import { bodies, eyeSizes, pose, resolveLook, visorSize, type Look, type Mood } from "@gbot/characters/core";

export type { Look, Mood };

// The same gbot as on the web, drawn with react-native-svg. Eyes take
// the mood's pose; the body breathes, bounces or hops with Animated.

export function Gbot({ look, seed = "gbot", mood = "idle", size = 48, live }: {
  look?: Look; seed?: string; mood?: Mood; size?: number; live?: boolean;
}) {
  const t = resolveLook(look, seed);
  const color = hues[t.hue];
  const body = bodies[t.shape];
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

  const [fx, fy] = body.face;
  const s = body.eyeScale;
  const [ew, eh] = eyeSizes[t.eyes];
  const w = ew * s, h = eh * s, gap = 9.5 * s;
  const vw = visorSize.w * s, vh = visorSize.h * s;

  return (
    <Animated.View style={{ width: size, height: size, transform }}>
      <Svg width={size} height={size} viewBox="0 0 100 100">
        <Path d={body.d} fill={color.body} stroke={t.hue === "ink" ? "rgba(255,255,255,.14)" : "none"} strokeWidth={1.5} />
        <Rect x={fx - vw / 2} y={fy - vh / 2} width={vw} height={vh} rx={vh / 2} fill={visor} stroke={t.hue === "ink" ? "rgba(255,255,255,.16)" : "none"} strokeWidth={1} />
        {([-1, 1] as const).map((side) => {
          const cx = fx + side * gap;
          const p = pose(mood, side, 0, 0);
          if (happy) {
            return <Path key={side} d={`M${cx - 4.5 * s} ${fy + 2.2}Q${cx} ${fy - 4.5 * s} ${cx + 4.5 * s} ${fy + 2.2}`} stroke={color.eye} strokeWidth={3.2 * s} strokeLinecap="round" fill="none" />;
          }
          const sy = blink ? 0.1 : p.sy;
          return (
            <G key={side} transform={`translate(${cx + p.tx} ${fy + p.ty}) rotate(${p.rot}) scale(${p.sx} ${sy})`}>
              <Rect x={-w / 2} y={-h / 2} width={w} height={h} rx={Math.min(w, h) / 2} fill={color.eye} />
            </G>
          );
        })}
        {live && <Circle cx={84} cy={84} r={7.5} fill="#8B5CF6" stroke="#FFF9F3" strokeWidth={3} />}
      </Svg>
    </Animated.View>
  );
}

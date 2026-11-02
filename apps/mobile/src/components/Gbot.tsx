import { useEffect, useRef, useState } from "react";
import { Animated, Easing } from "react-native";
import Svg, { Circle, ClipPath, Defs, Ellipse, G, LinearGradient, Path, RadialGradient, Rect, Stop } from "react-native-svg";
import { hues } from "@gbot/ui-tokens";
import { bodies, eyeSizes, mix, pose, resolveLook, type Look, type Mood } from "@gbot/characters/core";

export type { Look, Mood };

// The same softly shaded gbot as on the web, drawn with react-native-svg
// (gradients only, no filters). The eyes are flat, upright Grok Bot-style
// pills in a light colour; they take the mood's pose, and the body
// breathes, bounces or hops with Animated.

let ids = 0;

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
  const w = ew * s, h = eh * s, gap = 12 * s;
  const uid = useRef(`g${++ids}`).current;
  const id = (k: string) => `${k}${uid}`;
  const url = (k: string) => `url(#${id(k)})`;

  return (
    <Animated.View style={{ width: size, height: size, transform }}>
      <Svg width={size} height={size} viewBox="-3 -3 106 106">
        <Defs>
          <RadialGradient id={id("body")} cx="50%" cy="12%" r="95%">
            <Stop offset="0" stopColor={mix(color.body, "#ffffff", 0.2)} />
            <Stop offset="0.55" stopColor={color.body} />
            <Stop offset="1" stopColor={mix(color.body, "#000000", 0.1)} />
          </RadialGradient>
          <LinearGradient id={id("edge")} x1="0" y1="0" x2="0" y2="1">
            <Stop offset="0" stopColor="#fff" stopOpacity={0.18} />
            <Stop offset="0.12" stopColor="#fff" stopOpacity={0} />
            <Stop offset="0.82" stopColor="#000" stopOpacity={0} />
            <Stop offset="1" stopColor="#000" stopOpacity={0.12} />
          </LinearGradient>
          <RadialGradient id={id("sheen")} cx="50%" cy="50%" r="50%">
            <Stop offset="0" stopColor="#fff" stopOpacity={0.28} />
            <Stop offset="1" stopColor="#fff" stopOpacity={0} />
          </RadialGradient>
          <RadialGradient id={id("ground")} cx="50%" cy="50%" r="50%">
            <Stop offset="0" stopColor="#000" stopOpacity={0.2} />
            <Stop offset="0.6" stopColor="#000" stopOpacity={0.07} />
            <Stop offset="1" stopColor="#000" stopOpacity={0} />
          </RadialGradient>
          <ClipPath id={id("clip")}><Path d={body.d} /></ClipPath>
        </Defs>
        <Ellipse cx={50} cy={95.5} rx={27} ry={3.4} fill={url("ground")} />
        <Path d={body.d} fill={url("body")} />
        <Path d={body.d} fill={url("edge")} />
        <G clipPath={url("clip")}>
          <Ellipse cx={42} cy={24} rx={28} ry={16} fill={url("sheen")} />
        </G>
          {([-1, 1] as const).map((side) => {
            const cx = fx + side * gap;
            const p = pose(mood, side, 0, 0);
            if (happy) {
              return <Path key={side} d={`M${cx - 7 * s} ${fy + 3}Q${cx} ${fy - 7 * s} ${cx + 7 * s} ${fy + 3}`} stroke={color.eye} strokeWidth={6 * s} strokeLinecap="round" fill="none" />;
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

import { memo, useEffect, useRef, useState, type CSSProperties } from "react";
import { resolveLook } from "./traits";
import { mount, supported, type ViewOpts } from "./gl";
import { GbotSvg, type GbotProps } from "./GbotSvg";

export type { GbotProps };
export type { Mood } from "./pose";

const reduced = () => typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;

// The bots in three dimensions, painted by one shared renderer. Every
// avatar is its own small canvas, so layout, clipping and z-index keep
// working, and the flat SVG draws itself wherever WebGL is missing.
export const Gbot = memo(function Gbot(props: GbotProps) {
  const { look, seed = "gbot", mood = "idle", size = 48, lookAt, live, lively, className, style, title } = props;
  const look3d = resolveLook(look, seed);
  const ref = useRef<HTMLCanvasElement>(null);
  const view = useRef<ReturnType<typeof mount> | null>(null);
  const [fallback] = useState(() => !supported());

  const opts: ViewOpts = {
    look: look3d,
    mood,
    size,
    dpr: Math.min(window.devicePixelRatio || 1, 2),
    lookAt: lookAt ?? null,
    lively,
    static: reduced(),
    live,
  };

  useEffect(() => {
    if (fallback || !ref.current) return;
    const v = mount(ref.current, opts);
    view.current = v;
    return () => { v.dispose(); view.current = null; };
    // Mount once; prop changes flow through update() below.
  }, [fallback]);

  useEffect(() => { view.current?.update(opts); });

  if (fallback) return <GbotSvg {...props} />;

  return (
    <canvas
      ref={ref}
      width={size}
      height={size}
      className={`gbot ${className ?? ""}`}
      data-mood={mood}
      data-small={size < 48 ? "" : undefined}
      style={{ width: size, height: size, ...style } as CSSProperties}
      role="img"
      aria-label={title ?? "bot"}
    />
  );
});

import { describe, expect, it } from "vitest";
import { eyeStyles, hueNames, resolveLook, shapes, traitsFromSeed } from "./traits";
import { bodies } from "./parts";

describe("traits", () => {
  it("is deterministic per seed", () => {
    expect(traitsFromSeed("abc")).toEqual(traitsFromSeed("abc"));
  });
  it("spreads seeds across every trait", () => {
    const seen = { shape: new Set(), hue: new Set(), eyes: new Set() };
    for (let i = 0; i < 500; i++) {
      const t = traitsFromSeed("s" + i);
      for (const k of Object.keys(seen) as (keyof typeof seen)[]) seen[k].add(t[k]);
    }
    expect(seen.shape.size).toBe(shapes.length);
    expect(seen.hue.size).toBe(hueNames.length);
    expect(seen.eyes.size).toBe(eyeStyles.length);
  });
  it("lets explicit traits win and ignores junk and old looks", () => {
    const t = resolveLook({ seed: "x", shape: "hexagon", hue: "teal", eyes: "sparkle" as never }, "y");
    expect(t.shape).toBe("hexagon");
    expect(t.hue).toBe("teal");
    expect(resolveLook({ shape: "square" as never, hue: "cobalt" as never }, "q")).toMatchObject({ shape: "squircle", hue: "blue" });
    expect(eyeStyles).toContain(t.eyes);
    expect(shapes).toContain(resolveLook({ shape: "mochi" as never }, "z").shape);
  });
  it("has a valid path for every shape", () => {
    for (const s of shapes) expect(bodies[s].d).toMatch(/^M[\d. -]+.*Z$/);
  });
});

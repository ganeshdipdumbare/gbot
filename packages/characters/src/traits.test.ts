import { describe, expect, it } from "vitest";
import { accessories, eyeStyles, hueNames, mouths, resolveLook, shapes, traitsFromSeed } from "./traits";

describe("traits", () => {
  it("is deterministic per seed", () => {
    expect(traitsFromSeed("abc")).toEqual(traitsFromSeed("abc"));
  });
  it("spreads seeds across every trait", () => {
    const seen = { shape: new Set(), hue: new Set(), eyes: new Set(), accessory: new Set(), mouth: new Set() };
    for (let i = 0; i < 500; i++) {
      const t = traitsFromSeed("s" + i);
      for (const k of Object.keys(seen) as (keyof typeof seen)[]) seen[k].add(t[k]);
    }
    expect(seen.shape.size).toBe(shapes.length);
    expect(seen.hue.size).toBe(hueNames.length);
    expect(seen.eyes.size).toBe(eyeStyles.length);
    expect(seen.accessory.size).toBe(accessories.length);
    expect(seen.mouth.size).toBe(mouths.length);
  });
  it("never puts ears on a drop", () => {
    for (let i = 0; i < 500; i++) {
      const t = traitsFromSeed("d" + i);
      if (t.shape === "drop") expect(["cat", "bear", "antenna"]).not.toContain(t.accessory);
    }
  });
  it("lets explicit traits win and ignores junk", () => {
    const t = resolveLook({ seed: "x", shape: "ghost", hue: "mint", eyes: "nope" as never }, "y");
    expect(t.shape).toBe("ghost");
    expect(t.hue).toBe("mint");
    expect(eyeStyles).toContain(t.eyes);
  });
});

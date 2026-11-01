import { describe, expect, it } from "vitest";
import { blend, poseAt, rig } from "./motion";
import { shapes } from "./traits";

const r = rig("circle", "up", "t");

describe("motion", () => {
  it("is deterministic per bot and time", () => {
    expect(poseAt(r, "idle", 3.3)).toEqual(poseAt(rig("circle", "up", "t"), "idle", 3.3));
  });
  it("turns blocked into an exclamation mark and thinking into dots", () => {
    const b = poseAt(r, "blocked", 1);
    expect(b.bang.o).toBe(1);
    expect(b.head.s).toBeLessThan(0.2);
    const th = poseAt(r, "thinking", 1);
    expect(th.dots.every((d) => d.s > 0.1)).toBe(true);
    expect(th.eyes.every((e) => e.h === 0)).toBe(true);
  });
  it("celebrates with whole turns, then rests like idle", () => {
    expect(poseAt(r, "done", 3.6).head.rot % 360).toBeCloseTo(0, 0);
    expect(poseAt(r, "done", 6)).toEqual(poseAt(r, "idle", 6 - 5.4));
  });
  it("blends moods without spinning the long way round", () => {
    const k = blend(poseAt(r, "done", 2), poseAt(r, "idle", 0), 0.5);
    expect(Math.abs(k.head.rot)).toBeLessThanOrEqual(180);
  });
  it("produces finite poses for every shape and mood", () => {
    for (const s of shapes) for (const m of ["idle", "working", "waiting", "blocked", "thinking", "done", "sleepy"] as const)
      for (const t of [0, 0.2, 2.5, 5.2, 9]) {
        const p = poseAt(rig(s, "side", s), m, t);
        expect([p.head.rot, p.head.s, ...p.eyes.flatMap((e) => [e.x, e.y, e.w, e.h])].every(Number.isFinite)).toBe(true);
      }
  });
});

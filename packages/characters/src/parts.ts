import type { Shape } from "./traits";

// Geometry for the rig, in a 100×100 box with the ground at y=92.

export const bodies: Record<Shape, { d: string; top: number }> = {
  mochi: { top: 18, d: "M50 18C77 18 88 37 88 60C88 82 72 92 50 92C28 92 12 82 12 60C12 37 23 18 50 18Z" },
  bun: { top: 24, d: "M50 24C79 24 91 45 91 66C91 86 75 92 50 92C25 92 9 86 9 66C9 45 21 24 50 24Z" },
  bean: { top: 13, d: "M50 13C71 13 82 31 82 55C82 80 68 92 50 92C32 92 18 80 18 55C18 31 29 13 50 13Z" },
  ghost: {
    top: 15,
    d: "M50 15C74 15 86 33 86 56L86 86Q77 96 68 86Q59 96 50 86Q41 96 32 86Q23 96 14 86L14 56C14 33 26 15 50 15Z",
  },
  blob: { top: 17, d: "M53 17C77 15 90 35 88 59C86 82 70 93 48 92C26 91 10 78 12 56C14 34 29 19 53 17Z" },
  drop: { top: 10, d: "M50 10C61 27 86 40 86 63C86 82 70 92 50 92C30 92 14 82 14 63C14 40 39 27 50 10Z" },
};

export const face = { y: 57, eyeDX: 12, cheekY: 66, mouthY: 66.5 };

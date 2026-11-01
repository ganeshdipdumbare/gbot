// How each mood shapes the eyes, shared by web and mobile. Everything a
// bot feels is carried by two cut-out eyes and the way its head moves.

export type Mood = "idle" | "thinking" | "working" | "waiting" | "blocked" | "done" | "sleepy" | "happy";

/** Offset (in head units) and squash for both eyes. */
export interface EyePose { tx: number; ty: number; sx: number; sy: number }

export function pose(mood: Mood, gx: number, gy: number): EyePose {
  switch (mood) {
    case "thinking":
      return { tx: 4, ty: -6, sx: 1, sy: 0.9 };
    case "working":
      return { tx: gx * 0.5, ty: 2, sx: 1.05, sy: 0.55 };
    case "waiting":
      return { tx: 0, ty: 0, sx: 1.12, sy: 1.12 };
    case "blocked":
      return { tx: 0, ty: 4, sx: 1.05, sy: 0.4 };
    case "sleepy":
      return { tx: 0, ty: 5, sx: 1.1, sy: 0.12 };
    case "done":
    case "happy":
      return { tx: 0, ty: -3, sx: 1.15, sy: 0.4 };
    default:
      return { tx: gx, ty: gy, sx: 1, sy: 1 };
  }
}

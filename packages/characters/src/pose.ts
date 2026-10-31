// How each mood shapes the eyes, shared by web and mobile. Everything a
// bot feels is carried by two eyes and the way its body moves.

export type Mood = "idle" | "thinking" | "working" | "waiting" | "blocked" | "done" | "sleepy" | "happy";

export interface EyePose { tx: number; ty: number; sx: number; sy: number; rot: number }

export function pose(mood: Mood, side: -1 | 1, gx: number, gy: number): EyePose {
  switch (mood) {
    case "thinking":
      return { tx: 3, ty: -4.5, sx: 1, sy: 0.82, rot: 0 };
    case "working":
      return { tx: gx * 0.4, ty: 1.2, sx: 1.05, sy: 0.5, rot: 0 };
    case "waiting":
      return { tx: 0, ty: -0.6, sx: 1.18, sy: 1.18, rot: 0 };
    case "blocked":
      return { tx: 0, ty: 1.5, sx: 1.25, sy: 0.42, rot: side * 24 };
    case "sleepy":
      return { tx: 0, ty: 3, sx: 1.1, sy: 0.16, rot: 0 };
    default:
      return { tx: gx, ty: gy, sx: 1, sy: 1, rot: 0 };
  }
}

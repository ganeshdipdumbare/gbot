import { useSyncExternalStore } from "react";

// A tiny toast queue: quiet confirmations and errors at the bottom of
// the screen, announced to screen readers.

export interface Toast { id: number; text: string; kind: "info" | "error"; action?: { label: string; run: () => void } }

let toasts: Toast[] = [];
let seq = 0;
const subs = new Set<() => void>();
const emit = () => subs.forEach((f) => f());

export function toast(text: string, kind: Toast["kind"] = "info", action?: Toast["action"]) {
  const t = { id: ++seq, text, kind, action };
  toasts = [...toasts.slice(-2), t];
  emit();
  setTimeout(() => dismiss(t.id), kind === "error" ? 6000 : 3500);
}

export const toastError = (e: unknown, fallback = "Something went wrong") =>
  toast(e instanceof Error && e.message ? e.message : fallback, "error");

export function dismiss(id: number) {
  toasts = toasts.filter((t) => t.id !== id);
  emit();
}

export const useToasts = () => useSyncExternalStore((l) => (subs.add(l), () => subs.delete(l)), () => toasts);

/** Runs an action, toasting ok on success and the error on failure. */
export async function attempt<T>(fn: () => Promise<T>, ok?: string): Promise<T | undefined> {
  try {
    const r = await fn();
    if (ok) toast(ok);
    return r;
  } catch (e) {
    toastError(e);
    return undefined;
  }
}

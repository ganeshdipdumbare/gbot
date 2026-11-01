import { Component, type ErrorInfo, type ReactNode } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Gbot } from "@gbot/characters";
import { dismiss, useToasts } from "../lib/toast";
import { Button, Icon } from "./ui";

export function Toaster() {
  const toasts = useToasts();
  return (
    <div className="fixed bottom-4 inset-x-0 z-[60] flex flex-col items-center gap-2 pointer-events-none px-4" role="status" aria-live="polite">
      <AnimatePresence initial={false}>
        {toasts.map((t) => (
          <motion.div key={t.id} layout
            initial={{ opacity: 0, y: 8, scale: 0.97 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, scale: 0.97, transition: { duration: 0.12 } }}
            transition={{ duration: 0.22, ease: [0.2, 0.9, 0.3, 1.15] }}
            className="pointer-events-auto flex items-center gap-3 max-w-[460px] rounded-xl bg-[var(--emphasis)] text-[var(--on-emphasis)] pl-3.5 pr-1.5 py-1.5 text-[13px] shadow-pop">
            {t.kind === "error" && <span className="size-1.5 rounded-full bg-danger shrink-0" />}
            <span className="flex-1 py-0.5">{t.text}</span>
            {t.action && (
              <button onClick={() => { t.action!.run(); dismiss(t.id); }} className="font-medium px-2 h-7 rounded-lg hover:bg-black/10 dark:hover:bg-white/10 cursor-pointer">
                {t.action.label}
              </button>
            )}
            <button onClick={() => dismiss(t.id)} aria-label="Dismiss" className="size-7 grid place-items-center rounded-lg opacity-60 hover:opacity-100 cursor-pointer [&_svg]:size-3.5">
              <Icon.close />
            </button>
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  );
}

// Keeps one broken component from blanking the whole app.
export class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state = { error: null as Error | null };
  static getDerivedStateFromError(error: Error) { return { error }; }
  componentDidCatch(error: Error, info: ErrorInfo) { console.error("gbot crashed", error, info.componentStack); }
  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="h-full grid place-items-center p-6 text-center">
        <div className="max-w-[360px]">
          <Gbot seed="crash" look={{ shape: "squircle", hue: "red", eyes: "center" }} mood="blocked" size={56} className="mx-auto" />
          <h1 className="text-[15px] font-semibold mt-4">Something broke on this screen</h1>
          <p className="text-[13px] text-ink-2 mt-1">Your bots and chats are safe on the server. Reloading usually fixes it.</p>
          <Button className="mt-4" onClick={() => location.reload()}>Reload</Button>
          <details className="mt-4 text-left text-[12px] text-ink-3">
            <summary className="cursor-pointer">Details</summary>
            <pre className="mt-2 whitespace-pre-wrap break-words font-mono">{this.state.error.message}</pre>
          </details>
        </div>
      </div>
    );
  }
}

export function Skeleton({ className = "" }: { className?: string }) {
  return <div className={`rounded-md bg-surface animate-pulse ${className}`} />;
}

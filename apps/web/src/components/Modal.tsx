import { useEffect, useId, useRef, type ReactNode } from "react";
import { AnimatePresence, motion } from "motion/react";
import { bouncy, Icon, IconButton } from "./ui";

const focusable = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

// An accessible dialog: focus moves in, Tab stays inside, Escape closes,
// and focus returns to whatever opened it.
export function Modal({ open, onClose, children, title, width = 520, side }: {
  open: boolean; onClose: () => void; children: ReactNode; title?: ReactNode; width?: number; side?: boolean;
}) {
  const panel = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const close = useRef(onClose);
  close.current = onClose;

  useEffect(() => {
    if (!open) return;
    const opener = document.activeElement as HTMLElement | null;
    const t = setTimeout(() => {
      const el = panel.current;
      if (!el || el.contains(document.activeElement)) return;
      const first = el.querySelector<HTMLElement>("[autofocus], input, textarea") ?? el.querySelector<HTMLElement>(focusable);
      (first ?? el).focus();
    }, 30);
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") { e.stopPropagation(); close.current(); return; }
      if (e.key !== "Tab" || !panel.current) return;
      const items = [...panel.current.querySelectorAll<HTMLElement>(focusable)].filter((x) => x.offsetParent !== null);
      if (!items.length) return;
      const [first, last] = [items[0], items[items.length - 1]];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    };
    window.addEventListener("keydown", onKey);
    return () => { clearTimeout(t); window.removeEventListener("keydown", onKey); opener?.focus?.(); };
  }, [open]);

  return (
    <AnimatePresence>
      {open && (
        <div className={`fixed inset-0 z-50 flex ${side ? "justify-end" : "items-end sm:items-center justify-center"}`}>
          <motion.div className="absolute inset-0 bg-black/40" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.15 }} onClick={onClose} aria-hidden />
          <motion.div
            ref={panel}
            role="dialog"
            aria-modal="true"
            aria-labelledby={title ? titleId : undefined}
            tabIndex={-1}
            className={`relative bg-bg shadow-pop overflow-hidden flex flex-col outline-none ${side ? "h-full w-full" : "w-full max-h-[88vh] rounded-t-2xl sm:rounded-2xl sm:mx-4"}`}
            style={{ maxWidth: width }}
            initial={side ? { x: 24, opacity: 0 } : { y: 8, scale: 0.97, opacity: 0 }}
            animate={side ? { x: 0, opacity: 1 } : { y: 0, scale: 1, opacity: 1 }}
            exit={side ? { x: 24, opacity: 0, transition: { duration: 0.12 } } : { y: 4, scale: 0.98, opacity: 0, transition: { duration: 0.12 } }}
            transition={bouncy}
          >
            {title !== undefined && (
              <div className="flex items-center justify-between pl-5 pr-3 h-12 shrink-0">
                <h2 id={titleId} className="text-[15px] font-semibold tracking-[-0.2px]">{title}</h2>
                <IconButton label="Close" onClick={onClose}><Icon.close /></IconButton>
              </div>
            )}
            <div className="overflow-y-auto scroll-soft">{children}</div>
          </motion.div>
        </div>
      )}
    </AnimatePresence>
  );
}

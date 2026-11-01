import { useEffect, type ReactNode } from "react";
import { AnimatePresence, motion } from "motion/react";
import { bouncy, Icon, IconButton } from "./ui";

export function Modal({ open, onClose, children, title, width = 520, side }: {
  open: boolean; onClose: () => void; children: ReactNode; title?: ReactNode; width?: number; side?: boolean;
}) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);
  return (
    <AnimatePresence>
      {open && (
        <div className={`fixed inset-0 z-50 flex ${side ? "justify-end" : "items-end sm:items-center justify-center"} `} role="dialog" aria-modal="true">
          <motion.div className="absolute inset-0 bg-black/40" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.15 }} onClick={onClose} />
          <motion.div
            className={`relative bg-bg shadow-pop overflow-hidden flex flex-col ${side ? "h-full w-full" : "w-full max-h-[88vh] rounded-t-2xl sm:rounded-2xl sm:mx-4"}`}
            style={{ maxWidth: width }}
            initial={side ? { x: 24, opacity: 0 } : { y: 8, scale: 0.97, opacity: 0 }}
            animate={side ? { x: 0, opacity: 1 } : { y: 0, scale: 1, opacity: 1 }}
            exit={side ? { x: 24, opacity: 0, transition: { duration: 0.12 } } : { y: 4, scale: 0.98, opacity: 0, transition: { duration: 0.12 } }}
            transition={bouncy}
          >
            {title !== undefined && (
              <div className="flex items-center justify-between pl-5 pr-3 h-12 shrink-0">
                <h2 className="text-[15px] font-semibold tracking-[-0.2px]">{title}</h2>
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

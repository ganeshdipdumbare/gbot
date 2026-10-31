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
          <motion.div className="absolute inset-0 bg-[#1b1224]/35 backdrop-blur-[2px]" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={onClose} />
          <motion.div
            className={`relative bg-bg shadow-pop border border-line overflow-hidden flex flex-col ${side ? "h-full w-full rounded-l-[28px]" : "w-full max-h-[92vh] rounded-t-[28px] sm:rounded-[28px] sm:mx-4"}`}
            style={{ maxWidth: width }}
            initial={side ? { x: 60, opacity: 0 } : { y: 40, scale: 0.94, opacity: 0 }}
            animate={side ? { x: 0, opacity: 1 } : { y: 0, scale: 1, opacity: 1 }}
            exit={side ? { x: 60, opacity: 0 } : { y: 30, scale: 0.96, opacity: 0 }}
            transition={bouncy}
          >
            {title !== undefined && (
              <div className="flex items-center justify-between px-5 pt-4 pb-2">
                <h2 className="font-display font-black text-[20px]">{title}</h2>
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

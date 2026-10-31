import { motion, type HTMLMotionProps } from "motion/react";
import { forwardRef, type InputHTMLAttributes, type ReactNode, type TextareaHTMLAttributes } from "react";

export const spring = { type: "spring", stiffness: 520, damping: 30, mass: 0.8 } as const;
export const bouncy = { type: "spring", stiffness: 420, damping: 16, mass: 0.7 } as const;

type Variant = "primary" | "soft" | "ghost" | "danger";
const variants: Record<Variant, string> = {
  primary: "bg-ink text-bg shadow-soft hover:shadow-pop",
  soft: "bg-surface-2 text-ink hover:bg-line",
  ghost: "text-ink-2 hover:text-ink hover:bg-surface-2",
  danger: "text-danger hover:bg-danger/10",
};

export function Button({ variant = "primary", className = "", children, ...rest }: HTMLMotionProps<"button"> & { variant?: Variant }) {
  return (
    <motion.button
      whileHover={{ scale: 1.03 }}
      whileTap={{ scale: 0.94 }}
      transition={bouncy}
      className={`inline-flex items-center justify-center gap-2 rounded-full px-4 h-10 font-display font-bold text-[15px] transition-colors disabled:opacity-50 disabled:pointer-events-none cursor-pointer ${variants[variant]} ${className}`}
      {...rest}
    >
      {children}
    </motion.button>
  );
}

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function Input({ className = "", ...rest }, ref) {
  return (
    <input
      ref={ref}
      className={`w-full h-11 rounded-2xl bg-surface border-2 border-line px-4 text-[15px] text-ink placeholder:text-ink-2/60 outline-none focus:border-live transition-colors ${className}`}
      {...rest}
    />
  );
});

export const TextArea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(function TextArea({ className = "", ...rest }, ref) {
  return (
    <textarea
      ref={ref}
      className={`w-full rounded-2xl bg-surface border-2 border-line px-4 py-3 text-[15px] text-ink placeholder:text-ink-2/60 outline-none focus:border-live transition-colors resize-none ${className}`}
      {...rest}
    />
  );
});

export function Label({ children, hint }: { children: ReactNode; hint?: ReactNode }) {
  return (
    <div className="mb-1.5 flex items-baseline justify-between gap-3">
      <span className="font-display font-extrabold text-[14px]">{children}</span>
      {hint && <span className="text-[12px] text-ink-2">{hint}</span>}
    </div>
  );
}

export function IconButton({ label, children, className = "", ...rest }: HTMLMotionProps<"button"> & { label: string }) {
  return (
    <motion.button
      aria-label={label}
      title={label}
      whileHover={{ scale: 1.08 }}
      whileTap={{ scale: 0.88 }}
      transition={bouncy}
      className={`grid place-items-center size-9 rounded-full text-ink-2 hover:text-ink hover:bg-surface-2 transition-colors cursor-pointer ${className}`}
      {...rest}
    >
      {children}
    </motion.button>
  );
}

// Rounded duotone icons, drawn to match the characters.
const I = ({ children, size = 20 }: { children: ReactNode; size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
    {children}
  </svg>
);
export const Icon = {
  plus: () => <I><path d="M12 5v14M5 12h14" /></I>,
  send: () => <I><path d="M5 12h13M12 5l7 7-7 7" /></I>,
  stop: () => <I><rect x="7" y="7" width="10" height="10" rx="3" fill="currentColor" stroke="none" /></I>,
  gear: () => <I><circle cx="12" cy="12" r="3" fill="currentColor" opacity=".25" /><path d="M12 2.8l1.6 2.3 2.8-.4.9 2.7 2.5 1.3-.6 2.7 1.6 2.3-2.1 1.8.1 2.8-2.8.6-1.4 2.4-2.6-1-2.6 1-1.4-2.4-2.8-.6.1-2.8L2.9 14.6l1.6-2.3-.6-2.7 2.5-1.3.9-2.7 2.8.4z" /><circle cx="12" cy="12" r="3" /></I>,
  search: () => <I><circle cx="11" cy="11" r="6.5" fill="currentColor" opacity=".15" /><circle cx="11" cy="11" r="6.5" /><path d="M20 20l-4.2-4.2" /></I>,
  close: () => <I><path d="M6 6l12 12M18 6L6 18" /></I>,
  menu: () => <I><path d="M4 7h16M4 12h16M4 17h10" /></I>,
  dice: () => <I><rect x="4" y="4" width="16" height="16" rx="5" fill="currentColor" opacity=".15" /><rect x="4" y="4" width="16" height="16" rx="5" /><circle cx="9" cy="9" r="1.2" fill="currentColor" /><circle cx="15" cy="15" r="1.2" fill="currentColor" /><circle cx="15" cy="9" r="1.2" fill="currentColor" /><circle cx="9" cy="15" r="1.2" fill="currentColor" /></I>,
  pin: () => <I><path d="M14 4l6 6-3 1-4 4 .5 4-2.5.5L4 13l.5-2.5 4 .5 4-4z" fill="currentColor" opacity=".15" /><path d="M14 4l6 6-3 1-4 4 .5 4-2.5.5L4 13l.5-2.5 4 .5 4-4zM4 20l5-5" /></I>,
  chip: () => <I><rect x="6" y="6" width="12" height="12" rx="3" fill="currentColor" opacity=".15" /><rect x="6" y="6" width="12" height="12" rx="3" /><path d="M9 3v3M15 3v3M9 18v3M15 18v3M3 9h3M3 15h3M18 9h3M18 15h3" /></I>,
  logout: () => <I><path d="M15 4h3a2 2 0 012 2v12a2 2 0 01-2 2h-3M10 8l-4 4 4 4M6 12h10" /></I>,
  screen: () => <I><rect x="3" y="4" width="18" height="12" rx="3" fill="currentColor" opacity=".15" /><rect x="3" y="4" width="18" height="12" rx="3" /><path d="M9 20h6M12 16v4" /></I>,
  mic: () => <I><rect x="9" y="3" width="6" height="11" rx="3" fill="currentColor" opacity=".15" /><rect x="9" y="3" width="6" height="11" rx="3" /><path d="M5 11a7 7 0 0014 0M12 18v3" /></I>,
  speaker: () => <I><path d="M4 10v4h3l5 4V6L7 10z" fill="currentColor" opacity=".15" /><path d="M4 10v4h3l5 4V6L7 10zM16 9a4 4 0 010 6M18.5 6.5a8 8 0 010 11" /></I>,
  clock: () => <I><circle cx="12" cy="12" r="8.5" fill="currentColor" opacity=".15" /><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></I>,
  plug: () => <I><path d="M8 3v5M16 3v5M6 8h12v3a6 6 0 01-12 0zM12 17v4" /></I>,
  users: () => <I><circle cx="9" cy="9" r="3.5" fill="currentColor" opacity=".15" /><circle cx="9" cy="9" r="3.5" /><path d="M3 19a6 6 0 0112 0M16 5.5a3.5 3.5 0 010 7M18 19a6 6 0 00-2.5-4.9" /></I>,
  spark: () => <I><path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z" fill="currentColor" opacity=".15" /><path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8zM19 17l.7 2 2 .7-2 .7-.7 2-.7-2-2-.7 2-.7z" /></I>,
  globe: () => <I><circle cx="12" cy="12" r="8.5" /><path d="M3.5 12h17M12 3.5c2.5 2.6 2.5 14.4 0 17M12 3.5c-2.5 2.6-2.5 14.4 0 17" /></I>,
  play: () => <I><path d="M8 5.5v13l10-6.5z" fill="currentColor" opacity=".2" /><path d="M8 5.5v13l10-6.5z" /></I>,
  trash: () => <I><path d="M5 7h14M10 11v6M14 11v6M7 7l1 12h8l1-12M9.5 7V4.5h5V7" /></I>,
  check: () => <I><path d="M5 12.5l4.5 4.5L19 7.5" /></I>,
  heart: () => <I><path d="M12 20s-7-4.4-7-10a4 4 0 017-2.6A4 4 0 0119 10c0 5.6-7 10-7 10z" fill="currentColor" opacity=".2" /><path d="M12 20s-7-4.4-7-10a4 4 0 017-2.6A4 4 0 0119 10c0 5.6-7 10-7 10z" /></I>,
};

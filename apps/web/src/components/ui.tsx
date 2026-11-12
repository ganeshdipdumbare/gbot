import { motion, type HTMLMotionProps } from "motion/react";
import { forwardRef, useState, type InputHTMLAttributes, type ReactNode, type TextareaHTMLAttributes } from "react";

// Snappy motion, like Grok Bot. Panels and dialogs land fast with no
// overshoot and leave faster still; small things keep a hint of bounce.
export const spring = { type: "tween", duration: 0.18, ease: [0.22, 1, 0.36, 1] } as const;
export const leave = { type: "tween", duration: 0.12, ease: [0.4, 0, 1, 1] } as const;
export const fade = { duration: 0.12 } as const;
export const bouncy = { type: "tween", duration: 0.28, ease: [0.2, 0.9, 0.3, 1.15] } as const;

type Variant = "primary" | "soft" | "ghost" | "danger";
const variants: Record<Variant, string> = {
  primary: "bg-emphasis text-on-emphasis hover:opacity-90",
  soft: "bg-surface text-ink hover:bg-hover hairline",
  ghost: "text-ink-2 hover:text-ink hover:bg-hover",
  danger: "text-danger hover:bg-danger/10",
};

export function Button({ variant = "primary", className = "", children, ...rest }: HTMLMotionProps<"button"> & { variant?: Variant }) {
  return (
    <motion.button
      whileTap={{ scale: 0.97 }}
      transition={{ duration: 0.1 }}
      className={`inline-flex items-center justify-center gap-1.5 rounded-full px-3.5 h-8 text-[13px] font-medium transition-colors duration-[120ms] disabled:opacity-40 disabled:pointer-events-none cursor-pointer select-none ${variants[variant]} ${className}`}
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
      className={`w-full h-9 rounded-[10px] bg-surface hairline px-3 text-[14px] text-ink placeholder:text-ink-3 outline-none focus:shadow-[inset_0_0_0_1px_var(--accent)] transition-shadow duration-[120ms] ${className}`}
      {...rest}
    />
  );
});

export const TextArea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(function TextArea({ className = "", ...rest }, ref) {
  return (
    <textarea
      ref={ref}
      className={`w-full rounded-[10px] bg-surface hairline px-3 py-2 text-[14px] leading-5 text-ink placeholder:text-ink-3 outline-none focus:shadow-[inset_0_0_0_1px_var(--accent)] transition-shadow duration-[120ms] resize-none ${className}`}
      {...rest}
    />
  );
});

export function Label({ children, hint }: { children: ReactNode; hint?: ReactNode }) {
  return (
    <div className="mb-1.5 flex items-baseline justify-between gap-3">
      <span className="text-[13px] font-medium text-ink-2">{children}</span>
      {hint && <span className="text-[12px] text-ink-3">{hint}</span>}
    </div>
  );
}

export function IconButton({ label, children, className = "", ...rest }: HTMLMotionProps<"button"> & { label: string }) {
  return (
    <motion.button
      aria-label={label}
      title={label}
      whileTap={{ scale: 0.92 }}
      transition={{ duration: 0.1 }}
      className={`grid place-items-center size-8 rounded-lg text-ink-2 hover:text-ink hover:bg-hover transition-colors duration-[120ms] cursor-pointer ${className}`}
      {...rest}
    >
      {children}
    </motion.button>
  );
}

// Line icons, drawn on a 24px grid.
const I = ({ children, size = 18 }: { children: ReactNode; size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
    {children}
  </svg>
);
export const Icon = {
  plus: () => <I><path d="M12 5v14M5 12h14" /></I>,
  send: () => <I><path d="M5 12h13M12 5l7 7-7 7" /></I>,
  stop: () => <I><rect x="7" y="7" width="10" height="10" rx="3" fill="currentColor" stroke="none" /></I>,
  pause: () => <I><path d="M9 5v14M15 5v14" /></I>,
  gear: () => <I><path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z" /><circle cx="12" cy="12" r="3" /></I>,
  search: () => <I><circle cx="11" cy="11" r="6.5" /><path d="M20 20l-4.2-4.2" /></I>,
  close: () => <I><path d="M6 6l12 12M18 6L6 18" /></I>,
  menu: () => <I><path d="M4 7h16M4 12h16M4 17h10" /></I>,
  dice: () => <I><rect x="4" y="4" width="16" height="16" rx="5" /><circle cx="9" cy="9" r="1.2" fill="currentColor" /><circle cx="15" cy="15" r="1.2" fill="currentColor" /><circle cx="15" cy="9" r="1.2" fill="currentColor" /><circle cx="9" cy="15" r="1.2" fill="currentColor" /></I>,
  pin: () => <I><path d="M14 4l6 6-3 1-4 4 .5 4-2.5.5L4 13l.5-2.5 4 .5 4-4zM4 20l5-5" /></I>,
  unread: () => <I><circle cx="12" cy="12" r="8" /><circle cx="12" cy="12" r="2.5" fill="currentColor" /></I>,
  eye: () => <I><path d="M2.5 12s3.5-6 9.5-6 9.5 6 9.5 6-3.5 6-9.5 6S2.5 12 2.5 12z" /><circle cx="12" cy="12" r="2.5" /></I>,
  eyeOff: () => <I><path d="M3 3l18 18M10.6 6.2A9.7 9.7 0 0112 6c6 0 9.5 6 9.5 6a15 15 0 01-3 3.5M6.5 7.6A15.5 15.5 0 002.5 12s3.5 6 9.5 6a9.8 9.8 0 004.2-.9" /></I>,
  lock: () => <I><rect x="5" y="10" width="14" height="10" rx="2.5" /><path d="M8 10V7a4 4 0 018 0v3" /></I>,
  chip: () => <I><rect x="6" y="6" width="12" height="12" rx="3" /><path d="M9 3v3M15 3v3M9 18v3M15 18v3M3 9h3M3 15h3M18 9h3M18 15h3" /></I>,
  logout: () => <I><path d="M15 4h3a2 2 0 012 2v12a2 2 0 01-2 2h-3M10 8l-4 4 4 4M6 12h10" /></I>,
  screen: () => <I><rect x="3" y="4" width="18" height="12" rx="3" /><path d="M9 20h6M12 16v4" /></I>,
  mic: () => <I><rect x="9" y="3" width="6" height="11" rx="3" /><path d="M5 11a7 7 0 0014 0M12 18v3" /></I>,
  speaker: () => <I><path d="M4 10v4h3l5 4V6L7 10zM16 9a4 4 0 010 6M18.5 6.5a8 8 0 010 11" /></I>,
  clock: () => <I><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></I>,
  plug: () => <I><path d="M8 3v5M16 3v5M6 8h12v3a6 6 0 01-12 0zM12 17v4" /></I>,
  users: () => <I><circle cx="9" cy="9" r="3.5" /><path d="M3 19a6 6 0 0112 0M16 5.5a3.5 3.5 0 010 7M18 19a6 6 0 00-2.5-4.9" /></I>,
  spark: () => <I><path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8zM19 17l.7 2 2 .7-2 .7-.7 2-.7-2-2-.7 2-.7z" /></I>,
  globe: () => <I><circle cx="12" cy="12" r="8.5" /><path d="M3.5 12h17M12 3.5c2.5 2.6 2.5 14.4 0 17M12 3.5c-2.5 2.6-2.5 14.4 0 17" /></I>,
  folder: () => <I><path d="M3 7.5A2.5 2.5 0 015.5 5H9l2 2h7.5A2.5 2.5 0 0121 9.5v7A2.5 2.5 0 0118.5 19h-13A2.5 2.5 0 013 16.5z" /></I>,
  terminal: () => <I><rect x="3" y="4" width="18" height="16" rx="3" /><path d="M7 9l3 3-3 3M12 15h5" /></I>,
  play: () => <I><path d="M8 5.5v13l10-6.5z" /></I>,
  expand: () => <I><path d="M14 4h6v6M10 20H4v-6M20 4l-6.5 6.5M4 20l6.5-6.5" /></I>,
  control: () => <I><path d="M5 3.5l14 7-6.2 2.1L10.5 19z" /><path d="M13 13l5.5 5.5" /></I>,
  moon: () => <I><path d="M19.5 14.5A7.5 7.5 0 019.5 4.5a7.5 7.5 0 1010 10z" /></I>,
  power: () => <I><path d="M12 3.5V11M7.2 6.5a7.5 7.5 0 109.6 0" /></I>,
  trash: () => <I><path d="M5 7h14M10 11v6M14 11v6M7 7l1 12h8l1-12M9.5 7V4.5h5V7" /></I>,
  up: () => <I><path d="M12 19V5M5.5 11.5L12 5l6.5 6.5" /></I>,
  copy: () => <I><rect x="8" y="8" width="12" height="12" rx="2.5" /><path d="M16 8V6.5A2.5 2.5 0 0013.5 4h-7A2.5 2.5 0 004 6.5v7A2.5 2.5 0 006.5 16H8" /></I>,
  check: () => <I><path d="M5 12.5l4.5 4.5L19 7.5" /></I>,
  heart: () => <I><path d="M12 20s-7-4.4-7-10a4 4 0 017-2.6A4 4 0 0119 10c0 5.6-7 10-7 10z" /></I>,
};

// A password field with a show/hide control, for sign-in and secrets.
export const PasswordInput = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function PasswordInput({ className = "", ...rest }, ref) {
  const [show, setShow] = useState(false);
  return (
    <div className="relative">
      <Input ref={ref} {...rest} type={show ? "text" : "password"} className={`pr-10 ${className}`} />
      <button type="button" onClick={() => setShow((v) => !v)} aria-label={show ? "Hide password" : "Show password"} aria-pressed={show}
        className="absolute right-1 top-1/2 -translate-y-1/2 grid place-items-center size-7 rounded-md text-ink-3 hover:text-ink hover:bg-hover transition-colors cursor-pointer">
        {show ? <Icon.eyeOff /> : <Icon.eye />}
      </button>
    </div>
  );
});

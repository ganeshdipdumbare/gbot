import { type ReactNode } from "react";
import { Pressable, Text, type PressableProps, type ViewStyle } from "react-native";
import * as Haptics from "expo-haptics";
import { useTheme } from "../lib/theme";

export function Button({ children, variant = "primary", style, onPress, ...rest }: PressableProps & { children: ReactNode; variant?: "primary" | "soft" | "ghost"; style?: ViewStyle }) {
  const t = useTheme();
  const bg = variant === "primary" ? t.ink : variant === "soft" ? t.surface2 : "transparent";
  const fg = variant === "primary" ? t.bg : t.ink;
  return (
    <Pressable
      {...rest}
      onPress={(e) => { Haptics.selectionAsync(); onPress?.(e); }}
      style={({ pressed }) => [{ height: 44, paddingHorizontal: 18, borderRadius: 999, backgroundColor: bg, alignItems: "center", justifyContent: "center", flexDirection: "row", gap: 6, transform: [{ scale: pressed ? 0.95 : 1 }], opacity: rest.disabled ? 0.5 : 1 }, style]}
    >
      {typeof children === "string" ? <Text style={{ color: fg, fontSize: 15, fontWeight: "700" }}>{children}</Text> : children}
    </Pressable>
  );
}

export function timeAgo(iso?: string) {
  if (!iso) return "";
  const s = (Date.now() - new Date(iso).getTime()) / 1000;
  if (s < 60) return "now";
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  if (s < 86400) return `${Math.floor(s / 3600)}h`;
  return `${Math.floor(s / 86400)}d`;
}

export const presenceText: Record<string, string> = { thinking: "Thinking…", working: "Working…", waiting: "Needs you", blocked: "Stuck", done: "Done!" };

export function moodOf(state?: string) {
  return (state && state !== "idle" ? state : "idle") as "idle" | "thinking" | "working" | "waiting" | "blocked" | "done";
}

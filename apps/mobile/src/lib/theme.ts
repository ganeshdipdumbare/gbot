import { useColorScheme } from "react-native";
import { dark, light, live } from "@gbot/ui-tokens";

export function useTheme() {
  const c = useColorScheme() === "dark" ? dark : light;
  return { ...c, live, danger: "#D9486B", dark: useColorScheme() === "dark" };
}
export type Theme = ReturnType<typeof useTheme>;

export const font = {
  display: { fontWeight: "800" as const, letterSpacing: -0.3 },
  body: { fontSize: 15, lineHeight: 21 },
};

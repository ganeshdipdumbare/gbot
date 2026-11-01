import { useEffect, useState } from "react";
import { View } from "react-native";
import { Stack, router } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { SafeAreaProvider } from "react-native-safe-area-context";
import { Gbot } from "../components/Gbot";
import { api, loadBase } from "../lib/api";
import { connect, loadAll, set } from "../lib/store";
import { useTheme } from "../lib/theme";

export default function Layout() {
  const t = useTheme();
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let off: (() => void) | undefined;
    (async () => {
      await loadBase();
      try {
        const me = await api.me();
        set(() => ({ me }));
        await loadAll();
        off = connect();
      } catch {
        router.replace("/login");
      }
      setReady(true);
    })();
    return () => off?.();
  }, []);

  return (
    <SafeAreaProvider>
      <StatusBar style={t.dark ? "light" : "dark"} />
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: t.bg }, animation: "slide_from_right" }} />
      {!ready && (
        <View style={{ position: "absolute", inset: 0, backgroundColor: t.bg, alignItems: "center", justifyContent: "center" }}>
          <Gbot seed="boot" look={{ shape: "circle", hue: "blue", eyes: "up" }} mood="thinking" size={88} />
        </View>
      )}
    </SafeAreaProvider>
  );
}

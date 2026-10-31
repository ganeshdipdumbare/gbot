import { useEffect, useState } from "react";
import { Image, Pressable, ScrollView, Switch, Text, View } from "react-native";
import { router, useLocalSearchParams } from "expo-router";
import { SafeAreaView } from "react-native-safe-area-context";
import { Gbot } from "../../components/Gbot";
import { api, type Routine } from "../../lib/api";
import { useStore } from "../../lib/store";
import { useTheme } from "../../lib/theme";

// The bot's computer (watch it work) and its routines (pause, delete).
// Creating routines and taking control stay on web and desktop.
export default function Computer() {
  const t = useTheme();
  const { id } = useLocalSearchParams<{ id: string }>();
  const bot = useStore((s) => s.bots.find((b) => b.id === id));
  const [state, setState] = useState("off");
  const [frame, setFrame] = useState<string | null>(null);
  const [routines, setRoutines] = useState<Routine[]>([]);

  const loadRoutines = () => api.routines(id).then((r) => setRoutines(r.routines)).catch(() => {});
  useEffect(() => {
    loadRoutines();
    const tick = async () => {
      const c = await api.computer(id).catch(() => null);
      setState(c?.state ?? "off");
      if (c?.state === "on") setFrame(api.screen(id));
    };
    tick();
    const h = setInterval(tick, 1500);
    return () => clearInterval(h);
  }, [id]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!bot) return null;
  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: t.bg }}>
      <View style={{ flexDirection: "row", alignItems: "center", gap: 10, paddingHorizontal: 12, height: 56 }}>
        <Pressable onPress={() => router.back()} hitSlop={12} accessibilityLabel="Back"><Text style={{ color: t.ink, fontSize: 26 }}>‹</Text></Pressable>
        <Text style={{ color: t.ink, fontWeight: "800", fontSize: 18 }}>{bot.name}'s computer</Text>
      </View>
      <ScrollView contentContainerStyle={{ padding: 16, gap: 16 }}>
        <View style={{ aspectRatio: 16 / 10, borderRadius: 18, overflow: "hidden", backgroundColor: t.surface2, borderWidth: 1, borderColor: t.line, alignItems: "center", justifyContent: "center" }}>
          {state === "on" && frame ? (
            <Image source={{ uri: frame }} style={{ width: "100%", height: "100%" }} resizeMode="cover" fadeDuration={0} />
          ) : (
            <View style={{ alignItems: "center", gap: 8 }}>
              <Gbot look={bot.look} seed={bot.id} size={56} mood={state === "starting" ? "working" : "sleepy"} />
              <Text style={{ color: t.ink2, fontSize: 13 }}>{state === "starting" ? "Waking up…" : "Asleep. It wakes when it needs it."}</Text>
            </View>
          )}
        </View>
        <Text style={{ color: t.ink, fontWeight: "800", fontSize: 17 }}>Routines</Text>
        {routines.length === 0 && <Text style={{ color: t.ink2 }}>No routines. Ask {bot.name} in chat to set one up.</Text>}
        {routines.map((r) => (
          <View key={r.id} style={{ padding: 14, borderRadius: 18, backgroundColor: t.surface, borderWidth: 1, borderColor: t.line, gap: 4 }}>
            <View style={{ flexDirection: "row", alignItems: "center" }}>
              <View style={{ flex: 1 }}>
                <Text style={{ color: t.ink, fontWeight: "700", fontSize: 15 }}>{r.name}</Text>
                <Text style={{ color: t.ink2, fontSize: 13 }}>{r.active ? r.when : "Paused"}</Text>
              </View>
              <Switch value={r.active} trackColor={{ true: t.live }} onValueChange={async (v) => { await api.setRoutine(id, r.id, v); loadRoutines(); }} />
            </View>
            <Text style={{ color: t.ink, fontSize: 14 }} numberOfLines={2}>{r.prompt}</Text>
            <Pressable onPress={async () => { await api.deleteRoutine(id, r.id); loadRoutines(); }} hitSlop={6}>
              <Text style={{ color: t.danger, fontSize: 13, fontWeight: "600", marginTop: 4 }}>Delete</Text>
            </Pressable>
          </View>
        ))}
      </ScrollView>
    </SafeAreaView>
  );
}

import { FlatList, Pressable, Text, View } from "react-native";
import { router } from "expo-router";
import { SafeAreaView } from "react-native-safe-area-context";
import { Gbot } from "../components/Gbot";
import { useStore } from "../lib/store";
import { useTheme } from "../lib/theme";
import { moodOf, presenceText, timeAgo } from "../components/ui";

// The roster: every bot and group, scannable by face.
export default function Roster() {
  const t = useTheme();
  const bots = useStore((s) => s.bots);
  const groups = useStore((s) => s.groups);
  const presence = useStore((s) => s.presence);
  const rows = [
    ...bots.map((b) => ({ key: b.id, bot: b, group: undefined })),
    ...groups.map((g) => ({ key: "g:" + g.id, bot: undefined, group: g })),
  ];

  return (
    <SafeAreaView edges={["top"]} style={{ flex: 1, backgroundColor: t.bg }}>
      <View style={{ flexDirection: "row", alignItems: "center", gap: 8, paddingHorizontal: 20, paddingVertical: 12 }}>
        <Gbot seed="brand" look={{ shape: "circle", hue: "blue", eyes: "up" }} size={32} />
        <Text style={{ color: t.ink, fontSize: 26, fontWeight: "800", letterSpacing: -0.5 }}>gbot</Text>
      </View>
      <FlatList
        data={rows}
        keyExtractor={(r) => r.key}
        contentContainerStyle={{ paddingHorizontal: 12, paddingBottom: 32 }}
        renderItem={({ item }) => {
          const p = item.bot ? presence[item.bot.id] : undefined;
          const busy = p && p.state !== "idle";
          const last = item.bot?.lastMessage ?? item.group?.lastMessage;
          const members = item.group ? item.group.botIds.map((id) => bots.find((b) => b.id === id)).filter(Boolean) : [];
          return (
            <Pressable onPress={() => router.push(`/chat/${encodeURIComponent(item.key)}`)}
              style={({ pressed }) => ({ flexDirection: "row", alignItems: "center", gap: 12, padding: 10, borderRadius: 20, backgroundColor: pressed ? t.surface2 : "transparent" })}>
              {item.bot ? <Gbot look={item.bot.look} seed={item.bot.id} size={48} mood={moodOf(p?.state)} live={p?.live} /> : (
                <View style={{ width: 48, height: 48, flexDirection: "row", alignItems: "center" }}>
                  {members.slice(0, 2).map((b, i) => <View key={b!.id} style={{ marginLeft: i ? -14 : 0 }}><Gbot look={b!.look} seed={b!.id} size={30} /></View>)}
                </View>
              )}
              <View style={{ flex: 1, minWidth: 0 }}>
                <View style={{ flexDirection: "row", alignItems: "baseline" }}>
                  <Text style={{ color: t.ink, fontSize: 16, fontWeight: "800", flex: 1 }} numberOfLines={1}>{item.bot?.name ?? item.group?.name}</Text>
                  <Text style={{ color: t.ink2, fontSize: 12 }}>{timeAgo(last?.createdAt)}</Text>
                </View>
                <Text style={{ color: busy ? t.live : t.ink2, fontWeight: busy ? "600" : "400", fontSize: 14 }} numberOfLines={1}>
                  {busy ? (p!.state === "working" && p!.detail ? p!.detail : presenceText[p!.state] ?? "") : last?.content.replace(/[#*_`>]/g, "") ?? item.bot?.title ?? "Group"}
                </Text>
              </View>
            </Pressable>
          );
        }}
      />
    </SafeAreaView>
  );
}

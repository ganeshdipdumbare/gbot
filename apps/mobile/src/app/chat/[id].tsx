import { useEffect, useMemo, useRef, useState } from "react";
import { FlatList, KeyboardAvoidingView, Platform, Pressable, Text, TextInput, View } from "react-native";
import { router, useLocalSearchParams } from "expo-router";
import { SafeAreaView } from "react-native-safe-area-context";
import { Gbot } from "../../components/Gbot";
import { api, type Bot, type Message } from "../../lib/api";
import { loadMessages, send, upsertMessage, useStore } from "../../lib/store";
import { useTheme, type Theme } from "../../lib/theme";
import { Button, moodOf, presenceText } from "../../components/ui";

// One chat: a bot's own, or a group's. Cards can be answered right here.
export default function Chat() {
  const t = useTheme();
  const { id } = useLocalSearchParams<{ id: string }>();
  const key = decodeURIComponent(id);
  const isGroup = key.startsWith("g:");
  const bots = useStore((s) => s.bots);
  const bot = useStore((s) => s.bots.find((b) => b.id === key));
  const group = useStore((s) => s.groups.find((g) => "g:" + g.id === key));
  const messages = useStore((s) => s.messages[key]);
  const presence = useStore((s) => s.presence);
  const [text, setText] = useState("");
  const list = useRef<FlatList<Message>>(null);
  const byId = useMemo(() => Object.fromEntries(bots.map((b) => [b.id, b])), [bots]);

  useEffect(() => { loadMessages(key).catch(() => {}); }, [key]);

  const p = bot ? presence[bot.id] : undefined;
  const busy = isGroup ? group?.botIds.some((b) => ["thinking", "working"].includes(presence[b]?.state ?? "")) : !!p && ["thinking", "working"].includes(p.state);
  const title = bot?.name ?? group?.name ?? "";

  return (
    <SafeAreaView edges={["top", "bottom"]} style={{ flex: 1, backgroundColor: t.bg }}>
      <View style={{ flexDirection: "row", alignItems: "center", gap: 10, paddingHorizontal: 12, height: 56, borderBottomWidth: 1, borderBottomColor: t.line }}>
        <Pressable onPress={() => router.back()} hitSlop={12} accessibilityLabel="Back"><Text style={{ color: t.ink, fontSize: 26 }}>‹</Text></Pressable>
        {bot && <Gbot look={bot.look} seed={bot.id} size={36} mood={moodOf(p?.state)} live={p?.live} />}
        <View style={{ flex: 1 }}>
          <Text style={{ color: t.ink, fontWeight: "800", fontSize: 17 }} numberOfLines={1}>{title}</Text>
          <Text style={{ color: p && p.state !== "idle" ? t.live : t.ink2, fontSize: 12.5, fontWeight: p && p.state !== "idle" ? "600" : "400" }} numberOfLines={1}>
            {p && p.state !== "idle" ? (p.state === "working" && p.detail ? p.detail : presenceText[p.state]) : bot?.title ?? group?.botIds.map((b) => byId[b]?.name).join(", ")}
          </Text>
        </View>
        {bot && (
          <Pressable onPress={() => router.push(`/computer/${bot.id}`)} hitSlop={8} accessibilityLabel="Computer and routines"
            style={{ paddingHorizontal: 12, height: 34, borderRadius: 999, backgroundColor: t.surface2, justifyContent: "center" }}>
            <Text style={{ color: p?.live ? t.live : t.ink, fontWeight: "700", fontSize: 13 }}>Computer</Text>
          </Pressable>
        )}
      </View>

      <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === "ios" ? "padding" : undefined}>
        <FlatList
          ref={list}
          data={messages ?? []}
          keyExtractor={(m) => m.id}
          contentContainerStyle={{ padding: 12, gap: 6 }}
          onContentSizeChange={() => list.current?.scrollToEnd({ animated: true })}
          ListEmptyComponent={bot ? (
            <View style={{ alignItems: "center", paddingTop: 80, gap: 10 }}>
              <Gbot look={bot.look} seed={bot.id} size={110} />
              <Text style={{ color: t.ink, fontSize: 24, fontWeight: "800" }}>Hi, I'm {bot.name}</Text>
            </View>
          ) : undefined}
          renderItem={({ item, index }) => {
            const prev = messages?.[index - 1];
            // Another bot's message to this one is shown as coming from it.
            const from = typeof item.data?.from === "string" ? bots.find((b) => b.id === item.data!.from) : undefined;
            const author = from ?? (item.botId ? byId[item.botId] : undefined);
            const showName = (isGroup || !!from) && (!prev || prev.botId !== item.botId || prev.role !== item.role || prev.data?.from !== item.data?.from);
            return <Row m={item} author={author} showName={showName} t={t} />;
          }}
          ListFooterComponent={busy ? (
            <View style={{ flexDirection: "row", alignItems: "center", gap: 8, paddingTop: 8 }}>
              {(isGroup ? group!.botIds.filter((b) => ["thinking", "working"].includes(presence[b]?.state ?? "")) : [bot!.id]).map((b) => byId[b] && (
                <Gbot key={b} look={byId[b].look} seed={b} size={32} mood={presence[b]?.state === "working" ? "working" : "thinking"} />
              ))}
              <Text style={{ color: t.ink2, fontSize: 13 }}>{presence[bot?.id ?? ""]?.detail ?? "Thinking…"}</Text>
            </View>
          ) : undefined}
        />
        <View style={{ flexDirection: "row", alignItems: "flex-end", gap: 8, margin: 10, padding: 6, paddingLeft: 16, borderRadius: 26, borderWidth: 2, borderColor: t.line, backgroundColor: t.surface }}>
          <TextInput value={text} onChangeText={setText} placeholder={`Message ${title}…`} placeholderTextColor={t.ink2} multiline
            style={{ flex: 1, color: t.ink, fontSize: 16, maxHeight: 140, paddingVertical: 9 }} accessibilityLabel={`Message ${title}`} />
          {busy && !text.trim() ? (
            <Pressable onPress={() => api.stop(key)} accessibilityLabel="Stop" style={{ width: 40, height: 40, borderRadius: 20, backgroundColor: t.surface2, alignItems: "center", justifyContent: "center" }}>
              <View style={{ width: 12, height: 12, borderRadius: 3, backgroundColor: t.ink }} />
            </Pressable>
          ) : (
            <Pressable disabled={!text.trim()} onPress={() => { const v = text.trim(); setText(""); send(key, v); }} accessibilityLabel="Send"
              style={{ width: 40, height: 40, borderRadius: 20, backgroundColor: t.ink, opacity: text.trim() ? 1 : 0.3, alignItems: "center", justifyContent: "center" }}>
              <Text style={{ color: t.bg, fontSize: 18, fontWeight: "800" }}>↑</Text>
            </Pressable>
          )}
        </View>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

// Light Markdown cleanup for plain text bubbles.
const plain = (s: string) => s.replace(/```[a-z]*\n?/g, "").replace(/`([^`]+)`/g, "$1").replace(/\*\*([^*]+)\*\*/g, "$1").replace(/^#+\s*/gm, "").replace(/^\s*[-*]\s+/gm, "• ");

function Row({ m, author, showName, t }: { m: Message; author?: Bot; showName: boolean; t: Theme }) {
  if (m.role === "event") {
    return <Text style={{ alignSelf: "center", color: m.data?.kind === "error" ? t.danger : t.ink2, fontSize: 13, marginVertical: 6, textAlign: "center" }}>{m.content.replace(/\*\*/g, "")}</Text>;
  }
  if (m.role === "card") return author ? <CardRow m={m} bot={author} t={t} /> : null;
  const mine = m.role === "user" && !m.data?.from;
  return (
    <View style={{ alignItems: mine ? "flex-end" : "flex-start", marginTop: showName ? 8 : 0 }}>
      {showName && !mine && author && <Text style={{ color: t.ink2, fontSize: 12, fontWeight: "600", marginLeft: 44, marginBottom: 2 }}>{author.name}</Text>}
      <View style={{ flexDirection: "row", alignItems: "flex-end", gap: 8, maxWidth: "86%" }}>
        {!mine && author && <Gbot look={author.look} seed={author.id} size={30} mood={m.streaming ? "working" : "idle"} />}
        <View style={{ paddingHorizontal: 14, paddingVertical: 10, borderRadius: 22, backgroundColor: mine ? t.bubbleMe : t.surface, borderWidth: mine ? 0 : 1, borderColor: t.line, opacity: m.pending ? 0.7 : 1, flexShrink: 1 }}>
          <Text style={{ color: mine ? t.bubbleMeInk : t.ink, fontSize: 15, lineHeight: 21 }}>{plain(m.content) || " "}</Text>
        </View>
      </View>
    </View>
  );
}

function CardRow({ m, bot, t }: { m: Message; bot: Bot; t: Theme }) {
  const [answer, setAnswer] = useState("");
  const d = m.data ?? {};
  const kind = d.kind as string;
  const status = d.status as string;
  const act = async (decision: string, text?: string) => { try { upsertMessage(await api.answer(m.id, decision, text)); } catch { /* card may be done */ } };

  if (status !== "pending") {
    return <Text style={{ alignSelf: "center", color: t.ink2, fontSize: 13, marginVertical: 6 }}>{status[0].toUpperCase() + status.slice(1)} · {d.answer ? `“${d.answer}”` : m.content}</Text>;
  }
  const options = (d.options as string[] | undefined) ?? [];
  return (
    <View style={{ marginVertical: 8, padding: 16, borderRadius: 22, backgroundColor: t.surface, borderWidth: 1, borderColor: t.line, gap: 12 }}>
      <View style={{ flexDirection: "row", gap: 10, alignItems: "center" }}>
        <Gbot look={bot.look} seed={bot.id} size={34} mood="waiting" />
        <View style={{ flex: 1 }}>
          <Text style={{ color: t.live, fontSize: 11.5, fontWeight: "700", letterSpacing: 0.8 }}>{kind === "approval" ? "NEEDS YOUR OK" : kind === "question" ? "QUESTION" : "NEEDS YOU ON ITS COMPUTER"}</Text>
          <Text style={{ color: t.ink, fontSize: 16, fontWeight: "800" }}>{m.content}</Text>
        </View>
      </View>
      {kind === "approval" && !!d.args && (
        <Text style={{ color: t.ink, backgroundColor: t.surface2, padding: 10, borderRadius: 14, fontFamily: Platform.OS === "ios" ? "Menlo" : "monospace", fontSize: 12.5 }} numberOfLines={8}>
          {String((d.args as Record<string, unknown>).command ?? (d.args as Record<string, unknown>).url ?? JSON.stringify(d.args, null, 1))}
        </Text>
      )}
      {kind === "approval" && (
        <View style={{ flexDirection: "row", gap: 8 }}>
          <Button onPress={() => act("approve")}>Approve</Button>
          <Button variant="soft" onPress={() => act("reject")}>Decline</Button>
        </View>
      )}
      {kind === "question" && (
        <>
          {options.length > 0 && (
            <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8 }}>
              {options.map((o) => <Button key={o} variant="soft" onPress={() => act("answer", o)}>{o}</Button>)}
            </View>
          )}
          <View style={{ flexDirection: "row", gap: 8 }}>
            <TextInput value={answer} onChangeText={setAnswer} placeholder="Type your answer" placeholderTextColor={t.ink2}
              style={{ flex: 1, height: 42, borderRadius: 21, backgroundColor: t.surface2, paddingHorizontal: 14, color: t.ink }} />
            <Button disabled={!answer.trim()} onPress={() => act("answer", answer.trim())}>Send</Button>
          </View>
        </>
      )}
      {kind === "takeover" && <Button variant="soft" onPress={() => act("answer")}>I'm done</Button>}
    </View>
  );
}

import { useState } from "react";
import { KeyboardAvoidingView, Platform, Text, TextInput, View } from "react-native";
import { router } from "expo-router";
import { SafeAreaView } from "react-native-safe-area-context";
import { Gbot } from "../components/Gbot";
import { api, getBase, setBase } from "../lib/api";
import { connect, loadAll, set } from "../lib/store";
import { useTheme } from "../lib/theme";
import { Button } from "../components/ui";

export default function Login() {
  const t = useTheme();
  const [server, setServer] = useState(getBase());
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const input = { height: 48, borderRadius: 16, borderWidth: 2, borderColor: t.line, backgroundColor: t.surface, color: t.ink, paddingHorizontal: 14, fontSize: 15 };

  async function submit() {
    setBusy(true);
    setError("");
    try {
      await setBase(server.trim());
      const me = await api.login(email.trim(), password);
      set(() => ({ me }));
      await loadAll();
      connect();
      router.replace("/");
    } catch (e) {
      setError((e as Error).message === "Network request failed" ? "Can't reach that server." : (e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: t.bg }}>
      <KeyboardAvoidingView behavior={Platform.OS === "ios" ? "padding" : undefined} style={{ flex: 1, justifyContent: "center", padding: 24, gap: 12 }}>
        <View style={{ flexDirection: "row", justifyContent: "center", alignItems: "flex-end", gap: 10, marginBottom: 12 }}>
          <Gbot seed="a" look={{ shape: "triangle", hue: "pink", eyes: "up" }} size={56} />
          <Gbot seed="b" look={{ shape: "circle", hue: "blue", eyes: "up" }} size={84} mood={busy ? "thinking" : "idle"} />
          <Gbot seed="c" look={{ shape: "hexagon", hue: "purple", eyes: "side" }} size={56} />
        </View>
        <Text style={{ color: t.ink, fontSize: 30, fontWeight: "800", textAlign: "center", letterSpacing: -0.4 }}>Welcome back</Text>
        <Text style={{ color: t.ink2, fontSize: 15, textAlign: "center", marginBottom: 8 }}>Sign in to the gbot server you run.</Text>
        <TextInput style={input} value={server} onChangeText={setServer} placeholder="http://192.168.1.10:8080" placeholderTextColor={t.ink2} autoCapitalize="none" autoCorrect={false} keyboardType="url" accessibilityLabel="Server address" />
        <TextInput style={input} value={email} onChangeText={setEmail} placeholder="Email" placeholderTextColor={t.ink2} autoCapitalize="none" keyboardType="email-address" textContentType="username" accessibilityLabel="Email" />
        <TextInput style={input} value={password} onChangeText={setPassword} placeholder="Password" placeholderTextColor={t.ink2} secureTextEntry autoCapitalize="none" autoCorrect={false} textContentType="password" accessibilityLabel="Password" onSubmitEditing={submit} />
        {!!error && <Text style={{ color: t.danger, fontSize: 14 }}>{error}</Text>}
        <Button onPress={submit} disabled={busy || !email || !password} style={{ height: 50, marginTop: 4 }}>Sign in</Button>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

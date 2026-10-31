import { useEffect, useRef, useState } from "react";
import { api, type VoiceInfo } from "./api";

// Voice stays local by default: dictation goes to the server's own
// speech-to-text server when one is set up, and reading aloud uses the
// operating system's voices. The browser's speech recognition is the
// fallback; some browsers send that audio to their vendor, so the UI says so.

let info: Promise<VoiceInfo> | null = null;
export const voiceInfo = () => (info ??= api.voice().catch(() => ({ transcribe: false, speak: false })));

type SR = { start(): void; stop(): void; abort(): void; onresult: ((e: { results: ArrayLike<ArrayLike<{ transcript: string }>> }) => void) | null; onend: (() => void) | null; onerror: (() => void) | null; interimResults: boolean; continuous: boolean; lang: string };
const browserSR = (): (new () => SR) | undefined =>
  (window as unknown as { SpeechRecognition?: new () => SR; webkitSpeechRecognition?: new () => SR }).SpeechRecognition ??
  (window as unknown as { webkitSpeechRecognition?: new () => SR }).webkitSpeechRecognition;

export function useDictation(onText: (t: string) => void) {
  const [state, setState] = useState<"idle" | "listening" | "working">("idle");
  const [mode, setMode] = useState<"server" | "browser" | "none">("none");
  const [level, setLevel] = useState(0);
  const rec = useRef<MediaRecorder | null>(null);
  const sr = useRef<SR | null>(null);
  const raf = useRef(0);

  useEffect(() => {
    voiceInfo().then((v) => setMode(v.transcribe ? "server" : browserSR() ? "browser" : "none"));
  }, []);

  async function start() {
    if (mode === "server") {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const ctx = new AudioContext();
      const an = ctx.createAnalyser();
      ctx.createMediaStreamSource(stream).connect(an);
      const buf = new Uint8Array(an.fftSize);
      const tick = () => {
        an.getByteTimeDomainData(buf);
        let peak = 0;
        for (const v of buf) peak = Math.max(peak, Math.abs(v - 128));
        setLevel(peak / 128);
        raf.current = requestAnimationFrame(tick);
      };
      tick();
      const chunks: Blob[] = [];
      const r = new MediaRecorder(stream);
      r.ondataavailable = (e) => chunks.push(e.data);
      r.onstop = async () => {
        cancelAnimationFrame(raf.current);
        stream.getTracks().forEach((t) => t.stop());
        ctx.close();
        setLevel(0);
        setState("working");
        try {
          const { text } = await api.transcribe(new Blob(chunks, { type: r.mimeType }));
          if (text) onText(text);
        } finally {
          setState("idle");
        }
      };
      r.start();
      rec.current = r;
      setState("listening");
    } else if (mode === "browser") {
      const Ctor = browserSR()!;
      const s = new Ctor();
      s.interimResults = false;
      s.continuous = false;
      s.lang = navigator.language;
      s.onresult = (e) => onText(Array.from(e.results).map((r) => r[0].transcript).join(" "));
      s.onend = () => setState("idle");
      s.onerror = () => setState("idle");
      s.start();
      sr.current = s;
      setState("listening");
    }
  }

  function stop() {
    rec.current?.stop();
    rec.current = null;
    sr.current?.stop();
    sr.current = null;
  }

  return { state, mode, level, start, stop, toggle: () => (state === "listening" ? stop() : state === "idle" ? start().catch(() => setState("idle")) : undefined) };
}

const plain = (md: string) =>
  md.replace(/```[\s\S]*?```/g, " (code) ").replace(/`([^`]+)`/g, "$1").replace(/[*_#>|]/g, "").replace(/\[([^\]]+)\]\([^)]+\)/g, "$1").trim();

let audio: HTMLAudioElement | null = null;

export async function speak(text: string, voice?: string) {
  stopSpeaking();
  const t = plain(text).slice(0, 4000);
  if (!t) return;
  const v = await voiceInfo();
  if (v.speak) {
    const blob = await api.speak(t, voice);
    audio = new Audio(URL.createObjectURL(blob));
    await audio.play();
    return;
  }
  const u = new SpeechSynthesisUtterance(t);
  const match = speechSynthesis.getVoices().find((x) => x.name === voice);
  if (match) u.voice = match;
  u.rate = 1.03;
  speechSynthesis.speak(u);
}

export function stopSpeaking() {
  audio?.pause();
  audio = null;
  if ("speechSynthesis" in window) speechSynthesis.cancel();
}

/** The operating system's voices, local ones first. */
export function useSystemVoices() {
  const [voices, setVoices] = useState<SpeechSynthesisVoice[]>([]);
  useEffect(() => {
    if (!("speechSynthesis" in window)) return;
    const load = () => setVoices(speechSynthesis.getVoices().filter((v) => v.lang.startsWith(navigator.language.slice(0, 2))).sort((a, b) => Number(b.localService) - Number(a.localService)));
    load();
    speechSynthesis.addEventListener("voiceschanged", load);
    return () => speechSynthesis.removeEventListener("voiceschanged", load);
  }, []);
  return voices;
}

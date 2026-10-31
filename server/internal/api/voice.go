package api

import (
	"bytes"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"strings"
	"time"
)

// VoiceConfig points at OpenAI-compatible speech servers. Local servers
// (faster-whisper, speaches, Kokoro) are the default; without one the
// browser's own speech features are used instead.
type VoiceConfig struct {
	STTURL, STTModel, STTKey, STTName string
	TTSURL, TTSModel, TTSKey, TTSName string
}

func VoiceFromEnv() VoiceConfig {
	v := VoiceConfig{
		STTURL: strings.TrimRight(os.Getenv("GBOT_STT_URL"), "/"), STTModel: os.Getenv("GBOT_STT_MODEL"),
		TTSURL: strings.TrimRight(os.Getenv("GBOT_TTS_URL"), "/"), TTSModel: os.Getenv("GBOT_TTS_MODEL"),
		STTName: "this machine", TTSName: "this machine",
	}
	if v.STTModel == "" {
		v.STTModel = "Systran/faster-whisper-small"
	}
	if v.TTSModel == "" {
		v.TTSModel = "kokoro"
	}
	// Cloud speech only when asked for explicitly.
	if v.STTURL == "openai" || v.TTSURL == "openai" {
		key := os.Getenv("OPENAI_API_KEY")
		if v.STTURL == "openai" {
			v.STTURL, v.STTModel, v.STTKey, v.STTName = "https://api.openai.com/v1", "whisper-1", key, "OpenAI"
		}
		if v.TTSURL == "openai" {
			v.TTSURL, v.TTSModel, v.TTSKey, v.TTSName = "https://api.openai.com/v1", "gpt-4o-mini-tts", key, "OpenAI"
		}
	}
	return v
}

var speechClient = &http.Client{Timeout: 90 * time.Second}

func (s *Server) voiceConfig(w http.ResponseWriter, r *http.Request) {
	out := map[string]any{"transcribe": s.Voice.STTURL != "", "speak": s.Voice.TTSURL != ""}
	if s.Voice.STTURL != "" {
		out["transcribeBy"] = s.Voice.STTName
	}
	if s.Voice.TTSURL != "" {
		out["speakBy"] = s.Voice.TTSName
	}
	writeJSON(w, 200, out)
}

func (s *Server) transcribe(w http.ResponseWriter, r *http.Request) {
	if s.Voice.STTURL == "" {
		writeErr(w, 501, "no speech-to-text server is configured")
		return
	}
	audio, err := io.ReadAll(r.Body)
	if err != nil || len(audio) == 0 {
		writeErr(w, 400, "no audio")
		return
	}
	ct := r.Header.Get("Content-Type")
	ext := "webm"
	switch {
	case strings.Contains(ct, "mp4"), strings.Contains(ct, "m4a"):
		ext = "m4a"
	case strings.Contains(ct, "wav"):
		ext = "wav"
	case strings.Contains(ct, "ogg"):
		ext = "ogg"
	}
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	fw, _ := mw.CreateFormFile("file", "speech."+ext)
	_, _ = fw.Write(audio)
	_ = mw.WriteField("model", s.Voice.STTModel)
	_ = mw.Close()
	req, _ := http.NewRequestWithContext(r.Context(), http.MethodPost, s.Voice.STTURL+"/audio/transcriptions", &buf)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	if s.Voice.STTKey != "" {
		req.Header.Set("Authorization", "Bearer "+s.Voice.STTKey)
	}
	resp, err := speechClient.Do(req)
	if err != nil {
		writeErr(w, 502, "the speech server didn't answer")
		return
	}
	defer resp.Body.Close()
	var out struct {
		Text string `json:"text"`
	}
	if resp.StatusCode != 200 || json.NewDecoder(resp.Body).Decode(&out) != nil {
		writeErr(w, 502, "the speech server couldn't transcribe that")
		return
	}
	writeJSON(w, 200, map[string]string{"text": strings.TrimSpace(out.Text)})
}

func (s *Server) speak(w http.ResponseWriter, r *http.Request) {
	if s.Voice.TTSURL == "" {
		writeErr(w, 501, "no text-to-speech server is configured")
		return
	}
	var body struct {
		Text  string `json:"text"`
		Voice string `json:"voice"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	if body.Text == "" || len(body.Text) > 4000 {
		writeErr(w, 400, "text must be 1 to 4000 characters")
		return
	}
	if body.Voice == "" {
		body.Voice = "af_heart"
	}
	raw, _ := json.Marshal(map[string]any{"model": s.Voice.TTSModel, "input": body.Text, "voice": body.Voice, "response_format": "mp3"})
	req, _ := http.NewRequestWithContext(r.Context(), http.MethodPost, s.Voice.TTSURL+"/audio/speech", bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	if s.Voice.TTSKey != "" {
		req.Header.Set("Authorization", "Bearer "+s.Voice.TTSKey)
	}
	resp, err := speechClient.Do(req)
	if err != nil || resp.StatusCode != 200 {
		writeErr(w, 502, "the speech server couldn't read that aloud")
		return
	}
	defer resp.Body.Close()
	w.Header().Set("Content-Type", "audio/mpeg")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = io.Copy(w, resp.Body)
}

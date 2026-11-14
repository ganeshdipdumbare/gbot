package runs

import "testing"

func TestModelForFindsProviderByPrefix(t *testing.T) {
	r := &Runtime{
		cfg: Config{},
		providers: map[string]providerState{
			"0d0e0f00-0000-4000-8000-000000000001": {
				srv:    providerServer{prefix: "mp12345678", kind: "openai", baseURL: "http://127.0.0.1:1/v1", key: "k"},
				loaded: true,
			},
		},
	}
	// The map is keyed by the database id; the model string carries the
	// provider prefix.
	if _, err := r.modelFor("mp12345678:deepseek/deepseek-v4.1-flash"); err != nil {
		t.Fatalf("modelFor by prefix: %v", err)
	}
}

func TestReasoningFor(t *testing.T) {
	r := &Runtime{providers: map[string]providerState{
		"a": {srv: providerServer{prefix: "mpcodex01", kind: "codex"}},
		"b": {srv: providerServer{prefix: "mplocal01", kind: "openai", baseURL: "http://127.0.0.1:1/v1"}},
		"c": {srv: providerServer{prefix: "mpopenai1", kind: "openai", baseURL: "https://api.openai.com/v1"}},
	}}
	cases := map[string]bool{
		"anthropic:claude-sonnet-5-5": true,
		"anthropic:claude-haiku-5-5":  false,
		"openai:gpt-4.1":              false,
		"gemini:gemini-2.5-pro":       true,
		"ollama:qwq":                  false,
		"deepseek:deepseek-r1":        false,
		"mpcodex01:gpt-5-codex":       true,
		"mplocal01:o3":                false,
		"mpopenai1:o3":                true,
		"mpunknown:o3":                false,
		"mock:any":                    false,
	}
	for model, want := range cases {
		if got := r.reasoningFor(model) != nil; got != want {
			t.Errorf("reasoningFor(%q) = %v, want %v", model, got, want)
		}
	}
}

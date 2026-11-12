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

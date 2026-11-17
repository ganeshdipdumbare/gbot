package runs

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
)

// SVG is a document that can carry script: a tool that sends one gets it
// named in the text, and nothing is kept to serve back.
func TestSVGIsNotKeptAsAPicture(t *testing.T) {
	if showableImage("image/svg+xml") {
		t.Fatal("SVG counted as a picture")
	}
	for _, mime := range []string{"image/png", "image/jpeg", "IMAGE/PNG", "image/webp;charset=binary"} {
		if !showableImage(mime) {
			t.Fatalf("%s should be shown", mime)
		}
	}
	r := &Runtime{}
	out := r.readContent(context.Background(), "Draw", []mcpContent{{
		Type: "image", Data: base64.StdEncoding.EncodeToString([]byte("<svg onload=alert(1)>")), MimeType: "image/svg+xml",
	}})
	if !strings.Contains(out, "SVG file") {
		t.Fatalf("out = %q", out)
	}
}

func TestReadContentParsesBlocks(t *testing.T) {
	raw := `[{"type":"text","text":"Here is the diagram."},{"type":"image","data":"` +
		base64.StdEncoding.EncodeToString([]byte("not-a-real-png")) + `","mimeType":"image/png"}]`
	var blocks []mcpContent
	if err := json.Unmarshal([]byte(raw), &blocks); err != nil {
		t.Fatal(err)
	}
	if len(blocks) != 2 || blocks[1].MimeType != "image/png" || blocks[1].Data == "" {
		t.Fatalf("blocks: %+v", blocks)
	}
	r := &Runtime{}
	out := r.readContent(context.Background(), "Draw", blocks)
	t.Logf("out = %q", out)
	if out == "" {
		t.Fatal("no text came back")
	}
}

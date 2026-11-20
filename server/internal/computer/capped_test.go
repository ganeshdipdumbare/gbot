package computer

import (
	"bytes"
	"context"
	"os/exec"
	"testing"
)

func TestCappedKeepsTheStartAndDrainsTheRest(t *testing.T) {
	var c capped
	chunk := bytes.Repeat([]byte("y\n"), 1<<20) // 2 MiB
	for i := 0; i < 20; i++ {
		if n, err := c.Write(chunk); n != len(chunk) || err != nil {
			t.Fatalf("write %d: n=%d err=%v; the command must never be blocked", i, n, err)
		}
	}
	if c.Len() != maxOutput {
		t.Fatalf("kept %d bytes, want %d", c.Len(), maxOutput)
	}
}

// The cap must hold for output copied the way exec copies it (io.Copy from
// a pipe), not only for direct writes.
func TestRunCapsCommandOutput(t *testing.T) {
	if _, err := exec.LookPath("sh"); err != nil {
		t.Skip("no sh")
	}
	out, _, _, err := run(context.Background(), "sh", nil, "-c", "head -c 40000000 /dev/zero")
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != maxOutput {
		t.Fatalf("kept %d bytes of command output, want %d", len(out), maxOutput)
	}
}

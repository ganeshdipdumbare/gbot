package computer

import (
	"bytes"
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

package computer

import (
	"bytes"
	"context"
	"errors"
	"testing"
	"time"
)

// pipeDriver runs a local shell command in place of the capture inside a computer.
type pipeDriver struct {
	dockerDriver
	script string
}

func (p pipeDriver) pipe(id, user string, env []string, args ...string) (string, []string) {
	return "sh", []string{"-c", p.script}
}

func streamManager(script string) (*Manager, Owner) {
	o := Owner{Bot: "b", User: "u"}
	m := &Manager{d: pipeDriver{script: script}, boxes: map[string]*box{o.Key(): {id: "x", controller: "bot"}}}
	return m, o
}

func TestScreenFramesReadsFramedJPEGs(t *testing.T) {
	m, o := streamManager(`printf 'GBF 3\nabcGBF 2\nde'; sleep 5`)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	frames, done, err := m.ScreenFrames(ctx, o)
	if err != nil {
		t.Fatal(err)
	}
	var got [][]byte
	deadline := time.After(3 * time.Second)
	for len(got) == 0 || !bytes.Equal(got[len(got)-1], []byte("de")) {
		select {
		case f := <-frames:
			got = append(got, f)
		case err := <-done:
			t.Fatalf("stream ended early: %v", err)
		case <-deadline:
			t.Fatalf("frames so far: %q", got)
		}
	}
	// The newest frame always arrives; an older one may be skipped for it.
	if first := got[0]; !bytes.Equal(first, []byte("abc")) && !bytes.Equal(first, []byte("de")) {
		t.Fatalf("first frame %q", first)
	}
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("cancelled stream should end cleanly, got %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("stream didn't stop after cancel")
	}
}

// An image built before the capture could stream prints a bare JPEG for
// --stream; the caller must hear that and fall back to one capture per frame.
func TestScreenFramesOldImage(t *testing.T) {
	m, o := streamManager(`printf '\377\330\377\340 jpeg bytes\n more'`)
	_, done, err := m.ScreenFrames(context.Background(), o)
	if err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if !errors.Is(err, ErrNoStream) {
			t.Fatalf("want ErrNoStream, got %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("no answer")
	}
}

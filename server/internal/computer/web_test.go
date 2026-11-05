package computer

import (
	"reflect"
	"strings"
	"testing"
)

func TestWebCommands(t *testing.T) {
	if got := typeArgs(2, "-h", true, true); !reflect.DeepEqual(got, []string{"type", "2", "--submit", "--accept", "--", "-h"}) {
		t.Errorf("typeArgs with submit: %q", got)
	}
	if got := typeArgs(3, "pw", false, false); !reflect.DeepEqual(got, []string{"type", "3", "--", "pw"}) {
		t.Errorf("typeArgs: %q", got)
	}
	if got := pressArgs("Enter", false); !reflect.DeepEqual(got, []string{"press", "--", "Enter"}) {
		t.Errorf("pressArgs: %q", got)
	}
	if got := pressArgs("Enter", true); !reflect.DeepEqual(got, []string{"press", "--accept", "--", "Enter"}) {
		t.Errorf("pressArgs accepting a dialog: %q", got)
	}
	if got := clickArgs(4, true); !reflect.DeepEqual(got, []string{"click", "4", "--accept"}) {
		t.Errorf("clickArgs: %q", got)
	}
	if got := scrollArgs("down", 0); !reflect.DeepEqual(got, []string{"scroll", "down"}) {
		t.Errorf("scrollArgs: %q", got)
	}
	if got := scrollArgs("top", 300); !reflect.DeepEqual(got, []string{"scroll", "top", "--amount", "300"}) {
		t.Errorf("scrollArgs with amount: %q", got)
	}
	if got := webCmd("snapshot"); !reflect.DeepEqual(got, []string{"/usr/local/bin/gbot-web", "snapshot"}) {
		t.Errorf("webCmd: %q", got)
	}
}

func TestWebResult(t *testing.T) {
	if out, err := webResult("  Clicked [5].\n", "", 0); err != nil || out != "Clicked [5]." {
		t.Errorf("success: %q %v", out, err)
	}
	if _, err := webResult("", "No element [9] on this page.\n", 1); err == nil || err.Error() != "No element [9] on this page." {
		t.Errorf("step failure: %v", err)
	}
	if _, err := webResult("", "", 1); err == nil || err.Error() != "the browser step failed" {
		t.Errorf("silent failure: %v", err)
	}
	// Exit 127: the image has no gbot-web, so the person must rebuild it.
	if _, err := webResult("", "", 127); err == nil || !strings.Contains(err.Error(), "make computer") {
		t.Errorf("old image: %v", err)
	}
}

package slack

import "testing"

func TestPromptNeutralizesBroadcastAndLinks(t *testing.T) {
	e := Event{Text: "<@U9> ping <!everyone> and <!subteam^S1|devs> see <https://a.example/x|the doc>"}
	want := "ping @everyone and @group see https://a.example/x"
	if got := e.Prompt(); got != want {
		t.Fatalf("Prompt() = %q, want %q", got, want)
	}
}

func TestSlackEscapeKeepsReplyLiteral(t *testing.T) {
	if got := slackEscape("<!channel> & <b>"); got != "&lt;!channel&gt; &amp; &lt;b&gt;" {
		t.Fatalf("slackEscape() = %q", got)
	}
}

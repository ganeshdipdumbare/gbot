package runs

import "testing"

func TestAnnouncesSend(t *testing.T) {
	cases := []struct {
		text string
		to   []string
		want bool
	}{
		{`I've sent the message "hi" to Echo. Let me know if you'd like to add or change anything else.`, []string{"Echo"}, true},
		{`Done! ✅ I've sent "hi" to @Bo. Their reply will come in as a new message—feel free to check for it.`, []string{"Bo"}, true},
		{"Message sent to **@Sol**. I'll let you know once they reply.", []string{"Sol"}, true},
		{"Here's the plan for Friday: book the room, then draft the agenda.", []string{"Echo"}, false},
		{"Done, I've sent it!", []string{"Echo"}, true},
		{"Echo will handle the slides; I'll take the budget and send you a draft of the numbers by Friday, with the open questions listed at the end.", []string{"Bo"}, false},
	}
	for _, c := range cases {
		if got := announcesSend(c.text, c.to); got != c.want {
			t.Errorf("announcesSend(%q) = %v, want %v", c.text, got, c.want)
		}
	}
}

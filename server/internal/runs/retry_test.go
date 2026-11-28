package runs

import (
	"context"
	"errors"
	"testing"

	"github.com/ganeshdipdumbare/kritrim"
)

func TestRetryModel(t *testing.T) {
	out := `{"error":{"code":"data_inspection_failed","message":"Output data may contain inappropriate content.","type":"data_inspection_failed"}}`
	in := `{"error":{"code":"data_inspection_failed","message":"Input data may contain inappropriate content.","type":"data_inspection_failed"}}`
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"flagged output", &kritrim.StatusError{Code: 400, Body: out}, true},
		{"flagged input", &kritrim.StatusError{Code: 400, Body: in}, false},
		{"bad request", &kritrim.StatusError{Code: 400, Body: "bad model"}, false},
		{"rate limit", &kritrim.StatusError{Code: 429}, true},
		{"server error", &kritrim.StatusError{Code: 503}, true},
		{"plain text output flag", errors.New("400 " + out), true},
		{"cancelled", context.Canceled, false},
	}
	for _, c := range cases {
		if got := retryModel(c.err); got != c.want {
			t.Errorf("%s: retry %v, want %v", c.name, got, c.want)
		}
	}
}

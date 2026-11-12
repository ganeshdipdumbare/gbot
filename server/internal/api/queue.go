package api

import (
	"net/http"

	"gbot/internal/store"
)

// The queue of one chat: what the bot is doing now and what waits behind
// it. A person can pause, resume or cancel any of it.

func (s *Server) botQueue(w http.ResponseWriter, r *http.Request) {
	b := s.bot(w, r)
	if b == nil {
		return
	}
	s.writeQueue(w, r, b.ID, userFrom(r).ID, "")
}

func (s *Server) groupQueue(w http.ResponseWriter, r *http.Request) {
	g := s.group(w, r)
	if g == nil {
		return
	}
	s.writeQueue(w, r, "", g.UserID, g.ID)
}

func (s *Server) writeQueue(w http.ResponseWriter, r *http.Request, botID, userID, groupID string) {
	items, err := s.Runtime.QueueSnapshot(r.Context(), botID, userID, groupID)
	if err != nil {
		writeErr(w, 500, "could not load the queue")
		return
	}
	if items == nil {
		items = []*store.QueueItem{}
	}
	writeJSON(w, 200, map[string]any{"items": items})
}

// queueItem loads an item and checks that the caller owns its chat.
func (s *Server) queueItem(w http.ResponseWriter, r *http.Request) *store.QueueItem {
	u := userFrom(r)
	q, err := s.Store.QueueItem(r.Context(), r.PathValue("qid"))
	if err != nil {
		writeErr(w, 404, "queued message not found")
		return nil
	}
	if q.GroupID != "" {
		if _, err := s.Store.GroupFor(r.Context(), u.SpaceID, u.ID, q.GroupID); err != nil {
			writeErr(w, 404, "queued message not found")
			return nil
		}
		return q
	}
	if q.ChatUser != u.ID {
		writeErr(w, 404, "queued message not found")
		return nil
	}
	if _, err := s.Store.GetBotFor(r.Context(), u.SpaceID, u.ID, q.BotID); err != nil {
		writeErr(w, 404, "queued message not found")
		return nil
	}
	return q
}

func (s *Server) queueAction(w http.ResponseWriter, r *http.Request) {
	q := s.queueItem(w, r)
	if q == nil {
		return
	}
	var body struct {
		Action string `json:"action"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	var err error
	switch body.Action {
	case "pause":
		err = s.Runtime.PauseQueueItem(r.Context(), q)
	case "resume":
		err = s.Runtime.ResumeQueueItem(r.Context(), q)
	case "cancel":
		err = s.Runtime.CancelQueueItem(r.Context(), q)
	default:
		writeErr(w, 400, "use pause, resume or cancel")
		return
	}
	if err != nil {
		writeErr(w, 409, err.Error())
		return
	}
	s.writeQueue(w, r, q.BotID, q.ChatUser, q.GroupID)
}

// clearQueue stops the current turn and drops everything waiting.
func (s *Server) clearBotQueue(w http.ResponseWriter, r *http.Request) {
	b := s.bot(w, r)
	if b == nil {
		return
	}
	u := userFrom(r)
	if err := s.Runtime.ClearQueue(r.Context(), u.SpaceID, b.ID, u.ID, ""); err != nil {
		writeErr(w, 500, "could not clear the queue")
		return
	}
	s.writeQueue(w, r, b.ID, u.ID, "")
}

func (s *Server) clearGroupQueue(w http.ResponseWriter, r *http.Request) {
	g := s.group(w, r)
	if g == nil {
		return
	}
	if err := s.Runtime.ClearQueue(r.Context(), g.SpaceID, "", g.UserID, g.ID); err != nil {
		writeErr(w, 500, "could not clear the queue")
		return
	}
	s.writeQueue(w, r, "", g.UserID, g.ID)
}

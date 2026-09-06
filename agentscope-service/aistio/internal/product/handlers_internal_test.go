// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package product

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
)

func TestPostSessionWakeEventRetriesLeaseReleaseRace(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/sessions/session-a/events" ||
			r.Header.Get("X-Builder-Internal-Token") != "internal-token" ||
			r.Header.Get("X-Builder-Internal-User") != "owner-a" {
			t.Errorf("unexpected wake request: path=%s headers=%v", r.URL.Path, r.Header)
		}
		var payload map[string]any
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Errorf("decode payload: %v", err)
		}
		if calls.Add(1) == 1 {
			w.WriteHeader(http.StatusConflict)
			return
		}
		w.WriteHeader(http.StatusAccepted)
	}))
	t.Cleanup(server.Close)

	s := &Server{cfg: Config{DataURL: server.URL, InternalToken: "internal-token"}}
	if err := s.PostSessionWakeEvent(t.Context(), "session-a", "owner-a", "hello"); err != nil {
		t.Fatal(err)
	}
	if got := calls.Load(); got != 2 {
		t.Fatalf("wake calls=%d, want 2", got)
	}
}

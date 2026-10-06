package product

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// ManagedMirrorState is the durable data-plane delivery barrier for one original Attempt.
type ManagedMirrorState struct {
	Pending           int64 `json:"pending"`
	LastEnqueuedSeq   int64 `json:"last_enqueued_seq"`
	LastDeliveredSeq  int64 `json:"last_delivered_seq"`
	ExecutionFinished bool  `json:"execution_finished"`
	Ready             bool  `json:"ready"`
}

// ManagedMirrorStatus must be queried before publishing a terminal Managed Job/Team result.
// A missing admission is ready: dispatch may have failed before reaching the data plane.
// Transport failures are not readiness and must keep the public projection pending.
func (s *Server) ManagedMirrorStatus(ctx context.Context, owner, sessionID, attemptID string) (ManagedMirrorState, bool, error) {
	var state ManagedMirrorState
	if strings.TrimSpace(s.cfg.DataURL) == "" || sessionID == "" || attemptID == "" {
		return state, false, fmt.Errorf("managed mirror status requires data URL, session, and attempt")
	}
	endpoint := strings.TrimRight(s.cfg.DataURL, "/") + "/api/internal/sessions/" + url.PathEscape(sessionID) +
		"/event-mirror-status?attempt_id=" + url.QueryEscape(attemptID)
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return state, false, err
	}
	request.Header.Set("X-Builder-Internal-Token", s.cfg.InternalToken)
	if owner != "" {
		request.Header.Set("X-Builder-Internal-User", owner)
	}
	response, err := (&http.Client{Timeout: 15 * time.Second}).Do(request)
	if err != nil {
		return state, false, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return state, false, fmt.Errorf("managed mirror status: HTTP %d", response.StatusCode)
	}
	if err := json.NewDecoder(response.Body).Decode(&state); err != nil {
		return state, false, err
	}
	return state, state.Ready, nil
}

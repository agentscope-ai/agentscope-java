package product

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// ManagedServiceRequest is used only after the Endpoint owner/Invocation checks.
// Callers supply fixed resource suffixes, never arbitrary upstream URLs.
func (s *Server) ManagedServiceRequest(ctx context.Context, owner, session, method, suffix, key string, body any) (json.RawMessage, error) {
	raw, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	endpoint := strings.TrimRight(s.cfg.DataURL, "/") + "/api/v1/agent-sessions/" + url.PathEscape(session) + suffix
	req, err := http.NewRequestWithContext(ctx, method, endpoint, bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Builder-Internal-Token", s.cfg.InternalToken)
	req.Header.Set("X-Builder-Internal-User", owner)
	if key != "" {
		req.Header.Set("Idempotency-Key", key)
	}
	response, err := (&http.Client{Timeout: 15 * time.Second}).Do(req)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	payload, err := io.ReadAll(io.LimitReader(response.Body, 32<<20))
	if err != nil {
		return nil, err
	}
	if response.StatusCode >= 300 {
		return nil, fmt.Errorf("managed service API HTTP %d: %s", response.StatusCode, string(payload))
	}
	return payload, nil
}

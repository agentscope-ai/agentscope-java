package httpapi

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// SessionWithSnapshot is a runtime session plus its latest Level-1 snapshot.
type SessionWithSnapshot struct {
	*store.Session
	Snapshot *store.SessionSnapshot `json:"snapshot,omitempty"`
}

// listSessions handles GET /api/v1/sessions. Each row includes the latest
// Level-1 snapshot so the console can render context pressure without a
// second round-trip.
func (s *Server) listSessions(c *gin.Context) {
	filter := store.SessionFilter{
		AgentName: c.Query("agent"),
		Namespace: c.Query("namespace"),
		Phase:     c.Query("phase"),
		Framework: c.Query("framework"),
		TeamID:    c.Query("team"),
		Limit:     parseLimit(c, 100),
		Offset:    parseOffset(c),
	}

	sessions, err := s.store.Sessions().List(c.Request.Context(), filter)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	ids := make([]uuid.UUID, 0, len(sessions))
	for _, sess := range sessions {
		ids = append(ids, sess.ID)
	}
	snaps, _ := s.store.Metrics().LatestSnapshots(c.Request.Context(), ids)

	out := make([]SessionWithSnapshot, 0, len(sessions))
	for _, sess := range sessions {
		item := SessionWithSnapshot{Session: sess}
		if snaps != nil {
			item.Snapshot = snaps[sess.ID]
		}
		out = append(out, item)
	}
	c.JSON(http.StatusOK, gin.H{"sessions": out})
}

// getSession returns a single session with its latest snapshot attached.
func (s *Server) getSession(c *gin.Context) {
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}
	item := SessionWithSnapshot{Session: sess}
	if snap, err := s.store.Metrics().LatestSnapshot(c.Request.Context(), sess.ID); err == nil {
		item.Snapshot = snap
	}
	c.JSON(http.StatusOK, item)
}

// queryTokenMetrics handles GET /api/v1/metrics/tokens.
func (s *Server) queryTokenMetrics(c *gin.Context) {
	filter := store.TokenFilter{
		AgentName: c.Query("agent"),
		Namespace: c.Query("namespace"),
		Model:     c.Query("model"),
		Limit:     parseLimit(c, 500),
	}
	if since := c.Query("since"); since != "" {
		t, err := time.Parse(time.RFC3339, since)
		if err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid since (RFC3339)"})
			return
		}
		filter.Since = &t
	}
	if until := c.Query("until"); until != "" {
		t, err := time.Parse(time.RFC3339, until)
		if err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid until (RFC3339)"})
			return
		}
		filter.Until = &t
	}
	rows, err := s.store.Metrics().QueryTokenUsage(c.Request.Context(), filter)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	if rows == nil {
		rows = []*store.TokenUsageMetric{}
	}
	c.JSON(http.StatusOK, gin.H{"metrics": rows})
}

// fleetOverview handles GET /api/v1/overview.
func (s *Server) fleetOverview(c *gin.Context) {
	ctx := c.Request.Context()
	sessions, err := s.store.Sessions().List(ctx, store.SessionFilter{Limit: 5000})
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	agents := map[string]struct{}{}
	instances := map[string]struct{}{}
	var active int
	ids := make([]uuid.UUID, 0, len(sessions))
	for _, sess := range sessions {
		agents[sess.Namespace+"/"+sess.AgentName] = struct{}{}
		if sess.InstanceRef != "" {
			instances[sess.InstanceRef] = struct{}{}
		}
		if sess.Phase == "active" || sess.Phase == "Active" || sess.Phase == "idle" || sess.Phase == "Idle" {
			active++
		}
		ids = append(ids, sess.ID)
	}

	snaps, _ := s.store.Metrics().LatestSnapshots(ctx, ids)
	var pressureSum float64
	var pressureN int
	for _, snap := range snaps {
		if snap == nil {
			continue
		}
		pressureSum += snap.ContextPressure
		pressureN++
	}
	avgPressure := 0.0
	if pressureN > 0 {
		avgPressure = pressureSum / float64(pressureN)
	}

	since := time.Now().UTC().Add(-24 * time.Hour)
	tokens, _ := s.store.Metrics().QueryTokenUsage(ctx, store.TokenFilter{Since: &since, Limit: 10000})
	var tokenTotal int64
	for _, t := range tokens {
		tokenTotal += t.TotalTokens
	}

	dataplaneCount := 0
	if s.registry != nil {
		dataplaneCount = len(s.registry.List())
	}

	c.JSON(http.StatusOK, gin.H{
		"agentCount":           len(agents),
		"instanceCount":        max(len(instances), dataplaneCount),
		"dataplaneCount":       dataplaneCount,
		"sessionCount":         len(sessions),
		"activeSessionCount":   active,
		"avgContextPressure":   avgPressure,
		"tokenUsage24h":        tokenTotal,
	})
}

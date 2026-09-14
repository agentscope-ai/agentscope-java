// Copyright 2024-2026 the original author or authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package httpapi

import (
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// SessionWithSnapshot is a runtime session plus its latest Level-1 snapshot.
// Also carries resolved instance capability metadata when available.
type SessionWithSnapshot struct {
	Runtime *SessionRuntimeSummary `json:"runtime,omitempty"`
	*store.Session
	Snapshot        *store.SessionSnapshot `json:"snapshot,omitempty"`
	InstanceHealthy *bool                  `json:"instanceHealthy,omitempty"`
	InstanceBaseURL string                 `json:"instanceBaseUrl,omitempty"`
	Capabilities    []string               `json:"capabilities,omitempty"`
	ContractLevel   int32                  `json:"contractLevel,omitempty"`
	Model           string                 `json:"model,omitempty"`
}

// listSessions handles GET /api/v1/sessions. Each row includes the latest
// Level-1 snapshot so the console can render context pressure without a
// second round-trip.
func (s *Server) listSessions(c *gin.Context) {
	agentID, ok := optionalUUIDQuery(c, "agentId")
	if !ok {
		return
	}
	filter := store.SessionFilter{
		Tenant:    c.DefaultQuery("tenant", "default"),
		AgentID:   agentID,
		AgentName: c.Query("agent"),
		Namespace: c.Query("namespace"),
		Phase:     c.Query("phase"),
		Framework: c.Query("framework"),
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

// getSession returns a single session with its latest snapshot and resolved
// data-plane instance capabilities (so the console does not client-side join).
func (s *Server) getSession(c *gin.Context) {
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}
	item := SessionWithSnapshot{Session: sess}
	if snap, err := s.store.Metrics().LatestSnapshot(c.Request.Context(), sess.ID); err == nil {
		item.Snapshot = snap
	}
	s.enrichSessionRuntime(c.Request.Context(), sess, &item)
	if item.Runtime.Kind == model.DataPlaneManaged || (item.Runtime.Kind == "" && sess.Framework == "managed") {
		s.enrichSessionInstance(sess, &item)
		s.enrichSessionModel(c, sess, &item)
	}
	c.JSON(http.StatusOK, item)
}

// enrichSessionModel fills Model from a live /state probe when the DP reports it.
func (s *Server) enrichSessionModel(c *gin.Context, sess *store.Session, item *SessionWithSnapshot) {
	if item.Model != "" || s.prober == nil || s.registry == nil || sess == nil || sess.InstanceRef == "" {
		return
	}
	dp := s.registry.Get(sess.InstanceRef)
	if dp == nil || dp.BaseURL == "" {
		return
	}
	state, err := s.prober.FetchSessionState(c.Request.Context(), dp.BaseURL, sess.SessionID)
	if err != nil || state == nil {
		return
	}
	item.Model = state.Model
	if state.Busy != nil {
		item.Busy = state.Busy
	}
}

// enrichSessionInstance fills instanceHealthy / capabilities / contractLevel
// from the dataplane registry using sess.InstanceRef.
func (s *Server) enrichSessionInstance(sess *store.Session, item *SessionWithSnapshot) {
	if s.registry == nil || sess == nil {
		return
	}
	if sess.InstanceRef != "" {
		if dp := s.registry.Get(sess.InstanceRef); dp != nil {
			h := dp.Healthy
			item.InstanceHealthy = &h
			item.InstanceBaseURL = dp.BaseURL
			item.Capabilities = append([]string(nil), dp.Capabilities...)
			item.ContractLevel = dp.ContractLevel
			return
		}
		// Affinity miss: mark unhealthy but still copy capabilities from a
		// healthy peer so the console can gate on message-query / context-query
		// (resolveSessionEndpoint already falls back the same way).
		h := false
		item.InstanceHealthy = &h
	}
	for _, dp := range s.registry.ListByAgent(sess.Tenant, sess.AgentName, sess.Namespace) {
		if !dp.Healthy {
			continue
		}
		// A healthy peer is the endpoint that resolveSessionEndpoint can actually use,
		// so it supersedes a stale affinity miss above.
		h := true
		item.InstanceHealthy = &h
		if item.InstanceBaseURL == "" {
			item.InstanceBaseURL = dp.BaseURL
		}
		item.Capabilities = append([]string(nil), dp.Capabilities...)
		item.ContractLevel = dp.ContractLevel
		return
	}
}

// queryTokenMetrics handles GET /api/v1/metrics/tokens.
func (s *Server) queryTokenMetrics(c *gin.Context) {
	agentID, ok := optionalUUIDQuery(c, "agentId")
	if !ok {
		return
	}
	filter := store.TokenFilter{
		Tenant:    c.DefaultQuery("tenant", "default"),
		AgentID:   agentID,
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
	if a := accessFrom(c); a != nil {
		filtered := rows[:0]
		for _, row := range rows {
			if row.SessionFK == nil {
				continue
			}
			session, e := s.store.Sessions().GetByID(c.Request.Context(), *row.SessionFK)
			if e == nil && s.canAccessSession(c.Request.Context(), a, session, false) {
				filtered = append(filtered, row)
			}
		}
		rows = filtered
	}
	c.JSON(http.StatusOK, gin.H{"metrics": rows})
}

// queryAgentMetrics handles GET /api/v1/metrics/agents.
func (s *Server) queryAgentMetrics(c *gin.Context) {
	agentID, ok := optionalUUIDQuery(c, "agentId")
	if !ok {
		return
	}
	filter := store.AgentMetricFilter{
		Tenant:    c.DefaultQuery("tenant", "default"),
		AgentID:   agentID,
		AgentName: c.Query("agent"),
		Namespace: c.Query("namespace"),
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
	rows, err := s.store.Metrics().QueryAgentMetrics(c.Request.Context(), filter)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	if rows == nil {
		rows = []*store.AgentMetric{}
	}
	c.JSON(http.StatusOK, gin.H{"metrics": rows})
}

func optionalUUIDQuery(c *gin.Context, name string) (uuid.UUID, bool) {
	raw := strings.TrimSpace(c.Query(name))
	if raw == "" {
		return uuid.Nil, true
	}
	id, err := uuid.Parse(raw)
	if err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid " + name})
		return uuid.Nil, false
	}
	return id, true
}

type overviewCacheEntry struct {
	at   time.Time
	body gin.H
}

var (
	overviewCacheMu sync.Mutex
	overviewCache   = map[string]*overviewCacheEntry{}
)

const overviewCacheTTL = 5 * time.Second

func invalidateOverviewCache() {
	overviewCacheMu.Lock()
	overviewCache = map[string]*overviewCacheEntry{}
	overviewCacheMu.Unlock()
}

// fleetOverview handles GET /api/v1/overview using store aggregations.
func (s *Server) fleetOverview(c *gin.Context) {
	if a := accessFrom(c); a != nil {
		s.namespaceOverview(c, a)
		return
	}
	tenant := c.DefaultQuery("tenant", "default")
	overviewCacheMu.Lock()
	if cached := overviewCache[tenant]; cached != nil && time.Since(cached.at) < overviewCacheTTL {
		body := cached.body
		overviewCacheMu.Unlock()
		c.JSON(http.StatusOK, body)
		return
	}
	overviewCacheMu.Unlock()

	ctx := c.Request.Context()
	byPhase, err := s.store.Sessions().CountByPhase(ctx, store.SessionFilter{Tenant: tenant})
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	normalizePhase := func(m map[string]int) map[string]int {
		out := map[string]int{
			"active": 0, "idle": 0, "compressing": 0, "archived": 0, "terminated": 0,
		}
		total := 0
		for k, v := range m {
			lk := strings.ToLower(k)
			out[lk] += v
			total += v
		}
		out["_total"] = total
		return out
	}
	phases := normalizePhase(byPhase)
	sessionCount := phases["_total"]
	delete(phases, "_total")
	activeOnly := phases["active"]

	since24h := time.Now().UTC().Add(-24 * time.Hour)
	since5m := time.Now().UTC().Add(-5 * time.Minute)
	tokenTotal, _ := s.store.Metrics().SumTokenUsage(ctx, store.TokenFilter{Tenant: tenant, Since: &since24h})
	errorCount, _ := s.store.Metrics().SumErrorCount(ctx, store.AgentMetricFilter{Tenant: tenant, Since: &since24h})
	topAgents, _ := s.store.Metrics().TopAgents(ctx, tenant, since24h, 10)
	if topAgents == nil {
		topAgents = []store.AgentUsage{}
	}
	topSessionsByTokens, _ := s.store.Metrics().TopSessionsByTokens(ctx, tenant, since24h, 10)
	if topSessionsByTokens == nil {
		topSessionsByTokens = []store.SessionUsage{}
	}
	topSessionsByDuration, _ := s.store.Metrics().TopSessionsByDuration(ctx, tenant, since24h, 10)
	if topSessionsByDuration == nil {
		topSessionsByDuration = []store.SessionDuration{}
	}
	topAgentsByActive, _ := s.store.Metrics().TopAgentsByActiveSessions(ctx, tenant, since5m, 10)
	if topAgentsByActive == nil {
		topAgentsByActive = []store.AgentUsage{}
	}

	sessions, _ := s.store.Sessions().List(ctx, store.SessionFilter{Tenant: tenant, Limit: 5000})

	liveAgents, offlineAgents, registryKeys, _ := registryAgentBuckets(s.registry, tenant)
	historicalAgents := historicalAgentKeys(sessions, registryKeys)

	dataplaneCount := 0
	healthyCount := 0
	staleCount := 0
	stalePlanes := []gin.H{}
	if s.registry != nil {
		planes := s.registry.List()
		for _, dp := range planes {
			if dp.Tenant != tenant {
				continue
			}
			dataplaneCount++
			ns := dp.Namespace
			if ns == "" {
				ns = "default"
			}
			if dp.Healthy {
				healthyCount++
			} else {
				staleCount++
				stalePlanes = append(stalePlanes, gin.H{
					"instanceId": dp.InstanceID,
					"agentName":  dp.AgentName,
					"namespace":  ns,
					"lastSeenAt": dp.LastSeenAt,
				})
			}
		}
	}

	// Orphan sessions: have instanceRef but registry entry missing/unhealthy.
	orphanSessions := []gin.H{}
	if s.registry != nil {
		for _, sess := range sessions {
			if sess.InstanceRef == "" {
				continue
			}
			ph := strings.ToLower(sess.Phase)
			if ph == "terminated" || ph == "archived" {
				continue
			}
			dp := s.registry.Get(sess.InstanceRef)
			if dp == nil || !dp.Healthy {
				orphanSessions = append(orphanSessions, gin.H{
					"id":          sess.ID.String(),
					"sessionId":   sess.SessionID,
					"agentName":   sess.AgentName,
					"namespace":   sess.Namespace,
					"instanceRef": sess.InstanceRef,
				})
				if len(orphanSessions) >= 8 {
					break
				}
			}
		}
	}

	body := gin.H{
		"agentCount":            len(liveAgents),
		"offlineAgentCount":     len(offlineAgents),
		"historicalAgentCount":  len(historicalAgents),
		"instanceCount":         dataplaneCount,
		"healthyInstanceCount":  healthyCount,
		"staleInstanceCount":    staleCount,
		"dataplaneCount":        dataplaneCount,
		"sessionCount":          sessionCount,
		"activeSessionCount":    activeOnly,
		"sessionsByPhase":       phases,
		"tokenUsage24h":         tokenTotal,
		"errorCount24h":         errorCount,
		"topAgents":             topAgents,
		"topSessionsByTokens":   topSessionsByTokens,
		"topSessionsByDuration": topSessionsByDuration,
		"topAgentsByActive":     topAgentsByActive,
		"staleDataplanes":       stalePlanes,
		"orphanSessions":        orphanSessions,
	}

	overviewCacheMu.Lock()
	overviewCache[tenant] = &overviewCacheEntry{at: time.Now(), body: body}
	overviewCacheMu.Unlock()

	c.JSON(http.StatusOK, body)
}

// overviewTimeseries handles GET /api/v1/overview/timeseries.
func (s *Server) overviewTimeseries(c *gin.Context) {
	metric := c.DefaultQuery("metric", "tokens")
	bucketStr := c.DefaultQuery("bucket", "1h")
	var bucket time.Duration
	switch bucketStr {
	case "1m", "minute":
		bucket = time.Minute
	case "1d", "day":
		bucket = 24 * time.Hour
	default:
		bucket = time.Hour
	}
	since := time.Now().UTC().Add(-24 * time.Hour)
	if v := c.Query("since"); v != "" {
		t, err := time.Parse(time.RFC3339, v)
		if err != nil {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid since (RFC3339)"})
			return
		}
		since = t
	}
	switch metric {
	case "tokens":
		rows, err := s.store.Metrics().AggregateTokens(c.Request.Context(), store.TokenFilter{
			Tenant:    c.DefaultQuery("tenant", "default"),
			AgentName: c.Query("agent"),
			Namespace: c.Query("namespace"),
			Since:     &since,
		}, bucket)
		if err != nil {
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
			return
		}
		if rows == nil {
			rows = []store.TokenBucket{}
		}
		c.JSON(http.StatusOK, gin.H{"metric": "tokens", "bucket": bucketStr, "points": rows})
	case "active_sessions":
		// Approximate from agent_metrics time series.
		rows, err := s.store.Metrics().QueryAgentMetrics(c.Request.Context(), store.AgentMetricFilter{
			Tenant:    c.DefaultQuery("tenant", "default"),
			AgentName: c.Query("agent"),
			Namespace: c.Query("namespace"),
			Since:     &since,
			Limit:     2000,
		})
		if err != nil {
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
			return
		}
		type point struct {
			BucketStart    time.Time `json:"bucketStart"`
			ActiveSessions int32     `json:"activeSessions"`
		}
		points := make([]point, 0, len(rows))
		for i := len(rows) - 1; i >= 0; i-- {
			m := rows[i]
			points = append(points, point{BucketStart: m.RecordedAt, ActiveSessions: m.ActiveSessions})
		}
		c.JSON(http.StatusOK, gin.H{"metric": "active_sessions", "bucket": bucketStr, "points": points})
	default:
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "metric must be tokens or active_sessions"})
	}
}

// Console summaries never reuse tenant-wide caches or include another caller's
// session identifiers. Infrastructure counters remain namespace scoped.
func (s *Server) namespaceOverview(c *gin.Context, a *namespaceAccess) {
	ctx := c.Request.Context()
	phases := map[string]int{}
	total := 0
	for offset := 0; ; offset += 500 {
		sessions, err := s.store.Sessions().List(ctx, store.SessionFilter{Tenant: a.Namespace.Tenant, Namespace: a.Namespace.Name, Limit: 500, Offset: offset})
		if err != nil {
			c.JSON(500, ErrorResponse{Error: "cannot load namespace overview"})
			return
		}
		for _, session := range sessions {
			phases[strings.ToLower(session.Phase)]++
			total++
		}
		if len(sessions) < 500 {
			break
		}
	}
	live, offline := map[string]bool{}, map[string]bool{}
	instances, healthy := 0, 0
	if s.registry != nil {
		for _, dp := range s.registry.List() {
			if dp.Tenant != a.Namespace.Tenant || dp.Namespace != a.Namespace.Name {
				continue
			}
			instances++
			if dp.Healthy {
				healthy++
				live[dp.AgentName] = true
			} else {
				offline[dp.AgentName] = true
			}
		}
	}
	for name := range live {
		delete(offline, name)
	}
	c.JSON(200, gin.H{"agentCount": len(live), "offlineAgentCount": len(offline), "historicalAgentCount": 0, "instanceCount": instances, "healthyInstanceCount": healthy, "staleInstanceCount": instances - healthy, "dataplaneCount": instances, "sessionCount": total, "activeSessionCount": phases["active"], "sessionsByPhase": phases, "tokenUsage24h": 0, "errorCount24h": 0, "topAgents": []any{}, "topSessionsByTokens": []any{}, "topSessionsByDuration": []any{}, "topAgentsByActive": []any{}, "staleDataplanes": []any{}, "orphanSessions": []any{}, "usageUnavailable": true})
}

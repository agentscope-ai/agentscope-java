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
	"context"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/types"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
	"github.com/spring-ai-alibaba/aistio/internal/endpoints"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
	"github.com/spring-ai-alibaba/aistio/internal/sessionops"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// resolveSession resolves the :sessionId path parameter to a store Session.
// If sessionId parses as a UUID, it is looked up by primary key. Otherwise it
// is treated as the framework-reported session ID, which requires an `agent`
// query parameter (and optional `namespace`, defaulting to defaultNamespace)
// to disambiguate. On failure, it writes the appropriate error response and
// returns ok=false.
func (s *Server) resolveSession(c *gin.Context) (sess *store.Session, ok bool) {
	sessionIDParam := c.Param("sessionId")
	ctx := c.Request.Context()

	var err error
	if id, parseErr := uuid.Parse(sessionIDParam); parseErr == nil {
		sess, err = s.store.Sessions().GetByID(ctx, id)
	} else {
		agentName := c.Query("agent")
		if agentName == "" {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "agent query parameter is required to resolve a non-UUID sessionId"})
			return nil, false
		}
		namespace := c.DefaultQuery("namespace", defaultNamespace)
		sess, err = s.store.Sessions().Get(ctx, agentName, namespace, sessionIDParam)
	}

	if err != nil {
		if err == store.ErrNotFound {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "session not found"})
		} else {
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		}
		return nil, false
	}
	return sess, true
}

// getSessionContext handles GET /api/v1/sessions/:sessionId/context, returning
// the latest Level-4 full context snapshot for the session. When no snapshot
// has been stored yet, it falls back to fetching the effective context live
// from the data plane (context-query capability) and writes it through.
func (s *Server) getSessionContext(c *gin.Context) {
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}

	snap, err := s.store.ContextSnapshots().Latest(c.Request.Context(), sess.ID)
	if err == nil {
		c.JSON(http.StatusOK, snap)
		return
	}
	if err != store.ErrNotFound {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	// Live fallback: pull the effective context from the data plane.
	agent, ok := s.resolveSessionAgent(c, sess)
	if !ok {
		return
	}
	if !agent.Status.DataPlaneInfo.HasCapability(v1alpha1.CapabilityContextQuery) {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "no context snapshot recorded for this session"})
		return
	}
	endpoint, ok := s.resolveSessionEndpoint(c, sess)
	if !ok {
		return
	}
	probed, err := s.prober.FetchContext(c.Request.Context(), endpoint, sess.SessionID)
	if err != nil {
		if err == prober.ErrNotFoundOnDataPlane {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "session not found on data plane"})
		} else {
			c.JSON(http.StatusBadGateway, ErrorResponse{Error: "failed to fetch context from data plane: " + err.Error()})
		}
		return
	}
	row, err := probed.ToStoreContext(sess.ID, sess.Framework)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	// Write-through; PutIfChanged deduplicates by context_hash.
	_, _ = s.store.ContextSnapshots().PutIfChanged(c.Request.Context(), row)
	c.JSON(http.StatusOK, row)
}

// getSessionMessages handles GET /api/v1/sessions/:sessionId/messages.
// Prefers a control-plane transcript reader when available; on miss, falls
// back to live data-plane FetchMessages gated on the message-query capability.
// Query: offset, limit, fromEnd (when true and offset omitted/0, return the
// newest page so long sessions open on the tail).
func (s *Server) getSessionMessages(c *gin.Context) {
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}
	offset := parseOffset(c)
	limit := parseLimit(c, 100)
	fromEnd := parseTruthyQuery(c.Query("fromEnd"))

	if s.transcriptMessages != nil {
		page, hit, err := s.transcriptMessages(c.Request.Context(), sess.AgentName, sess.Namespace, sess.SessionID, offset, limit, fromEnd)
		if err != nil {
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: "failed to read transcript: " + err.Error()})
			return
		}
		if hit {
			c.JSON(http.StatusOK, page)
			return
		}
	}

	// Live DP fallback — message-query capability applies only here.
	agent, ok := s.resolveSessionAgent(c, sess)
	if !ok {
		return
	}
	if !agent.Status.DataPlaneInfo.HasCapability(v1alpha1.CapabilityMessageQuery) {
		c.JSON(http.StatusNotImplemented, ErrorResponse{Error: "data plane does not advertise the message-query capability"})
		return
	}
	endpoint, ok := s.resolveSessionEndpoint(c, sess)
	if !ok {
		return
	}
	page, err := s.fetchMessagesPage(c.Request.Context(), endpoint, sess.SessionID, offset, limit, fromEnd)
	if err != nil {
		if err == prober.ErrNotFoundOnDataPlane {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "session not found on data plane"})
		} else {
			c.JSON(http.StatusBadGateway, ErrorResponse{Error: "failed to fetch messages from data plane: " + err.Error()})
		}
		return
	}
	if page != nil && page.Source == "" {
		page.Source = "dataplane"
	}
	c.JSON(http.StatusOK, page)
}

func (s *Server) fetchMessagesPage(ctx context.Context, endpoint, sessionID string, offset, limit int, fromEnd bool) (*prober.MessagePage, error) {
	if !fromEnd || offset > 0 {
		page, err := s.prober.FetchMessages(ctx, endpoint, sessionID, offset, limit)
		if err != nil {
			return nil, err
		}
		if page != nil {
			page.Source = "dataplane"
		}
		return page, nil
	}
	page, err := s.prober.FetchMessages(ctx, endpoint, sessionID, 0, limit)
	if err != nil {
		return nil, err
	}
	if page == nil {
		return nil, nil
	}
	page.Source = "dataplane"
	if page.Total > limit {
		start := page.Total - limit
		if start < 0 {
			start = 0
		}
		tail, err := s.prober.FetchMessages(ctx, endpoint, sessionID, start, limit)
		if err != nil {
			return nil, err
		}
		if tail != nil {
			tail.Source = "dataplane"
		}
		return tail, nil
	}
	return page, nil
}

func parseTruthyQuery(v string) bool {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "1", "true", "yes", "on":
		return true
	default:
		return false
	}
}

// getSessionEvents handles GET /api/v1/sessions/:sessionId/events, returning
// the Level-2 event stream for the session with optional filters.
// Supports reverse paging via before (RFC3339 timestamp or integer seq) + limit.
func (s *Server) getSessionEvents(c *gin.Context) {
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}

	var opts []store.EventOption
	if eventType := c.Query("eventType"); eventType != "" {
		opts = append(opts, store.WithEventType(eventType))
	}
	if since := c.Query("since"); since != "" {
		if t, err := time.Parse(time.RFC3339, since); err == nil {
			opts = append(opts, store.WithEventSince(t))
		}
	}
	if until := c.Query("until"); until != "" {
		if t, err := time.Parse(time.RFC3339, until); err == nil {
			opts = append(opts, store.WithEventUntil(t))
		}
	}
	beforeSet := false
	if before := c.Query("before"); before != "" {
		beforeSet = true
		if t, err := time.Parse(time.RFC3339, before); err == nil {
			opts = append(opts, store.WithEventBefore(t))
		} else if seq, err := strconv.Atoi(before); err == nil {
			opts = append(opts, store.WithEventBeforeSeq(seq))
		} else {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid before (RFC3339 timestamp or integer seq)"})
			return
		}
	}
	opts = append(opts, store.WithEventLimit(parseLimit(c, 100)))
	if offset := parseOffset(c); offset > 0 {
		opts = append(opts, store.WithEventOffset(offset))
	} else if !beforeSet {
		// First page of reverse paging: newest N events in chronological order.
		opts = append(opts, store.WithEventNewestFirst())
	}

	events, err := s.store.Events().List(c.Request.Context(), sess.ID, opts...)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	if events == nil {
		events = []*store.SessionEvent{}
	}
	c.JSON(http.StatusOK, gin.H{"events": events})
}

// listSessionTurns handles GET /api/v1/sessions/:sessionId/turns.
func (s *Server) listSessionTurns(c *gin.Context) {
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}
	turns, err := s.store.Turns().List(c.Request.Context(), sess.ID, parseLimit(c, 100))
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	if turns == nil {
		turns = []*store.SessionTurn{}
	}
	c.JSON(http.StatusOK, gin.H{"turns": turns})
}

// compressSession handles POST /api/v1/sessions/:sessionId/compress.
func (s *Server) compressSession(c *gin.Context) {
	s.executeSessionCommand(c, sessionops.CommandCompress)
}

// terminateSession handles POST /api/v1/sessions/:sessionId/terminate.
func (s *Server) terminateSession(c *gin.Context) {
	s.executeSessionCommand(c, sessionops.CommandTerminate)
}

// abortSession handles POST /api/v1/sessions/:sessionId/abort.
func (s *Server) abortSession(c *gin.Context) {
	s.executeSessionCommand(c, sessionops.CommandAbort)
}

// archiveSession handles POST /api/v1/sessions/:sessionId/archive.
// Control-plane only: moves idle/active sessions into Operate History.
func (s *Server) archiveSession(c *gin.Context) {
	if !s.requireOperateWrite(c) {
		return
	}
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}
	phase := strings.ToLower(sess.Phase)
	if phase == store.SessionPhaseTerminated {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "session is terminated", Code: sessionops.CodeNotFound})
		return
	}
	if phase == store.SessionPhaseArchived {
		c.JSON(http.StatusOK, gin.H{"accepted": true, "phase": store.SessionPhaseArchived})
		return
	}
	if phase == store.SessionPhaseActive || phase == store.SessionPhaseCompressing {
		c.JSON(http.StatusConflict, ErrorResponse{
			Error: "archive requires idle session",
			Code:  sessionops.CodeBusy,
			Hint:  sessionops.HintWaitIdle,
		})
		return
	}
	if err := s.store.Sessions().UpdatePhase(c.Request.Context(), sess.ID, store.SessionPhaseArchived); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"accepted": true, "phase": store.SessionPhaseArchived})
}

// restoreSession handles POST /api/v1/sessions/:sessionId/restore.
// Control-plane only: archived → idle (soft affinity instanceRef preserved).
func (s *Server) restoreSession(c *gin.Context) {
	if !s.requireOperateWrite(c) {
		return
	}
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}
	phase := strings.ToLower(sess.Phase)
	if phase == store.SessionPhaseTerminated {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "terminated sessions cannot be restored", Code: sessionops.CodeNotFound})
		return
	}
	if phase != store.SessionPhaseArchived && phase != "" {
		c.JSON(http.StatusOK, gin.H{"accepted": true, "phase": sess.Phase})
		return
	}
	if err := s.store.Sessions().UpdatePhase(c.Request.Context(), sess.ID, store.SessionPhaseIdle); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"accepted": true, "phase": store.SessionPhaseIdle})
}

// executeSessionCommand runs a destructive session op through the Session
// Command Router (capability + busy gate + audit).
func (s *Server) executeSessionCommand(c *gin.Context, command string) {
	if !s.requireOperateWrite(c) {
		return
	}
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}
	if s.sessionOps == nil {
		// Standalone without registry: keep legacy ASDP/HTTP dispatch.
		if !s.dispatchSessionCommand(c, sess, command) {
			return
		}
		phase := sess.Phase
		switch command {
		case sessionops.CommandCompress:
			phase = store.SessionPhaseCompressing
		case sessionops.CommandTerminate:
			phase = store.SessionPhaseTerminated
		}
		if phase != sess.Phase {
			if err := s.store.Sessions().UpdatePhase(c.Request.Context(), sess.ID, phase); err != nil {
				c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
				return
			}
		}
		c.JSON(http.StatusOK, gin.H{
			"accepted":  true,
			"commandId": c.GetHeader("X-Command-Id"),
			"phase":     phase,
			"result":    gin.H{},
		})
		return
	}

	force := c.Query("force") == "true" || c.Query("force") == "1"
	queueSet := false
	queue := true
	if q := c.Query("queue"); q == "false" || q == "0" {
		queue = false
		queueSet = true
	} else if q == "true" || q == "1" {
		queue = true
		queueSet = true
	}
	var body struct {
		Force bool  `json:"force"`
		Queue *bool `json:"queue"`
	}
	if c.Request.ContentLength != 0 {
		_ = c.ShouldBindJSON(&body)
		if body.Force {
			force = true
		}
		if body.Queue != nil {
			queue = *body.Queue
			queueSet = true
		}
	}

	req := sessionops.Request{
		Command:   command,
		Operator:  s.operatorFromContext(c),
		Source:    "http",
		Force:     force,
		CommandID: c.GetHeader("X-Command-Id"),
	}
	if queueSet {
		req.Queue = &queue
	}

	result, err := s.sessionOps.Execute(c.Request.Context(), sess, req)
	if err != nil {
		s.writeSessionOpsError(c, err)
		return
	}
	status := http.StatusOK
	if result.Queued {
		status = http.StatusAccepted
	}
	c.JSON(status, gin.H{
		"accepted":  result.Accepted,
		"commandId": result.CommandID,
		"phase":     result.Phase,
		"result":    result.Result,
		"forced":    result.Forced,
		"cached":    result.Cached,
		"queued":    result.Queued,
	})
}

// getSessionTasks proxies GET /api/v1/sessions/:sessionId/tasks to the DP
// when the instance advertises task-query.
func (s *Server) getSessionTasks(c *gin.Context) {
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}
	if s.registry == nil || sess.InstanceRef == "" {
		c.JSON(http.StatusNotImplemented, ErrorResponse{
			Error: "task-query requires a registered instanceRef",
			Code:  sessionops.CodeUnsupported,
		})
		return
	}
	dp := s.registry.Get(sess.InstanceRef)
	if dp == nil || !dp.Healthy {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{
			Error: "instance unreachable",
			Code:  sessionops.CodeUnreachable,
		})
		return
	}
	hasTaskQuery := false
	for _, capName := range dp.Capabilities {
		if capName == v1alpha1.CapabilityTaskQuery {
			hasTaskQuery = true
			break
		}
	}
	if !hasTaskQuery {
		c.JSON(http.StatusNotImplemented, ErrorResponse{
			Error: "data plane does not advertise the task-query capability",
			Code:  sessionops.CodeUnsupported,
		})
		return
	}
	tasks, err := s.prober.FetchTasks(c.Request.Context(), dp.BaseURL, sess.SessionID)
	if err != nil {
		if err == prober.ErrNotFoundOnDataPlane {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "session not found on data plane", Code: sessionops.CodeNotFound})
			return
		}
		c.JSON(http.StatusBadGateway, ErrorResponse{Error: "failed to fetch tasks: " + err.Error(), Code: sessionops.CodeFailed})
		return
	}
	c.JSON(http.StatusOK, gin.H{"tasks": tasks})
}

func (s *Server) getSessionSubagentTasks(c *gin.Context) {
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}
	dp, ok := s.requireSessionDP(c, sess, v1alpha1.CapabilitySubagentTaskQuery)
	if !ok {
		return
	}
	tasks, err := s.prober.FetchSubagentTasks(c.Request.Context(), dp.BaseURL, sess.SessionID)
	if err != nil {
		if err == prober.ErrNotFoundOnDataPlane {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "session not found on data plane", Code: sessionops.CodeNotFound})
			return
		}
		c.JSON(http.StatusBadGateway, ErrorResponse{Error: "failed to fetch subagent tasks: " + err.Error(), Code: sessionops.CodeFailed})
		return
	}
	c.JSON(http.StatusOK, gin.H{"tasks": tasks})
}

func (s *Server) cancelSessionSubagentTask(c *gin.Context) {
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}
	taskID := c.Param("taskId")
	if taskID == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "taskId required"})
		return
	}
	dp, ok := s.requireSessionDP(c, sess, v1alpha1.CapabilitySubagentTaskCommand)
	if !ok {
		return
	}
	if err := s.prober.CancelSubagentTask(c.Request.Context(), dp.BaseURL, sess.SessionID, taskID); err != nil {
		if err == prober.ErrNotFoundOnDataPlane {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "task not found on data plane", Code: sessionops.CodeNotFound})
			return
		}
		c.JSON(http.StatusBadGateway, ErrorResponse{Error: "failed to cancel subagent task: " + err.Error(), Code: sessionops.CodeFailed})
		return
	}
	c.JSON(http.StatusOK, gin.H{"accepted": true, "taskId": taskID})
}

func (s *Server) postSessionPlanMode(c *gin.Context) {
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}
	dp, ok := s.requireSessionDP(c, sess, v1alpha1.CapabilityPlanMode)
	if !ok {
		return
	}
	var body struct {
		Active *bool  `json:"active"`
		Mode   string `json:"mode"`
	}
	_ = c.ShouldBindJSON(&body)
	active := true
	if body.Active != nil {
		active = *body.Active
	} else if body.Mode != "" {
		m := strings.ToLower(body.Mode)
		active = m != "exit" && m != "off" && m != "false"
	}
	if err := s.prober.SendPlanMode(c.Request.Context(), dp.BaseURL, sess.SessionID, active); err != nil {
		c.JSON(http.StatusBadGateway, ErrorResponse{Error: "failed to set plan mode: " + err.Error(), Code: sessionops.CodeFailed})
		return
	}
	c.JSON(http.StatusOK, gin.H{"accepted": true, "active": active, "phase": sess.Phase})
}

// requireSessionDP returns a healthy registry entry that advertises capability.
func (s *Server) requireSessionDP(c *gin.Context, sess *store.Session, capability string) (*dataplane.Entry, bool) {
	if s.registry == nil || sess.InstanceRef == "" {
		c.JSON(http.StatusNotImplemented, ErrorResponse{
			Error: capability + " requires a registered instanceRef",
			Code:  sessionops.CodeUnsupported,
		})
		return nil, false
	}
	dp := s.registry.Get(sess.InstanceRef)
	if dp == nil || !dp.Healthy {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{
			Error: "instance unreachable",
			Code:  sessionops.CodeUnreachable,
		})
		return nil, false
	}
	has := false
	for _, capName := range dp.Capabilities {
		if capName == capability {
			has = true
			break
		}
	}
	if !has {
		c.JSON(http.StatusNotImplemented, ErrorResponse{
			Error: "data plane does not advertise the " + capability + " capability",
			Code:  sessionops.CodeUnsupported,
		})
		return nil, false
	}
	return dp, true
}

// listSessionCommands returns the audit history for one session.
func (s *Server) listSessionCommands(c *gin.Context) {
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}
	list, err := s.store.Commands().List(c.Request.Context(), store.SessionCommandFilter{
		SessionFK: sess.ID,
		Limit:     parseLimit(c, 50),
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"commands": list})
}

// listRecentCommands returns recent ops across sessions (Overview "Recent ops").
func (s *Server) listRecentCommands(c *gin.Context) {
	filter := store.SessionCommandFilter{
		AgentName: c.Query("agent"),
		Namespace: c.Query("namespace"),
		Limit:     parseLimit(c, 50),
	}
	if since := c.Query("since"); since != "" {
		if t, err := time.Parse(time.RFC3339, since); err == nil {
			filter.Since = &t
		} else {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid since (RFC3339)"})
			return
		}
	}
	list, err := s.store.Commands().List(c.Request.Context(), filter)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"commands": list})
}

// requireOperateWrite enforces AISTIO_OPERATE_WRITE_ENABLED for destructive
// session commands. If the env is explicitly "false", reject with 403.
// If unset or "true", allow (dev-friendly default for local/tests).
func (s *Server) requireOperateWrite(c *gin.Context) bool {
	v := strings.TrimSpace(os.Getenv("AISTIO_OPERATE_WRITE_ENABLED"))
	if strings.EqualFold(v, "false") {
		c.JSON(http.StatusForbidden, ErrorResponse{
			Error: "operate write disabled (AISTIO_OPERATE_WRITE_ENABLED=false)",
			Code:  "forbidden",
		})
		return false
	}
	return true
}

func (s *Server) operatorFromContext(c *gin.Context) string {
	if u, ok := c.Get("username"); ok {
		if name, ok := u.(string); ok && name != "" {
			return name
		}
	}
	return "token:static"
}

func (s *Server) writeSessionOpsError(c *gin.Context, err error) {
	if opErr, ok := sessionops.AsError(err); ok {
		c.JSON(opErr.Status, ErrorResponse{
			Error: opErr.Msg,
			Code:  opErr.Code,
			Hint:  opErr.Hint,
		})
		return
	}
	c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error(), Code: sessionops.CodeFailed})
}

// dispatchSessionCommand delivers a session command, preferring a live ASDP
// stream (session's instanceRef) and falling back to the HTTP data-plane
// contract. Used only when sessionOps is unavailable (no registry).
func (s *Server) dispatchSessionCommand(c *gin.Context, sess *store.Session, command string) bool {
	// 1) ASDP fast path: the instance holds a live gRPC stream.
	if s.asdpCommands != nil && sess.InstanceRef != "" {
		if err := s.asdpCommands.SendSessionCommand(sess.Namespace, sess.InstanceRef, sess.SessionID, command); err == nil {
			return true
		}
	}

	// 2) HTTP contract fallback.
	endpoint, ok := s.resolveSessionEndpoint(c, sess)
	if !ok {
		return false
	}
	var err error
	switch command {
	case sessionops.CommandCompress:
		err = s.prober.SendCompress(c.Request.Context(), endpoint, sess.SessionID)
	case sessionops.CommandTerminate:
		err = s.prober.SendTerminate(c.Request.Context(), endpoint, sess.SessionID)
	default:
		// abort/undo/redo not on legacy prober helpers — reject.
		c.JSON(http.StatusNotImplemented, ErrorResponse{
			Error: "command requires sessionops router + registry: " + command,
			Code:  sessionops.CodeUnsupported,
		})
		return false
	}
	if err != nil {
		c.JSON(http.StatusBadGateway, ErrorResponse{Error: "failed to dispatch " + command + " command: " + err.Error()})
		return false
	}
	return true
}

// deleteSession handles DELETE /api/v1/sessions/:sessionId. It marks the
// session terminated in the store (soft delete); historical rows are removed
// later by the retention worker.
func (s *Server) deleteSession(c *gin.Context) {
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}
	if err := s.store.Sessions().UpdatePhase(c.Request.Context(), sess.ID, store.SessionPhaseTerminated); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	c.Status(http.StatusNoContent)
}

// resolveSessionAgent looks up the session's Agent, writing an error
// response on failure. In standalone mode (no kube client) a synthetic Agent
// is built from the data-plane registry so capability gates still work.
func (s *Server) resolveSessionAgent(c *gin.Context, sess *store.Session) (*v1alpha1.Agent, bool) {
	if s.client != nil {
		var agent v1alpha1.Agent
		if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: sess.AgentName, Namespace: sess.Namespace}, &agent); err != nil {
			if errors.IsNotFound(err) {
				c.JSON(http.StatusNotFound, ErrorResponse{Error: "agent not found"})
			} else {
				c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
			}
			return nil, false
		}
		return &agent, true
	}
	if s.registry != nil {
		for _, dp := range s.registry.ListByAgent(sess.AgentName, sess.Namespace) {
			agent := &v1alpha1.Agent{}
			agent.Name = sess.AgentName
			agent.Namespace = sess.Namespace
			agent.Status.DataPlaneInfo = &v1alpha1.DataPlaneInfo{
				ContractLevel: dp.ContractLevel,
				Capabilities:  append([]string{}, dp.Capabilities...),
			}
			return agent, true
		}
	}
	c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "agent lookup requires a Kubernetes connection or a registered data plane"})
	return nil, false
}

// resolveSessionEndpoint returns a live HTTP base URL for the session's data
// plane. Prefers the self-registration registry, then K8s endpoint resolution.
func (s *Server) resolveSessionEndpoint(c *gin.Context, sess *store.Session) (string, bool) {
	if s.registry != nil {
		if sess.InstanceRef != "" {
			if dp := s.registry.Get(sess.InstanceRef); dp != nil && dp.BaseURL != "" {
				return dp.BaseURL, true
			}
		}
		for _, dp := range s.registry.ListByAgent(sess.AgentName, sess.Namespace) {
			if dp.Healthy && dp.BaseURL != "" {
				return dp.BaseURL, true
			}
		}
	}
	if s.client == nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "no data plane endpoint registered for this session"})
		return "", false
	}
	agent, ok := s.resolveSessionAgent(c, sess)
	if !ok {
		return "", false
	}
	endpoint, err := endpoints.ResolveAgentHTTP(c.Request.Context(), s.client, agent)
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "failed to resolve agent endpoint: " + err.Error()})
		return "", false
	}
	return endpoint, true
}

func parseOffset(c *gin.Context) int {
	offsetStr := c.DefaultQuery("offset", "")
	if offsetStr == "" {
		return 0
	}
	offset, err := strconv.Atoi(offsetStr)
	if err != nil || offset < 0 {
		return 0
	}
	return offset
}

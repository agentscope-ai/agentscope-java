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
	"encoding/json"
	stderrors "errors"
	"fmt"
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
	tenant := c.DefaultQuery("tenant", "default")

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
		sess, err = s.store.Sessions().Get(ctx, tenant, agentName, namespace, sessionIDParam)
	}
	if err == nil && sess.Tenant != tenant {
		err = store.ErrNotFound
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
		page, hit, err := s.transcriptMessages(c.Request.Context(), sess.Tenant, sess.AgentName, sess.Namespace, sess.SessionID, offset, limit, fromEnd)
		if err != nil {
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: "failed to read transcript: " + err.Error()})
			return
		}
		if hit {
			c.JSON(http.StatusOK, page)
			return
		}
	}

	// Level-2 message/tool events are already durable control-plane facts. They
	// are sufficient for the conversation read model and must be preferred over
	// an optional live message-query call. This keeps event-reporting-only
	// runtimes usable after the holding instance disconnects as well.
	if page, hit, err := s.messagePageFromEvents(c.Request.Context(), sess, offset, limit, fromEnd); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: "failed to project messages from events: " + err.Error()})
		return
	} else if hit {
		c.JSON(http.StatusOK, page)
		return
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

func (s *Server) messagePageFromEvents(ctx context.Context, sess *store.Session, offset, limit int, fromEnd bool) (*prober.MessagePage, bool, error) {
	events, err := s.store.Events().List(ctx, sess.ID)
	if err != nil {
		return nil, false, err
	}
	messages := make([]prober.MessageItem, 0, len(events))
	for _, event := range events {
		if !eventProjectsToMessage(event) {
			continue
		}
		messages = append(messages, messageFromEvent(event))
	}
	if len(messages) == 0 {
		return nil, false, nil
	}
	total := len(messages)
	if fromEnd && offset == 0 && total > limit {
		offset = total - limit
	}
	if offset > total {
		offset = total
	}
	end := offset + limit
	if end > total {
		end = total
	}
	return &prober.MessagePage{
		SessionID: sess.SessionID,
		Offset:    offset,
		Limit:     limit,
		Total:     total,
		Messages:  messages[offset:end],
		Source:    "events",
	}, true, nil
}

func eventProjectsToMessage(event *store.SessionEvent) bool {
	if event == nil {
		return false
	}
	switch strings.ToLower(strings.TrimSpace(event.EventType)) {
	case "message", "user_message", "assistant_message", "agent_message", "tool_call", "tool_use", "tool_result":
		return true
	default:
		return strings.TrimSpace(event.Role) != "" &&
			(event.Content != "" || event.ToolName != "" || len(event.ToolInput) > 0 || event.ToolOutput != "")
	}
}

func messageFromEvent(event *store.SessionEvent) prober.MessageItem {
	role := strings.ToLower(strings.TrimSpace(event.Role))
	if role == "" {
		if strings.Contains(strings.ToLower(event.EventType), "result") {
			role = "tool"
		} else if event.ToolName != "" || len(event.ToolInput) > 0 {
			role = "assistant"
		}
	}
	return prober.MessageItem{
		Seq:        int32(event.Seq),
		Role:       role,
		Content:    event.Content,
		ToolName:   event.ToolName,
		ToolCallID: eventToolCallID(event.FrameworkMeta),
		ToolInput:  event.ToolInput,
		ToolOutput: event.ToolOutput,
		OccurredAt: event.OccurredAt.UTC().Format(time.RFC3339Nano),
	}
}

func eventToolCallID(raw json.RawMessage) string {
	if len(raw) == 0 {
		return ""
	}
	var metadata map[string]any
	if json.Unmarshal(raw, &metadata) != nil {
		return ""
	}
	for _, key := range []string{"toolCallId", "toolUseId", "tool_use_id", "callId"} {
		if value, ok := metadata[key]; ok && value != nil {
			return fmt.Sprint(value)
		}
	}
	return ""
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
// Supports reverse paging via before and forward gap repair via after (exclusive seq).
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
	afterSet := false
	if after := c.Query("after"); after != "" {
		if beforeSet {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "before and after are mutually exclusive"})
			return
		}
		seq, err := strconv.Atoi(after)
		if err != nil || seq < 0 {
			c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid after (non-negative integer seq required)"})
			return
		}
		afterSet = true
		opts = append(opts, store.WithEventAfterSeq(seq))
	}
	opts = append(opts, store.WithEventLimit(parseLimit(c, 100)))
	if offset := parseOffset(c); offset > 0 {
		opts = append(opts, store.WithEventOffset(offset))
	} else if !beforeSet && !afterSet {
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

// streamSessionEvents sends the durable session log as resumable SSE. The
// initial REST tail plus this stream is the canonical conversation read path;
// clients derive Messages from these events instead of polling message-query.
func (s *Server) streamSessionEvents(c *gin.Context) {
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}
	after, _ := strconv.Atoi(c.Query("after"))
	if headerAfter, err := strconv.Atoi(c.GetHeader("Last-Event-ID")); err == nil && headerAfter > after {
		after = headerAfter
	}
	// http.Server.WriteTimeout is an absolute deadline for the whole response,
	// not an idle timeout. Clear it for this long-lived stream; heartbeat frames
	// keep intermediaries alive and the request context still handles clients
	// that disconnect.
	_ = http.NewResponseController(c.Writer).SetWriteDeadline(time.Time{})
	prepareEventStream(c)
	flusher, _ := c.Writer.(http.Flusher)
	for {
		if a := accessFrom(c); a != nil {
			n, err := s.store.Access().GetNamespace(c.Request.Context(), a.Namespace.Tenant, a.Namespace.Name)
			if err != nil || len(n.Roles(a.User)) == 0 {
				return
			}
			fresh := *a
			fresh.Namespace = n
			fresh.Roles = n.Roles(a.User)
			if !s.canAccessSession(c.Request.Context(), &fresh, sess, false) {
				return
			}
		}
		events, err := s.store.Events().List(c.Request.Context(), sess.ID,
			store.WithEventAfterSeq(after), store.WithEventLimit(1000))
		if err != nil {
			return
		}
		for _, event := range events {
			payload, _ := json.Marshal(event)
			_, _ = fmt.Fprintf(c.Writer, "id: %d\nevent: %s\ndata: %s\n\n", event.Seq, event.EventType, payload)
			after = event.Seq
		}
		if flusher != nil {
			flusher.Flush()
		}
		if len(events) > 0 {
			continue
		}
		waitCtx, cancel := context.WithTimeout(c.Request.Context(), 15*time.Second)
		err = s.store.Events().WaitForNew(waitCtx, sess.ID, after)
		cancel()
		if err == nil {
			continue
		}
		if c.Request.Context().Err() != nil {
			return
		}
		if sessionEventWaitTimedOut(err) {
			_, _ = fmt.Fprint(c.Writer, ": heartbeat\n\n")
			if flusher != nil {
				flusher.Flush()
			}
			continue
		}
		return
	}
}

func sessionEventWaitTimedOut(err error) bool {
	return stderrors.Is(err, context.DeadlineExceeded)
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

func (s *Server) postSessionUserMessage(c *gin.Context) {
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}
	if a := accessFrom(c); a != nil && len(a.Namespace.Resources) > 0 {
		if err := s.checkResourceUse(c.Request.Context(), a.Namespace, a.User, "agent:"+sess.AgentID.String()); err != nil {
			c.JSON(403, ErrorResponse{Error: err.Error()})
			return
		}
	}
	var body struct {
		Content string `json:"content"`
	}
	if err := c.ShouldBindJSON(&body); err != nil || strings.TrimSpace(body.Content) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "content is required"})
		return
	}
	content := strings.TrimSpace(body.Content)
	// Sessions created by the unified Agent catalog are routed by their immutable
	// RuntimeBinding. Managed and Hosted sessions intentionally have no legacy
	// instanceRef, while External sessions use the durable AgentInstance registry
	// and ASDP transport. Only pre-catalog BYO sessions use the legacy HTTP prober.
	if sess.BindingID != uuid.Nil {
		if err := s.sendAgentConversationTurn(
			c.Request.Context(), sess, content, "session_console", sess.ID.String()); err != nil {
			writeConversationTurnError(c, err)
			return
		}
		c.JSON(http.StatusAccepted, gin.H{"accepted": true, "phase": sess.Phase})
		return
	}
	dp, ok := s.requireHealthyInstance(c, sess)
	if !ok {
		return
	}
	if err := s.prober.SendUserMessage(c.Request.Context(), dp.BaseURL, sess.SessionID, content); err != nil {
		if err == prober.ErrNotFoundOnDataPlane {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "session not found on data plane", Code: sessionops.CodeNotFound})
			return
		}
		if err == prober.ErrBusyOnDataPlane {
			c.JSON(http.StatusConflict, ErrorResponse{Error: "session busy on data plane", Code: sessionops.CodeBusy, Hint: sessionops.HintWaitIdle})
			return
		}
		c.JSON(http.StatusBadGateway, ErrorResponse{Error: "failed to inject user message: " + err.Error(), Code: sessionops.CodeFailed})
		return
	}
	c.JSON(http.StatusAccepted, gin.H{"accepted": true, "phase": sess.Phase})
}

// requireHealthyInstance returns the registered instance that holds this session.
func (s *Server) requireHealthyInstance(c *gin.Context, sess *store.Session) (*dataplane.Entry, bool) {
	if s.registry == nil || sess.InstanceRef == "" {
		c.JSON(http.StatusNotImplemented, ErrorResponse{
			Error: "user-message requires a registered instanceRef",
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
	return dp, true
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
	if a := accessFrom(c); a != nil {
		filtered := list[:0]
		for _, item := range list {
			if item.SessionFK == nil {
				continue
			}
			session, e := s.store.Sessions().GetByID(c.Request.Context(), *item.SessionFK)
			if e == nil && s.canAccessSession(c.Request.Context(), a, session, false) {
				filtered = append(filtered, item)
			}
		}
		list = filtered
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
	if user := c.GetString("userId"); user != "" {
		return user
	}
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
		if err := s.asdpCommands.SendSessionCommand(sess.Tenant, sess.Namespace, sess.AgentID.String(), sess.InstanceRef, sess.SessionID, command); err == nil {
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
		for _, dp := range s.registry.ListByAgent(sess.Tenant, sess.AgentName, sess.Namespace) {
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
		for _, dp := range s.registry.ListByAgent(sess.Tenant, sess.AgentName, sess.Namespace) {
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

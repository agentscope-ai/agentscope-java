package httpapi

import (
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/types"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/endpoints"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
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

// getSessionMessages handles GET /api/v1/sessions/:sessionId/messages by
// forwarding to the data-plane Level-3 endpoint. Gated on the
// `message-query` capability.
func (s *Server) getSessionMessages(c *gin.Context) {
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}
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
	page, err := s.prober.FetchMessages(c.Request.Context(), endpoint, sess.SessionID, parseOffset(c), parseLimit(c, 100))
	if err != nil {
		if err == prober.ErrNotFoundOnDataPlane {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "session not found on data plane"})
		} else {
			c.JSON(http.StatusBadGateway, ErrorResponse{Error: "failed to fetch messages from data plane: " + err.Error()})
		}
		return
	}
	c.JSON(http.StatusOK, page)
}

// getSessionEvents handles GET /api/v1/sessions/:sessionId/events, returning
// the Level-2 event stream for the session with optional filters.
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
	opts = append(opts, store.WithEventLimit(parseLimit(c, 100)))
	if offset := parseOffset(c); offset > 0 {
		opts = append(opts, store.WithEventOffset(offset))
	}

	events, err := s.store.Events().List(c.Request.Context(), sess.ID, opts...)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"events": events})
}

// compressSession handles POST /api/v1/sessions/:sessionId/compress by
// dispatching a live compress command to the session's data-plane instance.
func (s *Server) compressSession(c *gin.Context) {
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}

	if !s.dispatchSessionCommand(c, sess, "compress") {
		return
	}

	if err := s.store.Sessions().UpdatePhase(c.Request.Context(), sess.ID, store.SessionPhaseCompressing); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"sessionId": sess.SessionID, "command": "compress", "status": "initiated"})
}

// terminateSession handles POST /api/v1/sessions/:sessionId/terminate by
// dispatching a live terminate command to the session's data-plane instance.
func (s *Server) terminateSession(c *gin.Context) {
	sess, ok := s.resolveSession(c)
	if !ok {
		return
	}

	if !s.dispatchSessionCommand(c, sess, "terminate") {
		return
	}

	if err := s.store.Sessions().UpdatePhase(c.Request.Context(), sess.ID, store.SessionPhaseTerminated); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"sessionId": sess.SessionID, "command": "terminate", "status": "initiated"})
}

// dispatchSessionCommand delivers a session command, preferring a live ASDP
// stream (session's instanceRef) and falling back to the HTTP data-plane
// contract. It writes the error response and returns false on failure.
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
	case "compress":
		err = s.prober.SendCompress(c.Request.Context(), endpoint, sess.SessionID)
	default:
		err = s.prober.SendTerminate(c.Request.Context(), endpoint, sess.SessionID)
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
			agent.Status.DataPlaneInfo.ContractLevel = dp.ContractLevel
			agent.Status.DataPlaneInfo.Capabilities = append([]string{}, dp.Capabilities...)
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

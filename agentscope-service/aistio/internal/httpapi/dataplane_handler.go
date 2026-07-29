package httpapi

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"

	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
)

type registerReq struct {
	AgentName    string   `json:"agentName"`
	Namespace    string   `json:"namespace"`
	InstanceID   string   `json:"instanceId"`
	BaseURL      string   `json:"baseUrl"`
	Runtime      string   `json:"runtime"`
	Framework    string   `json:"framework"`
	ContractLevel int32   `json:"contractLevel"`
	Capabilities []string `json:"capabilities"`
	Source       string   `json:"source"`
}

func (s *Server) registerDataPlane(c *gin.Context) {
	if s.registry == nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "data plane registry not enabled"})
		return
	}
	var req registerReq
	if err := c.ShouldBindJSON(&req); err != nil || req.InstanceID == "" || req.AgentName == "" || req.BaseURL == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "agentName, instanceId, and baseUrl are required"})
		return
	}
	interval := s.registry.Upsert(dataplane.Entry{
		AgentName:     req.AgentName,
		Namespace:     req.Namespace,
		InstanceID:    req.InstanceID,
		BaseURL:       strings.TrimRight(req.BaseURL, "/"),
		Runtime:       req.Runtime,
		Framework:     req.Framework,
		ContractLevel: req.ContractLevel,
		Capabilities:  req.Capabilities,
		Source:        firstNonEmpty(req.Source, dataplane.SourceSelfRegister),
	})
	c.JSON(http.StatusOK, gin.H{
		"instanceId":        req.InstanceID,
		"heartbeatInterval": interval.Seconds(),
		"status":            "registered",
	})
}

func (s *Server) heartbeatDataPlane(c *gin.Context) {
	if s.registry == nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "data plane registry not enabled"})
		return
	}
	id := c.Param("instanceId")
	if !s.registry.Heartbeat(id) {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "unknown instance"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"instanceId": id, "status": "ok"})
}

func (s *Server) deleteDataPlane(c *gin.Context) {
	if s.registry == nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "data plane registry not enabled"})
		return
	}
	id := c.Param("instanceId")
	if !s.registry.Delete(id) {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "unknown instance"})
		return
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) listDataPlanes(c *gin.Context) {
	if s.registry == nil {
		c.JSON(http.StatusOK, gin.H{"dataplanes": []any{}})
		return
	}
	items := s.registry.List()
	if agent := c.Query("agent"); agent != "" {
		ns := c.DefaultQuery("namespace", defaultNamespace)
		var filtered []*dataplane.Entry
		for _, e := range items {
			if e.AgentName == agent && e.Namespace == ns {
				filtered = append(filtered, e)
			}
		}
		items = filtered
	}
	if items == nil {
		items = []*dataplane.Entry{}
	}
	c.JSON(http.StatusOK, gin.H{"dataplanes": items})
}

// listAgentsFromRegistry serves GET /api/v1/agents when no Kubernetes client
// is configured.
func (s *Server) listAgentsFromRegistry(c *gin.Context) {
	if s.registry == nil {
		c.JSON(http.StatusOK, AgentListResponse{Items: []AgentSummary{}})
		return
	}
	summaries := s.registry.AggregateAgents()
	nsFilter := c.Query("namespace")
	items := make([]AgentSummary, 0, len(summaries))
	for _, a := range summaries {
		if nsFilter != "" && a.Namespace != nsFilter {
			continue
		}
		var active int32
		if s.store != nil {
			n, _ := s.store.Sessions().CountActive(c.Request.Context(), a.Name, a.Namespace)
			active = n
		}
		items = append(items, AgentSummary{
			Name:           a.Name,
			Namespace:      a.Namespace,
			Type:           "BYO",
			Runtime:        a.Runtime,
			DisplayName:    a.Name,
			Replicas:       a.Replicas,
			ActiveSessions: active,
		})
	}
	c.JSON(http.StatusOK, AgentListResponse{Items: items})
}

func (s *Server) getAgentFromRegistry(c *gin.Context) {
	if s.registry == nil {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "agent not found"})
		return
	}
	name := c.Param("name")
	ns := c.DefaultQuery("namespace", defaultNamespace)
	entries := s.registry.ListByAgent(name, ns)
	if len(entries) == 0 {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "agent not found"})
		return
	}
	sum := s.registry.AggregateAgents()
	var match *dataplane.AgentSummary
	for i := range sum {
		if sum[i].Name == name && sum[i].Namespace == ns {
			match = &sum[i]
			break
		}
	}
	if match == nil {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "agent not found"})
		return
	}
	var active int32
	if s.store != nil {
		active, _ = s.store.Sessions().CountActive(c.Request.Context(), name, ns)
	}
	c.JSON(http.StatusOK, gin.H{
		"name":           match.Name,
		"namespace":      match.Namespace,
		"type":           "BYO",
		"runtime":        match.Runtime,
		"framework":      match.Framework,
		"replicas":       match.Replicas,
		"activeSessions": active,
		"contractLevel":  match.ContractLevel,
		"capabilities":   match.Capabilities,
		"instances":      entries,
		"source":         "registry",
	})
}

// internalTokenMiddleware authenticates data-plane self-registration calls.
func (s *Server) internalTokenMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		if s.internalToken == "" {
			c.Next()
			return
		}
		tok := c.GetHeader("X-Builder-Internal-Token")
		if tok == "" || tok != s.internalToken {
			c.AbortWithStatusJSON(http.StatusUnauthorized, ErrorResponse{Error: "invalid internal token"})
			return
		}
		c.Next()
	}
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

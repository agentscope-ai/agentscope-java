package httpapi

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

// Namespace convention for the REST API:
//   - List endpoints (GET collection) treat an absent/empty `?namespace=` query
//     param as "all namespaces".
//   - Single-resource endpoints (GET/PATCH/DELETE/push on a named object) default
//     the namespace to `defaultNamespace` ("default") when `?namespace=` is absent.
const (
	defaultNamespace = "default"
	// revisionsAnnotation stores the JSON-encoded revision history on the Agent.
	revisionsAnnotation = "agentscope.io/revisions"
	// maxRevisionHistory bounds the retained revision-history entries.
	maxRevisionHistory = 20
)

func (s *Server) listAgents(c *gin.Context) {
	namespace := c.DefaultQuery("namespace", "")
	typeFilter := c.Query("type")
	limit := parseLimit(c, 100)
	continueToken := c.Query("continue")

	var agentList v1alpha1.AgentList
	opts := []client.ListOption{}
	if namespace != "" {
		opts = append(opts, client.InNamespace(namespace))
	}
	if limit > 0 {
		opts = append(opts, client.Limit(int64(limit)))
	}
	if continueToken != "" {
		opts = append(opts, client.Continue(continueToken))
	}
	if err := s.client.List(c.Request.Context(), &agentList, opts...); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	items := make([]AgentSummary, 0, len(agentList.Items))
	for _, a := range agentList.Items {
		if typeFilter != "" && string(a.Spec.Type) != typeFilter {
			continue
		}
		items = append(items, AgentSummary{
			Name:           a.Name,
			Namespace:      a.Namespace,
			Type:           string(a.Spec.Type),
			Runtime:        a.Spec.Runtime,
			DisplayName:    a.Spec.DisplayName,
			Replicas:       fmt.Sprintf("%d/%d", a.Status.Replicas.Ready, a.Status.Replicas.Desired),
			ActiveSessions: a.Status.ActiveSessions,
			Revision:       a.Status.Revision,
		})
	}

	resp := AgentListResponse{Items: items}
	if agentList.Continue != "" {
		resp.Metadata = &ListMetadata{Continue: agentList.Continue}
	}
	c.JSON(http.StatusOK, resp)
}

func (s *Server) getAgent(c *gin.Context) {
	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var agent v1alpha1.Agent
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: name, Namespace: namespace}, &agent); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "agent not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusOK, agent)
}

func (s *Server) pushAgent(c *gin.Context) {
	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var req PushAgentRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body", Message: err.Error()})
		return
	}

	ctx := c.Request.Context()
	revision := generateRevision(name, time.Now())
	createdResources := []CreatedResource{}

	// Check if agent already exists
	var existingAgent v1alpha1.Agent
	err := s.client.Get(ctx, types.NamespacedName{Name: name, Namespace: namespace}, &existingAgent)
	isNew := errors.IsNotFound(err)

	if err != nil && !isNew {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	// Build the desired Agent and append a revision-history entry.
	desired := s.buildAgentFromPush(name, namespace, &req)
	var ownerAgent *v1alpha1.Agent

	if isNew {
		appendRevision(&desired.ObjectMeta, revision, "push", &desired.Spec)
		if err := s.client.Create(ctx, desired); err != nil {
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: fmt.Sprintf("creating agent: %v", err)})
			return
		}
		ownerAgent = desired
		createdResources = append(createdResources, CreatedResource{Kind: "Agent", Name: name})
	} else {
		existingAgent.Spec = desired.Spec
		if existingAgent.Annotations == nil {
			existingAgent.Annotations = map[string]string{}
		}
		// The revision snapshots the spec being applied (the new desired spec),
		// so that revision IDs, status.Revision, GET /revisions/{rev} and
		// rollback all refer to the same configuration consistently.
		appendRevision(&existingAgent.ObjectMeta, revision, "push", &existingAgent.Spec)
		if err := s.client.Update(ctx, &existingAgent); err != nil {
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: fmt.Sprintf("updating agent: %v", err)})
			return
		}
		existingAgent.Status.Revision = revision
		_ = s.client.Status().Update(ctx, &existingAgent)
		ownerAgent = &existingAgent
	}

	owner := agentOwnerRef(ownerAgent)

	// Declarative-only resources: model Secret + ModelConfig.
	if req.Image == "" && req.Model != nil {
		// Inline API key -> Secret (owned by the Agent).
		secretName := ""
		if req.Model.APIKey != "" {
			secretName = fmt.Sprintf("%s-model-key", name)
			secret := buildModelSecret(secretName, namespace, req.Model.APIKey, owner)
			if err := s.upsertSecret(ctx, secret); err == nil {
				createdResources = append(createdResources, CreatedResource{Kind: "Secret", Name: secretName})
			}
		}

		mc := s.buildModelConfigFromPush(name, namespace, req.Model, secretName, owner)
		var existingMC v1alpha1.ModelConfig
		if err := s.client.Get(ctx, client.ObjectKeyFromObject(mc), &existingMC); errors.IsNotFound(err) {
			if err := s.client.Create(ctx, mc); err == nil {
				createdResources = append(createdResources, CreatedResource{Kind: "ModelConfig", Name: mc.Name})
			}
		} else if err == nil {
			existingMC.Spec = mc.Spec
			_ = s.client.Update(ctx, &existingMC)
		}
	}

	// MCPServer CRDs for tools. These are namespace-shared resources, so they are
	// NOT owned by a single Agent (no ownerRef) — only created/updated (deduped).
	if req.Tools != nil {
		for i := range req.Tools.Tools {
			tool := req.Tools.Tools[i]
			if tool.MCPServerName == "" {
				continue
			}
			mcp := s.buildMCPServerFromPush(namespace, &tool)
			var existingMCP v1alpha1.MCPServer
			if err := s.client.Get(ctx, client.ObjectKeyFromObject(mcp), &existingMCP); errors.IsNotFound(err) {
				if err := s.client.Create(ctx, mcp); err == nil {
					createdResources = append(createdResources, CreatedResource{Kind: "MCPServer", Name: mcp.Name})
				}
			} else if err == nil {
				existingMCP.Spec = mcp.Spec
				_ = s.client.Update(ctx, &existingMCP)
			}
		}
	}

	statusCode := http.StatusOK
	if isNew {
		statusCode = http.StatusCreated
	}

	c.JSON(statusCode, PushAgentResponse{
		Name:      name,
		Namespace: namespace,
		Type:      string(ownerAgent.Spec.Type),
		Revision:  revision,
		UpdatedAt: time.Now().Format(time.RFC3339),
		Status: &AgentStatusBrief{
			Phase: "Reconciling",
		},
		CreatedResources: createdResources,
	})
}

// upsertSecret creates or updates the model API-key Secret.
func (s *Server) upsertSecret(ctx context.Context, secret *corev1.Secret) error {
	var existing corev1.Secret
	if err := s.client.Get(ctx, client.ObjectKeyFromObject(secret), &existing); errors.IsNotFound(err) {
		return s.client.Create(ctx, secret)
	} else if err != nil {
		return err
	}
	existing.Data = secret.Data
	return s.client.Update(ctx, &existing)
}

func (s *Server) patchAgent(c *gin.Context) {
	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var agent v1alpha1.Agent
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: name, Namespace: namespace}, &agent); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "agent not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	var patch map[string]interface{}
	if err := c.ShouldBindJSON(&patch); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	if displayName, ok := patch["displayName"].(string); ok {
		agent.Spec.DisplayName = displayName
	}
	if description, ok := patch["description"].(string); ok {
		agent.Spec.Description = description
	}

	if err := s.client.Update(c.Request.Context(), &agent); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusOK, agent)
}

func (s *Server) deleteAgent(c *gin.Context) {
	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var agent v1alpha1.Agent
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: name, Namespace: namespace}, &agent); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "agent not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	if err := s.client.Delete(c.Request.Context(), &agent); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.Status(http.StatusNoContent)
}

func (s *Server) agentHealth(c *gin.Context) {
	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var agent v1alpha1.Agent
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: name, Namespace: namespace}, &agent); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "agent not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	healthy := false
	for _, cond := range agent.Status.Conditions {
		if cond.Type == v1alpha1.ConditionReady && cond.Status == metav1.ConditionTrue {
			healthy = true
			break
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"name":    name,
		"healthy": healthy,
		"replicas": gin.H{
			"desired": agent.Status.Replicas.Desired,
			"ready":   agent.Status.Replicas.Ready,
		},
	})
}

func (s *Server) listRevisions(c *gin.Context) {
	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var agent v1alpha1.Agent
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: name, Namespace: namespace}, &agent); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "agent not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	entries := readRevisions(&agent.ObjectMeta)
	summaries := make([]RevisionSummary, len(entries))
	for i, e := range entries {
		summaries[i] = RevisionSummary{
			Revision:  e.Revision,
			CreatedAt: e.CreatedAt,
			Message:   e.Message,
		}
	}
	c.JSON(http.StatusOK, RevisionListResponse{Revisions: summaries})
}

func (s *Server) buildAgentFromPush(name, namespace string, req *PushAgentRequest) *v1alpha1.Agent {
	runtime := req.Runtime
	if runtime == "" {
		runtime = "agentscope-java"
	}

	agent := &v1alpha1.Agent{
		ObjectMeta: metav1.ObjectMeta{
			Name:      name,
			Namespace: namespace,
		},
		Spec: v1alpha1.AgentSpec{
			Runtime:     runtime,
			DisplayName: req.DisplayName,
			Description: req.Description,
		},
	}

	// BYO (image) push: build a BYO agent and return early; declarative-only
	// fields (model, system prompt, tools) do not apply.
	if req.Image != "" {
		agent.Spec.Type = v1alpha1.AgentTypeBYO
		byo := &v1alpha1.BYOSpec{
			Image:   req.Image,
			Command: req.Command,
			Args:    req.Args,
		}
		if req.Deployment != nil && req.Deployment.Replicas > 0 {
			replicas := req.Deployment.Replicas
			byo.Replicas = &replicas
		}
		agent.Spec.BYO = byo
		return agent
	}

	agent.Spec.Type = v1alpha1.AgentTypeDeclarative
	agent.Spec.Declarative = &v1alpha1.DeclarativeSpec{
		AgentConfig: v1alpha1.AgentConfig{
			SystemMessage:  req.SystemPrompt,
			ModelConfigRef: fmt.Sprintf("%s-model", name),
			Stream:         true,
		},
	}

	if req.Deployment != nil {
		if req.Deployment.Replicas > 0 {
			replicas := req.Deployment.Replicas
			agent.Spec.Declarative.Replicas = &replicas
		}
		if req.Deployment.Resources != nil {
			agent.Spec.Declarative.Resources = &v1alpha1.ResourceRequirements{}
			if req.Deployment.Resources.Requests != nil {
				agent.Spec.Declarative.Resources.Requests = v1alpha1.ResourceList{
					CPU:    req.Deployment.Resources.Requests["cpu"],
					Memory: req.Deployment.Resources.Requests["memory"],
				}
			}
			if req.Deployment.Resources.Limits != nil {
				agent.Spec.Declarative.Resources.Limits = v1alpha1.ResourceList{
					CPU:    req.Deployment.Resources.Limits["cpu"],
					Memory: req.Deployment.Resources.Limits["memory"],
				}
			}
		}
	}

	// Build tool bindings
	if req.Tools != nil {
		for _, t := range req.Tools.Tools {
			binding := v1alpha1.ToolBinding{
				Type: "McpServer",
				MCPServer: &v1alpha1.MCPServerRef{
					Name:      t.MCPServerName,
					ToolNames: []string{t.Name},
				},
			}
			// Per-tool approval takes precedence over the global map.
			if t.RequireApproval {
				binding.MCPServer.RequireApproval = append(binding.MCPServer.RequireApproval, t.Name)
			} else if req.Tools.InterruptConfig != nil {
				// Fallback: apply the global InterruptConfig for backward compatibility.
				if val, ok := req.Tools.InterruptConfig[t.Name]; ok && val {
					binding.MCPServer.RequireApproval = append(binding.MCPServer.RequireApproval, t.Name)
				}
			}
			agent.Spec.Declarative.Tools = append(agent.Spec.Declarative.Tools, binding)
		}
	}

	// Build skill bindings
	if len(req.Skills) > 0 {
		if agent.Spec.Declarative.Skills == nil {
			agent.Spec.Declarative.Skills = &v1alpha1.SkillsSpec{}
		}
		for _, sk := range req.Skills {
			switch sk.Type {
			case "oci":
				// OCI skills are stored as refs for the runtime to pull.
				if sk.Ref != "" {
					agent.Spec.Declarative.Skills.Refs = append(agent.Spec.Declarative.Skills.Refs, sk.Ref)
				}
			default:
				// Inline skills (or any other type) carry full metadata.
				agent.Spec.Declarative.Skills.Bindings = append(agent.Spec.Declarative.Skills.Bindings, v1alpha1.SkillBinding{
					Name:         sk.Name,
					Description:  sk.Description,
					Instructions: sk.Instructions,
					Ref:          sk.Ref,
				})
			}
		}
	}

	// Sub-agents (in-process) carried on the declarative spec for the runtime.
	for _, sa := range req.Subagents {
		agent.Spec.Declarative.Subagents = append(agent.Spec.Declarative.Subagents, v1alpha1.SubagentSpec{
			Name:          sa.Name,
			Description:   sa.Description,
			Model:         sa.Model,
			Instructions:  sa.Instructions,
			Tools:         sa.Tools,
			Steps:         sa.Steps,
			WorkspaceMode: sa.WorkspaceMode,
			URL:           sa.URL,
		})
	}

	// Team templates instantiated by the lead at runtime (not created here).
	for _, tt := range req.TeamTemplates {
		tmpl := v1alpha1.TeamTemplateRef{
			Name:        tt.Name,
			Description: tt.Description,
		}
		for _, m := range tt.Members {
			tmpl.Members = append(tmpl.Members, v1alpha1.TeamTemplateMember{
				Role:     m.Role,
				AgentRef: m.AgentRef,
				Prompt:   m.Prompt,
			})
		}
		agent.Spec.Declarative.TeamTemplates = append(agent.Spec.Declarative.TeamTemplates, tmpl)
	}

	return agent
}

func (s *Server) buildModelConfigFromPush(agentName, namespace string, model *ModelSpec, secretName string, owner metav1.OwnerReference) *v1alpha1.ModelConfig {
	mc := &v1alpha1.ModelConfig{
		ObjectMeta: metav1.ObjectMeta{
			Name:            fmt.Sprintf("%s-model", agentName),
			Namespace:       namespace,
			OwnerReferences: []metav1.OwnerReference{owner},
		},
		Spec: v1alpha1.ModelConfigSpec{
			Provider: model.Provider,
			Model:    model.ModelID,
			Options:  model.Options,
		},
	}
	if secretName != "" {
		mc.Spec.APIKeySecret = secretName
		mc.Spec.APIKeySecretKey = "api-key"
	}
	return mc
}

// buildModelSecret creates the Secret holding an inline model API key.
func buildModelSecret(name, namespace, apiKey string, owner metav1.OwnerReference) *corev1.Secret {
	return &corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{
			Name:            name,
			Namespace:       namespace,
			OwnerReferences: []metav1.OwnerReference{owner},
		},
		Type: corev1.SecretTypeOpaque,
		Data: map[string][]byte{"api-key": []byte(apiKey)},
	}
}

// agentOwnerRef builds a controller owner reference pointing at the Agent.
func agentOwnerRef(agent *v1alpha1.Agent) metav1.OwnerReference {
	t := true
	return metav1.OwnerReference{
		APIVersion:         v1alpha1.GroupVersion.String(),
		Kind:               "Agent",
		Name:               agent.Name,
		UID:                agent.UID,
		Controller:         &t,
		BlockOwnerDeletion: &t,
	}
}

// readRevisions decodes the revision-history annotation.
func readRevisions(meta *metav1.ObjectMeta) []RevisionEntry {
	if meta.Annotations == nil {
		return []RevisionEntry{}
	}
	raw, ok := meta.Annotations[revisionsAnnotation]
	if !ok || raw == "" {
		return []RevisionEntry{}
	}
	var entries []RevisionEntry
	if err := json.Unmarshal([]byte(raw), &entries); err != nil {
		return []RevisionEntry{}
	}
	return entries
}

// maxSnapshotRevisions is the number of most-recent revisions that retain
// their full SpecSnapshot. Older entries have the snapshot stripped to keep the
// annotation well within the etcd 256 KB object-size limit.
const maxSnapshotRevisions = 5

// appendRevision appends an entry to the bounded revision history annotation.
// When spec is non-nil the entry stores a JSON snapshot of the agent spec so
// that rollback can restore it later.
func appendRevision(meta *metav1.ObjectMeta, revision, message string, spec *v1alpha1.AgentSpec) {
	entries := readRevisions(meta)

	entry := RevisionEntry{
		Revision:  revision,
		CreatedAt: time.Now().Format(time.RFC3339),
		Message:   message,
	}
	if spec != nil {
		if raw, err := json.Marshal(spec); err == nil {
			entry.SpecSnapshot = raw
		}
	}
	entries = append(entries, entry)

	if len(entries) > maxRevisionHistory {
		entries = entries[len(entries)-maxRevisionHistory:]
	}

	// Keep snapshots only on the last maxSnapshotRevisions entries to bound
	// annotation size.
	if len(entries) > maxSnapshotRevisions {
		for i := 0; i < len(entries)-maxSnapshotRevisions; i++ {
			entries[i].SpecSnapshot = nil
		}
	}

	encoded, err := json.Marshal(entries)
	if err != nil {
		return
	}
	if meta.Annotations == nil {
		meta.Annotations = map[string]string{}
	}
	meta.Annotations[revisionsAnnotation] = string(encoded)
}

func (s *Server) buildMCPServerFromPush(namespace string, tool *ToolEntry) *v1alpha1.MCPServer {
	protocol := tool.Protocol
	if protocol == "" {
		protocol = "STREAMABLE_HTTP"
	}
	timeout := tool.Timeout
	if timeout == "" {
		timeout = "30s"
	}
	remote := &v1alpha1.RemoteMCPConfig{
		Protocol: protocol,
		URL:      tool.MCPServerURL,
		Timeout:  timeout,
	}
	for _, h := range tool.HeadersFrom {
		kind := h.Kind
		if kind == "" {
			kind = "Secret"
		}
		remote.HeadersFrom = append(remote.HeadersFrom, v1alpha1.HeaderFromRef{
			Kind:   kind,
			Name:   h.Name,
			Key:    h.Key,
			Header: h.Header,
		})
	}
	return &v1alpha1.MCPServer{
		ObjectMeta: metav1.ObjectMeta{
			Name:      tool.MCPServerName,
			Namespace: namespace,
		},
		Spec: v1alpha1.MCPServerSpec{
			Type:   v1alpha1.MCPServerTypeRemote,
			Remote: remote,
		},
	}
}

func (s *Server) rollbackAgent(c *gin.Context) {
	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var req RollbackRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body", Message: err.Error()})
		return
	}

	ctx := c.Request.Context()
	var agent v1alpha1.Agent
	if err := s.client.Get(ctx, types.NamespacedName{Name: name, Namespace: namespace}, &agent); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "agent not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	revisions := readRevisions(&agent.ObjectMeta)
	var target *RevisionEntry
	for i := range revisions {
		if revisions[i].Revision == req.Revision {
			target = &revisions[i]
			break
		}
	}
	if target == nil {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: fmt.Sprintf("revision %q not found in history", req.Revision)})
		return
	}
	if len(target.SpecSnapshot) == 0 {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "revision has no config snapshot"})
		return
	}

	var restoredSpec v1alpha1.AgentSpec
	if err := json.Unmarshal(target.SpecSnapshot, &restoredSpec); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: fmt.Sprintf("decoding spec snapshot: %v", err)})
		return
	}

	// Snapshot current spec before overwriting, then restore.
	newRevision := generateRevision(name, time.Now())
	appendRevision(&agent.ObjectMeta, newRevision, fmt.Sprintf("rollback to %s", req.Revision), &agent.Spec)
	agent.Spec = restoredSpec

	if err := s.client.Update(ctx, &agent); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	agent.Status.Revision = newRevision
	_ = s.client.Status().Update(ctx, &agent)

	c.JSON(http.StatusOK, PushAgentResponse{
		Name:      name,
		Namespace: namespace,
		Type:      string(agent.Spec.Type),
		Revision:  newRevision,
		UpdatedAt: time.Now().Format(time.RFC3339),
		Status:    &AgentStatusBrief{Phase: "RollingBack"},
	})
}

func (s *Server) getRevision(c *gin.Context) {
	name := c.Param("name")
	rev := c.Param("rev")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var agent v1alpha1.Agent
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: name, Namespace: namespace}, &agent); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "agent not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	for _, entry := range readRevisions(&agent.ObjectMeta) {
		if entry.Revision == rev {
			c.JSON(http.StatusOK, entry)
			return
		}
	}

	c.JSON(http.StatusNotFound, ErrorResponse{Error: fmt.Sprintf("revision %q not found", rev)})
}

func parseLimit(c *gin.Context, defaultLimit int) int {
	limitStr := c.DefaultQuery("limit", "")
	if limitStr == "" {
		return defaultLimit
	}
	limit, err := strconv.Atoi(limitStr)
	if err != nil || limit < 0 {
		return defaultLimit
	}
	if limit > 500 {
		return 500
	}
	return limit
}

func generateRevision(name string, t time.Time) string {
	data := fmt.Sprintf("%s-%d", name, t.UnixNano())
	hash := sha256.Sum256([]byte(data))
	return fmt.Sprintf("%x", hash[:4])
}

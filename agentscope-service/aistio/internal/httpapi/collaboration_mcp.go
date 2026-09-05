// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/orchestration"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// The collaboration MCP server is deliberately task-scoped. It gives every
// runtime the same high-level tools without exposing the database, dispatcher,
// or backend-specific APIs. Human workflows continue to use the REST API and
// Console, where object-level authorization has the authenticated user context.
type mcpRequest struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params,omitempty"`
}

type mcpResponse struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Result  any             `json:"result,omitempty"`
	Error   *mcpError       `json:"error,omitempty"`
}

type mcpError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

type mcpTool struct {
	Name        string         `json:"name"`
	Description string         `json:"description"`
	InputSchema map[string]any `json:"inputSchema"`
}

type mcpCallParams struct {
	Name      string         `json:"name"`
	Arguments map[string]any `json:"arguments"`
}

type mcpToolResult struct {
	Content           []map[string]any `json:"content"`
	StructuredContent any              `json:"structuredContent,omitempty"`
	IsError           bool             `json:"isError,omitempty"`
}

func collaborationMCPTools() []mcpTool {
	object := func(properties map[string]any, required ...string) map[string]any {
		schema := map[string]any{"type": "object", "additionalProperties": false, "properties": properties}
		if len(required) > 0 {
			schema["required"] = required
		}
		return schema
	}
	stringProp := map[string]any{"type": "string"}
	mentions := map[string]any{"type": "array", "items": map[string]any{"type": "object", "additionalProperties": false,
		"properties": map[string]any{"type": map[string]any{"type": "string", "enum": []string{"human", "agent", "team"}}, "ref": stringProp},
		"required":   []string{"type", "ref"}}}
	ids := map[string]any{"type": "array", "items": stringProp}
	return []mcpTool{
		{Name: "issue.get", Description: "Read the authoritative Issue for this AgentTask.", InputSchema: object(map[string]any{"issueId": stringProp})},
		{Name: "issue.comment.list", Description: "Read Issue discussion roots, a thread, or its tail.", InputSchema: object(map[string]any{"issueId": stringProp, "rootsOnly": map[string]any{"type": "boolean"}, "threadId": stringProp, "tail": map[string]any{"type": "integer", "minimum": 1, "maximum": 500}})},
		{Name: "issue.comment.add", Description: "Add an attributable Comment and route structured mentions.", InputSchema: object(map[string]any{"content": stringProp, "parentId": stringProp, "type": stringProp, "mentions": mentions}, "content")},
		{Name: "issue.child.create", Description: "Create child work from an active Team leader task.", InputSchema: object(map[string]any{"title": stringProp, "description": stringProp, "priority": stringProp, "assigneeType": stringProp, "assigneeRef": stringProp, "acceptanceCriteria": map[string]any{"type": "object"}}, "title")},
		{Name: "artifact.upload", Description: "Upload base64 bytes into shared artifact storage and link them to this task or Issue.", InputSchema: object(map[string]any{"filename": stringProp, "contentBase64": stringProp, "contentType": stringProp, "targetType": stringProp, "targetRef": stringProp}, "filename", "contentBase64")},
		{Name: "artifact.download", Description: "Download a task-visible Artifact as base64 bytes.", InputSchema: object(map[string]any{"artifactId": stringProp}, "artifactId")},
		{Name: "task.get", Description: "Read this AgentTask and its input states.", InputSchema: object(map[string]any{"taskId": stringProp})},
		{Name: "task.progress", Description: "Write a progress Comment for this AgentTask.", InputSchema: object(map[string]any{"content": stringProp, "mentions": mentions}, "content")},
		{Name: "task.respond", Description: "Write a result Comment attributed to this AgentTask.", InputSchema: object(map[string]any{"content": stringProp, "parentId": stringProp, "mentions": mentions}, "content")},
		{Name: "task.complete", Description: "Complete this AgentTask and reconcile every input.", InputSchema: object(map[string]any{"summary": stringProp, "result": map[string]any{}, "processedInputIds": ids, "deferredInputIds": ids})},
		{Name: "task.fail", Description: "Fail this AgentTask with a durable error code and message.", InputSchema: object(map[string]any{"code": stringProp, "message": stringProp}, "code", "message")},
		{Name: "team.get", Description: "Read the Team roster, roles, instructions, and policy for this task.", InputSchema: object(map[string]any{})},
		{Name: "approval.request", Description: "Request human approval for this Issue, task, or its ExecutionAttempt.", InputSchema: object(map[string]any{"targetType": stringProp, "targetRef": stringProp, "approverRef": stringProp, "reason": stringProp}, "targetType", "targetRef", "approverRef")},
		{Name: "run.get", Description: "Read the OrchestrationRun containing this task.", InputSchema: object(map[string]any{})},
		{Name: "run.graph", Description: "Read the materialized nodes, edges, tasks, and attempts in this run.", InputSchema: object(map[string]any{})},
		{Name: "run.node.complete", Description: "Explicitly complete this Team coordinator node after all delegated work converges.", InputSchema: object(map[string]any{"output": map[string]any{}})},
		{Name: "run.node.fail", Description: "Explicitly fail this Team coordinator node.", InputSchema: object(map[string]any{"code": stringProp, "message": stringProp}, "code", "message")},
		{Name: "run.replan", Description: "Add a dynamic agent or team node to this adaptive run.", InputSchema: object(map[string]any{"key": stringProp, "type": stringProp, "agentId": stringProp, "teamRef": stringProp, "role": stringProp}, "type")},
		{Name: "run.signal", Description: "Deliver an idempotent named signal to this run.", InputSchema: object(map[string]any{"name": stringProp, "idempotencyKey": stringProp, "payload": map[string]any{}}, "name", "idempotencyKey")},
		{Name: "run.artifacts", Description: "List Issue artifacts shared by all runtimes in this run.", InputSchema: object(map[string]any{})},
	}
}

func (s *Server) collaborationMCP(c *gin.Context) {
	task, ok := taskPrincipal(c)
	if !ok {
		c.JSON(http.StatusForbidden, ErrorResponse{Error: "collaboration MCP requires a task-scoped token"})
		return
	}
	var req mcpRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.JSONRPC != "2.0" || req.Method == "" {
		c.JSON(http.StatusOK, mcpResponse{JSONRPC: "2.0", ID: req.ID, Error: &mcpError{Code: -32600, Message: "invalid JSON-RPC request"}})
		return
	}
	if req.Method == "notifications/initialized" {
		c.Status(http.StatusNoContent)
		return
	}
	respond := func(result any, err *mcpError) {
		c.JSON(http.StatusOK, mcpResponse{JSONRPC: "2.0", ID: req.ID, Result: result, Error: err})
	}
	switch req.Method {
	case "initialize":
		respond(map[string]any{"protocolVersion": "2025-06-18", "capabilities": map[string]any{"tools": map[string]any{"listChanged": false}}, "serverInfo": map[string]any{"name": "aistio-collaboration", "version": "1.0.0"}}, nil)
	case "ping":
		respond(map[string]any{}, nil)
	case "tools/list":
		respond(map[string]any{"tools": collaborationMCPTools()}, nil)
	case "tools/call":
		var params mcpCallParams
		if err := json.Unmarshal(req.Params, &params); err != nil || params.Name == "" {
			respond(nil, &mcpError{Code: -32602, Message: "invalid tools/call params"})
			return
		}
		value, err := s.callCollaborationMCPTool(c, task, params.Name, params.Arguments)
		if err != nil {
			respond(mcpResult(map[string]any{"error": err.Error()}, true), nil)
			return
		}
		respond(mcpResult(value, false), nil)
	default:
		respond(nil, &mcpError{Code: -32601, Message: "method not found"})
	}
}

func mcpResult(value any, isError bool) mcpToolResult {
	raw, _ := json.Marshal(value)
	return mcpToolResult{Content: []map[string]any{{"type": "text", "text": string(raw)}}, StructuredContent: value, IsError: isError}
}

func (s *Server) callCollaborationMCPTool(c *gin.Context, task *controlmodel.AgentTask, name string, args map[string]any) (any, error) {
	ctx := c.Request.Context()
	if requested := stringArg(args, "taskId"); requested != "" && requested != task.ID.String() {
		return nil, store.ErrNotFound
	}
	if requested := stringArg(args, "issueId"); requested != "" && requested != task.IssueID.String() {
		return nil, store.ErrNotFound
	}
	actor := controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}
	svc := s.collaborationService()
	switch name {
	case "issue.get":
		issue, err := s.store.Collaboration().GetIssue(ctx, task.IssueID)
		if err != nil {
			return nil, err
		}
		return map[string]any{"issue": issue}, nil
	case "issue.comment.list":
		opts := store.CommentListOptions{Limit: 100, RootsOnly: boolArg(args, "rootsOnly")}
		if value := intArg(args, "tail"); value > 0 {
			opts.Tail = min(value, 500)
		}
		if raw := stringArg(args, "threadId"); raw != "" {
			id, err := uuid.Parse(raw)
			if err != nil {
				return nil, fmt.Errorf("invalid threadId")
			}
			opts.ThreadID = &id
		}
		comments, err := s.store.Collaboration().ListComments(ctx, task.IssueID, opts)
		return map[string]any{"items": comments}, err
	case "issue.comment.add", "task.progress", "task.respond":
		parentID, err := optionalUUIDArg(args, "parentId")
		if err != nil {
			return nil, err
		}
		commentType := controlmodel.CommentType(stringArg(args, "type"))
		if name == "task.progress" {
			commentType = controlmodel.CommentProgress
		} else if name == "task.respond" {
			commentType = controlmodel.CommentResult
		}
		result, err := svc.AddComment(ctx, collaboration.AddCommentRequest{IssueID: task.IssueID, ParentID: parentID,
			Author: actor, Content: stringArg(args, "content"), Type: commentType, Mentions: mentionArgs(args), SourceTaskID: &task.ID})
		return result, err
	case "issue.child.create":
		criteria, _ := json.Marshal(args["acceptanceCriteria"])
		issue, childTask, err := svc.CreateChildFromTask(ctx, task.ID, collaboration.CreateIssueRequest{
			Title: stringArg(args, "title"), Description: stringArg(args, "description"), Priority: stringArg(args, "priority"),
			AssigneeType: controlmodel.AssigneeType(stringArg(args, "assigneeType")), AssigneeRef: stringArg(args, "assigneeRef"), AcceptanceCriteria: criteria})
		return map[string]any{"issue": issue, "agentTask": childTask}, err
	case "artifact.upload":
		return s.uploadMCPArtifact(ctx, task, args)
	case "artifact.download":
		return s.downloadMCPArtifact(ctx, task, args)
	case "task.get":
		current, err := s.store.Collaboration().GetAgentTask(ctx, task.ID)
		return map[string]any{"task": current}, err
	case "task.complete":
		current, err := s.store.Collaboration().GetAgentTask(ctx, task.ID)
		if err != nil {
			return nil, err
		}
		result, _ := json.Marshal(args["result"])
		usage, _ := json.Marshal(args["usage"])
		completion := store.TaskCompletion{ExpectedVersion: current.Version, Summary: stringArg(args, "summary"), Result: result,
			Usage:             usage,
			ProcessedInputIDs: uuidListArg(args, "processedInputIds"), DeferredInputIDs: uuidListArg(args, "deferredInputIds")}
		completed, comment, err := svc.CompleteTask(ctx, task.ID, completion, actor)
		return map[string]any{"task": completed, "comment": comment}, err
	case "task.fail":
		current, err := s.store.Collaboration().GetAgentTask(ctx, task.ID)
		if err != nil {
			return nil, err
		}
		failed, err := svc.FailTask(ctx, task.ID, current.Version, stringArg(args, "code"), stringArg(args, "message"))
		return map[string]any{"task": failed}, err
	case "team.get":
		if task.TeamID == nil {
			return nil, fmt.Errorf("AgentTask has no Team context")
		}
		team, err := svc.TeamForTask(ctx, task)
		return map[string]any{"team": team}, err
	case "approval.request":
		approval := &controlmodel.Approval{Tenant: task.Tenant, Namespace: task.Namespace,
			TargetType: stringArg(args, "targetType"), TargetRef: stringArg(args, "targetRef"), ApproverRef: stringArg(args, "approverRef"),
			Reason: stringArg(args, "reason"), RequestedBy: actor, IssueID: &task.IssueID}
		if approval.TargetType == "" || approval.TargetRef == "" || approval.ApproverRef == "" {
			return nil, fmt.Errorf("targetType, targetRef, and approverRef are required")
		}
		if !s.approvalWithinTaskScope(ctx, task, approval) {
			return nil, fmt.Errorf("approval target is outside task scope")
		}
		created, err := s.store.Collaboration().CreateApproval(ctx, approval)
		return map[string]any{"approval": created}, err
	case "run.get":
		run, err := s.store.Orchestration().GetRun(ctx, task.OrchestrationRunID)
		return map[string]any{"run": run}, err
	case "run.graph":
		graph, err := s.orchestrationService().Graph(ctx, task.OrchestrationRunID)
		return graph, err
	case "run.node.complete":
		output, _ := json.Marshal(args["output"])
		node, err := s.orchestrationService().CompleteCoordinatorNode(ctx, task.ID, output, actor)
		return map[string]any{"node": node}, err
	case "run.node.fail":
		node, err := s.orchestrationService().FailCoordinatorNode(ctx, task.ID,
			stringArg(args, "code"), stringArg(args, "message"), actor)
		return map[string]any{"node": node}, err
	case "run.replan":
		node, err := s.orchestrationService().Replan(ctx, task.ID, orchestration.DefinitionNode{
			Key: stringArg(args, "key"), Type: controlmodel.RunNodeType(stringArg(args, "type")),
			AgentID: stringArg(args, "agentId"), TeamRef: stringArg(args, "teamRef"),
			Role: stringArg(args, "role")}, actor)
		return map[string]any{"node": node}, err
	case "run.signal":
		payload, _ := json.Marshal(args["payload"])
		err := s.orchestrationService().Signal(ctx, task.OrchestrationRunID, stringArg(args, "name"),
			stringArg(args, "idempotencyKey"), payload, actor)
		return map[string]any{"accepted": err == nil}, err
	case "run.artifacts":
		artifacts, err := s.store.Collaboration().ListArtifacts(ctx, task.Tenant, task.Namespace,
			"issue", task.IssueID.String())
		return map[string]any{"artifacts": artifacts}, err
	default:
		return nil, fmt.Errorf("unknown collaboration tool %q", name)
	}
}

func (s *Server) uploadMCPArtifact(ctx context.Context, task *controlmodel.AgentTask, args map[string]any) (any, error) {
	if s.artifactProvider == nil {
		return nil, fmt.Errorf("artifact provider is unavailable")
	}
	filename := strings.TrimSpace(stringArg(args, "filename"))
	if filename == "" || strings.ContainsAny(filename, "/\\") {
		return nil, fmt.Errorf("artifact filename must be a base name")
	}
	content, err := base64.StdEncoding.DecodeString(stringArg(args, "contentBase64"))
	if err != nil {
		return nil, fmt.Errorf("invalid contentBase64")
	}
	const maxArtifactSize = 100 << 20
	if len(content) > maxArtifactSize {
		return nil, fmt.Errorf("artifact exceeds platform size limit")
	}
	contentType := strings.TrimSpace(stringArg(args, "contentType"))
	if contentType == "" {
		contentType = http.DetectContentType(content)
	}
	policy := controlmodel.TeamPolicy{}
	if task.TeamID != nil {
		if team, loadErr := s.collaborationService().TeamForTask(ctx, task); loadErr == nil {
			policy = team.Policy
		}
	}
	if policy.MaxArtifactBytes > 0 && int64(len(content)) > policy.MaxArtifactBytes {
		return nil, fmt.Errorf("artifact exceeds Team policy size limit")
	}
	if len(policy.AllowedArtifactMediaTypes) > 0 && !mediaTypeAllowed(contentType, policy.AllowedArtifactMediaTypes) {
		return nil, fmt.Errorf("artifact media type is blocked by Team policy")
	}
	if strings.HasPrefix(strings.ToLower(contentType), "text/") {
		if err := collaboration.ValidateContentPolicy(policy, string(content)); err != nil {
			return nil, err
		}
	}
	id := uuid.New()
	key := fmt.Sprintf("%s/%s/%s", task.Tenant, task.Namespace, id)
	info, err := s.artifactProvider.Put(ctx, key, bytes.NewReader(content))
	if err != nil {
		return nil, err
	}
	artifact := &controlmodel.Artifact{ID: id, Tenant: task.Tenant, Namespace: task.Namespace,
		StorageProvider: s.artifactProvider.Name(), StorageKey: key, Filename: filename, ContentType: contentType,
		SizeBytes: info.Size, Checksum: info.Checksum, Uploader: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}, SourceTaskID: &task.ID}
	targetType, targetRef := stringArg(args, "targetType"), stringArg(args, "targetRef")
	if targetType == "" {
		targetType, targetRef = "issue", task.IssueID.String()
	}
	links := []controlmodel.ArtifactLink{{TargetType: targetType, TargetRef: targetRef, Relation: "attachment"}}
	created, err := s.store.Collaboration().CreateArtifact(ctx, artifact, links)
	if err != nil {
		_ = s.artifactProvider.Delete(ctx, key)
		return nil, err
	}
	return map[string]any{"artifact": created, "links": links}, nil
}

func (s *Server) downloadMCPArtifact(ctx context.Context, task *controlmodel.AgentTask, args map[string]any) (any, error) {
	id, err := uuid.Parse(stringArg(args, "artifactId"))
	if err != nil {
		return nil, fmt.Errorf("invalid artifactId")
	}
	artifact, links, err := s.store.Collaboration().GetArtifact(ctx, id)
	if err != nil {
		return nil, err
	}
	if !taskCanReadArtifact(task, artifact, links) {
		return nil, store.ErrNotFound
	}
	if artifact.ExpiresAt != nil && artifact.ExpiresAt.Before(time.Now().UTC()) {
		return nil, fmt.Errorf("artifact has expired")
	}
	if s.artifactProvider == nil || artifact.StorageProvider != s.artifactProvider.Name() {
		return nil, fmt.Errorf("artifact provider is unavailable")
	}
	reader, info, err := s.artifactProvider.Open(ctx, artifact.StorageKey)
	if err != nil {
		return nil, err
	}
	defer reader.Close()
	content, err := io.ReadAll(io.LimitReader(reader, 100<<20+1))
	if err != nil {
		return nil, err
	}
	if len(content) > 100<<20 || info.Checksum != artifact.Checksum {
		return nil, fmt.Errorf("artifact integrity validation failed")
	}
	return map[string]any{"artifact": artifact, "contentBase64": base64.StdEncoding.EncodeToString(content)}, nil
}

func stringArg(args map[string]any, key string) string {
	value, _ := args[key].(string)
	return strings.TrimSpace(value)
}

func boolArg(args map[string]any, key string) bool {
	value, _ := args[key].(bool)
	return value
}

func intArg(args map[string]any, key string) int {
	value, _ := args[key].(float64)
	return int(value)
}

func optionalUUIDArg(args map[string]any, key string) (*uuid.UUID, error) {
	if raw := stringArg(args, key); raw != "" {
		id, err := uuid.Parse(raw)
		if err != nil {
			return nil, fmt.Errorf("invalid %s", key)
		}
		return &id, nil
	}
	return nil, nil
}

func uuidListArg(args map[string]any, key string) []uuid.UUID {
	values, _ := args[key].([]any)
	result := make([]uuid.UUID, 0, len(values))
	for _, value := range values {
		if id, err := uuid.Parse(fmt.Sprint(value)); err == nil {
			result = append(result, id)
		}
	}
	return result
}

func mentionArgs(args map[string]any) []collaboration.MentionTarget {
	values, _ := args["mentions"].([]any)
	result := make([]collaboration.MentionTarget, 0, len(values))
	for _, value := range values {
		item, _ := value.(map[string]any)
		result = append(result, collaboration.MentionTarget{Type: controlmodel.AssigneeType(stringArg(item, "type")), Ref: stringArg(item, "ref")})
	}
	return result
}

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
	"sigs.k8s.io/controller-runtime/pkg/log"

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
	acceptanceCriteria := object(map[string]any{
		"requiredResult":   map[string]any{"type": "boolean"},
		"minimumArtifacts": map[string]any{"type": "integer", "minimum": 0},
		"minimumApprovals": map[string]any{"type": "integer", "minimum": 0},
		"checklist": map[string]any{"type": "array", "items": object(map[string]any{
			"id": stringProp, "text": stringProp, "required": map[string]any{"type": "boolean"},
			"satisfied": map[string]any{"type": "boolean"},
		})},
	})
	acceptanceCriteria["description"] = "Optional acceptance criteria object. Omit it when no criteria are needed; never pass a top-level array."
	return []mcpTool{
		{Name: "issue.get", Description: "Read the authoritative Issue for this AgentTask.", InputSchema: object(map[string]any{"issueId": stringProp})},
		{Name: "issue.comment.list", Description: "Read Issue discussion roots, a thread, or its tail.", InputSchema: object(map[string]any{"issueId": stringProp, "rootsOnly": map[string]any{"type": "boolean"}, "threadId": stringProp, "tail": map[string]any{"type": "integer", "minimum": 1, "maximum": 500}})},
		{Name: "issue.comment.add", Description: "Add an attributable Comment and route structured mentions.", InputSchema: object(map[string]any{"content": stringProp, "parentId": stringProp, "type": stringProp, "mentions": mentions}, "content")},
		{Name: "issue.child.create", Description: "Create child work from an active Team leader task. For assigneeType=agent, assigneeRef must be the roster member's agentId from team.get, never the membership id field. acceptanceCriteria is optional and must be an object, never a top-level array.", InputSchema: object(map[string]any{"title": stringProp, "description": stringProp, "priority": stringProp, "assigneeType": stringProp, "assigneeRef": stringProp, "acceptanceCriteria": acceptanceCriteria}, "title")},
		{Name: "issue.accept", Description: "Accept this delegated child Issue from an active Team leader follow-up after its worker result has converged.", InputSchema: object(map[string]any{"reason": stringProp})},
		{Name: "issue.cancel", Description: "Explicitly skip the current blocked delegated child Issue after the Team leader decides a degraded or partial result is acceptable.", InputSchema: object(map[string]any{"reason": stringProp})},
		{Name: "artifact.upload", Description: "Upload base64 bytes into shared artifact storage and link them to this task or Issue.", InputSchema: object(map[string]any{"filename": stringProp, "contentBase64": stringProp, "contentType": stringProp, "targetType": stringProp, "targetRef": stringProp}, "filename", "contentBase64")},
		{Name: "artifact.download", Description: "Download a task-visible Artifact as base64 bytes.", InputSchema: object(map[string]any{"artifactId": stringProp}, "artifactId")},
		{Name: "task.get", Description: "Read this AgentTask and its input states. Omit taskId or use \"current\" for the token-scoped task.", InputSchema: object(map[string]any{"taskId": stringProp})},
		{Name: "task.start", Description: "Acknowledge that execution of this dispatched AgentTask has started.", InputSchema: object(map[string]any{})},
		{Name: "task.progress", Description: "Write a progress Comment for this AgentTask.", InputSchema: object(map[string]any{"content": stringProp, "mentions": mentions}, "content")},
		{Name: "task.respond", Description: "Write the result Comment for this AgentTask. A later task.complete call reuses it instead of publishing a duplicate.", InputSchema: object(map[string]any{"content": stringProp, "parentId": stringProp, "mentions": mentions}, "content")},
		{Name: "task.complete", Description: "Complete this AgentTask, reconcile every input, and reuse any result previously written by task.respond.", InputSchema: object(map[string]any{"summary": stringProp, "result": map[string]any{}, "processedInputIds": ids, "deferredInputIds": ids})},
		{Name: "task.fail", Description: "Fail this AgentTask with a durable error code and message.", InputSchema: object(map[string]any{"code": stringProp, "message": stringProp}, "code", "message")},
		{Name: "team.get", Description: "Read the Team roster, roles, instructions, and policy for this task. Delegate to members[].agentId; members[].id is only the membership record id.", InputSchema: object(map[string]any{})},
		{Name: "approval.request", Description: "Request human approval for this Issue, task, or its ExecutionAttempt.", InputSchema: object(map[string]any{"targetType": stringProp, "targetRef": stringProp, "approverRef": stringProp, "reason": stringProp}, "targetType", "targetRef", "approverRef")},
		{Name: "run.get", Description: "Read the OrchestrationRun containing this task.", InputSchema: object(map[string]any{})},
		{Name: "run.graph", Description: "Read the materialized nodes, edges, tasks, and attempts in this run.", InputSchema: object(map[string]any{})},
		{Name: "run.node.complete", Description: "Conclude this Team leader turn after all delegated work converges. This completes the current leader AgentTask and then the coordinator node; do not call task.complete afterwards.", InputSchema: object(map[string]any{"output": map[string]any{}})},
		{Name: "run.node.fail", Description: "Fail this Team leader turn and its coordinator node as one convergent operation.", InputSchema: object(map[string]any{"code": stringProp, "message": stringProp}, "code", "message")},
		{Name: "run.replan", Description: "Add a dynamic agent or team node to this adaptive run.", InputSchema: object(map[string]any{"key": stringProp, "type": stringProp, "agentId": stringProp, "teamRef": stringProp, "role": stringProp}, "type")},
		{Name: "run.signal", Description: "Deliver an idempotent named signal to this run.", InputSchema: object(map[string]any{"name": stringProp, "idempotencyKey": stringProp, "payload": map[string]any{}}, "name", "idempotencyKey")},
		{Name: "run.artifacts", Description: "List Issue artifacts shared by all runtimes in this run.", InputSchema: object(map[string]any{})},
	}
}

func collaborationMCPToolsForTask(task *controlmodel.AgentTask) []mcpTool {
	tools := collaborationMCPTools()
	if task == nil {
		return nil
	}
	filtered := make([]mcpTool, 0, len(tools))
	for _, tool := range tools {
		switch tool.Name {
		case "team.get":
			if task.TeamID == nil {
				continue
			}
		case "issue.child.create", "issue.accept", "issue.cancel", "run.node.complete", "run.node.fail", "run.replan":
			if task.TeamID == nil || !task.LeaderTask {
				continue
			}
		}
		filtered = append(filtered, tool)
	}
	return filtered
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
		respond(map[string]any{"tools": collaborationMCPToolsForTask(task)}, nil)
	case "tools/call":
		var params mcpCallParams
		if err := json.Unmarshal(req.Params, &params); err != nil || params.Name == "" {
			respond(nil, &mcpError{Code: -32602, Message: "invalid tools/call params"})
			return
		}
		if completedCoordinator, _ := c.Get(ctxCompletedCoordinatorAuth); completedCoordinator == true &&
			params.Name != "run.node.complete" && params.Name != "run.node.fail" {
			restrictionErr := fmt.Errorf("completed coordinator token is restricted to the final node transition")
			s.recordMCPToolFailure(c.Request.Context(), task, params.Name,
				stringArg(params.Arguments, "_toolCallId"), restrictionErr)
			respond(mcpResult(map[string]any{"error": restrictionErr.Error()}, true), nil)
			return
		}
		value, err := s.callCollaborationMCPTool(c, task, params.Name, params.Arguments)
		if err != nil {
			s.recordMCPToolFailure(c.Request.Context(), task, params.Name,
				stringArg(params.Arguments, "_toolCallId"), err)
			respond(mcpResult(map[string]any{"error": err.Error()}, true), nil)
			return
		}
		respond(mcpResult(value, false), nil)
	default:
		respond(nil, &mcpError{Code: -32601, Message: "method not found"})
	}
}

func (s *Server) recordMCPToolFailure(ctx context.Context, task *controlmodel.AgentTask,
	toolName, toolCallID string, cause error) {
	if task == nil || cause == nil {
		return
	}
	logger := log.FromContext(ctx).WithName("collaboration-mcp").WithValues(
		"runId", task.OrchestrationRunID, "taskId", task.ID, "attemptId", task.CurrentAttemptID,
		"tool", toolName, "toolCallId", toolCallID)
	logger.Error(cause, "Agent collaboration tool failed")
	payloadValues := map[string]any{"toolName": toolName, "toolCallId": toolCallID,
		"state": "error", "message": cause.Error(), "source": "collaboration_mcp"}
	if task.CurrentAttemptID != nil {
		if attempt, attemptErr := s.store.ExecutionAttempts().Get(ctx, *task.CurrentAttemptID); attemptErr == nil {
			payloadValues["sessionId"] = attempt.SessionID
			sessions, sessionErr := s.store.Sessions().List(ctx, store.SessionFilter{
				Tenant: task.Tenant, Namespace: task.Namespace, AgentID: attempt.AgentID,
				SessionID: attempt.SessionID, AgentTaskID: task.ID, Limit: 1,
			})
			if sessionErr == nil && len(sessions) > 0 {
				payloadValues["sessionRef"] = sessions[0].ID
			}
		}
	}
	payload, err := json.Marshal(payloadValues)
	if err != nil {
		return
	}
	idempotencyKey := ""
	if toolCallID != "" {
		idempotencyKey = fmt.Sprintf("agent-tool-failed:%s:%s", task.ID, toolCallID)
	}
	if _, err = s.store.Orchestration().AppendRunEvent(ctx, &controlmodel.RunEvent{
		RunID: task.OrchestrationRunID, Tenant: task.Tenant, Namespace: task.Namespace,
		NodeID: &task.RunNodeID, AgentTaskID: &task.ID, AttemptID: task.CurrentAttemptID,
		Type: "agent_tool.failed", Actor: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef},
		Payload: payload, CausationID: toolCallID, CorrelationID: task.CorrelationID,
		IdempotencyKey: idempotencyKey,
	}); err != nil {
		logger.Error(err, "failed to persist Agent tool diagnostic")
	}
}

func mcpResult(value any, isError bool) mcpToolResult {
	raw, _ := json.Marshal(value)
	return mcpToolResult{Content: []map[string]any{{"type": "text", "text": string(raw)}}, StructuredContent: value, IsError: isError}
}

func (s *Server) callCollaborationMCPTool(c *gin.Context, task *controlmodel.AgentTask, name string, args map[string]any) (any, error) {
	ctx := c.Request.Context()
	if requested := stringArg(args, "taskId"); requested != "" && requested != "current" && requested != task.ID.String() {
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
			Author: actor, Content: stringArg(args, "content"), Type: commentType, Mentions: mentionArgs(args), SourceTaskID: &task.ID,
			SuppressImplicitRouting: name == "task.progress"})
		return result, err
	case "issue.child.create":
		var criteria json.RawMessage
		if rawCriteria, ok := args["acceptanceCriteria"]; ok && rawCriteria != nil {
			encoded, marshalErr := json.Marshal(rawCriteria)
			if marshalErr != nil {
				return nil, fmt.Errorf("invalid acceptanceCriteria: %w", marshalErr)
			}
			criteria = encoded
		}
		issue, childTask, err := svc.CreateChildFromTask(ctx, task.ID, collaboration.CreateIssueRequest{
			Title: stringArg(args, "title"), Description: stringArg(args, "description"), Priority: stringArg(args, "priority"),
			AssigneeType: controlmodel.AssigneeType(stringArg(args, "assigneeType")), AssigneeRef: stringArg(args, "assigneeRef"), AcceptanceCriteria: criteria})
		return map[string]any{"issue": issue, "agentTask": childTask}, err
	case "issue.accept":
		issue, err := svc.AcceptIssueFromTask(ctx, task.ID, stringArg(args, "reason"))
		return map[string]any{"issue": issue}, err
	case "issue.cancel":
		issue, err := svc.CancelBlockedIssueFromTask(ctx, task.ID, stringArg(args, "reason"))
		return map[string]any{"issue": issue}, err
	case "artifact.upload":
		return s.uploadMCPArtifact(ctx, task, args)
	case "artifact.download":
		return s.downloadMCPArtifact(ctx, task, args)
	case "task.get":
		current, err := s.store.Collaboration().GetAgentTask(ctx, task.ID)
		return map[string]any{"task": current}, err
	case "task.start":
		current, err := s.store.Collaboration().GetAgentTask(ctx, task.ID)
		if err != nil {
			return nil, err
		}
		if current.Status == controlmodel.AgentTaskRunning {
			return map[string]any{"task": current}, nil
		}
		started, err := s.store.Collaboration().StartAgentTask(ctx, task.ID, current.Version)
		return map[string]any{"task": started}, err
	case "task.complete":
		current, err := s.store.Collaboration().GetAgentTask(ctx, task.ID)
		if err != nil {
			return nil, err
		}
		if err = s.validateMCPTeamLeaderCompletion(ctx, current); err != nil {
			return nil, err
		}
		result, _ := json.Marshal(args["result"])
		usage, _ := json.Marshal(args["usage"])
		completion := store.TaskCompletion{ExpectedVersion: current.Version, Summary: stringArg(args, "summary"), Result: result,
			Usage:             usage,
			ProcessedInputIDs: uuidListArg(args, "processedInputIds"), DeferredInputIDs: uuidListArg(args, "deferredInputIds")}
		completed, comment, err := svc.CompleteTask(ctx, task.ID, completion, actor)
		projectionErr := s.projectMCPTaskTerminal(ctx, completed, comment)
		if err != nil {
			return map[string]any{"task": completed, "comment": comment}, err
		}
		if projectionErr != nil {
			return map[string]any{"task": completed, "comment": comment}, projectionErr
		}
		return map[string]any{"task": completed, "comment": comment}, nil
	case "task.fail":
		current, err := s.store.Collaboration().GetAgentTask(ctx, task.ID)
		if err != nil {
			return nil, err
		}
		failed, err := svc.FailTask(ctx, task.ID, current.Version, stringArg(args, "code"), stringArg(args, "message"))
		if err == nil {
			err = (&orchestration.Engine{Store: s.store}).ReconcileRun(context.WithoutCancel(ctx), failed.OrchestrationRunID)
		}
		projectionErr := s.projectMCPTaskTerminal(ctx, failed, nil)
		if err != nil {
			return map[string]any{"task": failed}, err
		}
		if projectionErr != nil {
			return map[string]any{"task": failed}, projectionErr
		}
		return map[string]any{"task": failed}, nil
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
		completed, node, err := s.concludeCoordinator(ctx, task, output, actor)
		return map[string]any{"task": completed, "node": node}, err
	case "run.node.fail":
		failed, node, err := s.failCoordinator(ctx, task,
			stringArg(args, "code"), stringArg(args, "message"), actor)
		return map[string]any{"task": failed, "node": node}, err
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

func (s *Server) validateMCPTeamLeaderCompletion(ctx context.Context, task *controlmodel.AgentTask) error {
	if task == nil || !task.LeaderTask || task.TeamID == nil {
		return nil
	}
	node, err := s.store.Orchestration().GetNode(ctx, task.RunNodeID)
	if err != nil {
		return err
	}
	if controlmodel.IsRunNodeTerminal(node.State) {
		return nil
	}
	// A follow-up is one coordinator decision turn, not the coordinator itself.
	// It may finish after accepting/rejecting one result while sibling work is
	// still active; a later worker outcome will enqueue the next follow-up.
	if task.ParentTaskID != nil {
		return nil
	}
	tasks, err := s.store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{
		Tenant: task.Tenant, Namespace: task.Namespace, RunID: task.OrchestrationRunID, Limit: 1000,
	})
	if err != nil {
		return err
	}
	for _, candidate := range tasks {
		if candidate.ParentTaskID != nil && *candidate.ParentTaskID == task.ID {
			return nil
		}
	}
	return fmt.Errorf("Team leader must create delegated work or complete its coordinator node before completing the AgentTask")
}

func (s *Server) projectMCPTaskTerminal(ctx context.Context, task *controlmodel.AgentTask,
	comment *controlmodel.Comment) error {
	if task == nil || task.CurrentAttemptID == nil {
		return nil
	}
	// The Runtime Host may cancel the provider as soon as it observes the
	// terminal Attempt. Keep the post-commit projection alive if that closes the
	// MCP request while the response is still being persisted.
	projectionCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
	defer cancel()
	attempt, err := s.store.ExecutionAttempts().Get(projectionCtx, *task.CurrentAttemptID)
	if err != nil {
		return err
	}
	output := ""
	if comment != nil {
		output = comment.Content
	}
	// MCP completion terminalizes the Attempt before the provider can flush its
	// final event. Project the durable task response into Chat synchronously.
	return s.projectHostedAttemptTerminalWithOutput(projectionCtx, attempt, output)
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

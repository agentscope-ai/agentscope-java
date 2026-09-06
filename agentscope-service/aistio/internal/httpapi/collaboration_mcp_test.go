// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/artifact"
	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestCollaborationMCPToolCatalogFollowsTaskRole(t *testing.T) {
	teamID := uuid.New()
	tests := []struct {
		name          string
		task          *controlmodel.AgentTask
		wantTeam      bool
		wantLeaderOps bool
	}{
		{name: "standalone", task: &controlmodel.AgentTask{}},
		{name: "team worker", task: &controlmodel.AgentTask{TeamID: &teamID}, wantTeam: true},
		{name: "team leader", task: &controlmodel.AgentTask{TeamID: &teamID, LeaderTask: true}, wantTeam: true, wantLeaderOps: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			names := map[string]bool{}
			for _, tool := range collaborationMCPToolsForTask(tt.task) {
				names[tool.Name] = true
			}
			if names["team.get"] != tt.wantTeam {
				t.Fatalf("team.get visibility=%v, want %v", names["team.get"], tt.wantTeam)
			}
			for _, name := range []string{"issue.child.create", "issue.accept", "run.node.complete", "run.node.fail", "run.replan"} {
				if names[name] != tt.wantLeaderOps {
					t.Fatalf("%s visibility=%v, want %v", name, names[name], tt.wantLeaderOps)
				}
			}
			if !names["issue.get"] || !names["task.complete"] {
				t.Fatalf("base tools missing: %+v", names)
			}
		})
	}
}

func TestCollaborationMCPIsTaskScopedAndUsesDomainServices(t *testing.T) {
	gin.SetMode(gin.TestMode)
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	creator := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "tenant-a", Namespace: "default",
		Title: "MCP work", Creator: creator, AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker"})
	if err != nil {
		t.Fatal(err)
	}
	other, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "tenant-a", Namespace: "default", Title: "private", Creator: creator,
		AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker-b"})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("task setup: %+v %v", tasks, err)
	}
	provider := &artifact.LocalProvider{Root: t.TempDir()}
	srv := NewServer(ServerOptions{Store: st, AuthToken: "human-token", TaskTokenSecret: "0123456789abcdef0123456789abcdef",
		ArtifactProvider: provider})
	token, err := srv.taskTokens.Mint(tasks[0].ID, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	callWithToken := func(taskToken, method string, params any) mcpResponse {
		t.Helper()
		body, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": 1, "method": method, "params": params})
		req := httptest.NewRequest(http.MethodPost, "/mcp/collaboration", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Agent-Task-Token", taskToken)
		w := httptest.NewRecorder()
		srv.router.ServeHTTP(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("%s: HTTP %d: %s", method, w.Code, w.Body.String())
		}
		var response mcpResponse
		if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
			t.Fatal(err)
		}
		return response
	}
	call := func(method string, params any) mcpResponse { return callWithToken(token, method, params) }

	listed := call("tools/list", map[string]any{})
	encoded, _ := json.Marshal(listed.Result)
	if !bytes.Contains(encoded, []byte(`"issue.comment.add"`)) || !bytes.Contains(encoded, []byte(`"artifact.upload"`)) ||
		!bytes.Contains(encoded, []byte(`"task.start"`)) {
		t.Fatalf("incomplete MCP tool catalog: %s", encoded)
	}
	if bytes.Contains(encoded, []byte(`"issue.child.create"`)) || bytes.Contains(encoded, []byte(`"run.node.complete"`)) {
		t.Fatalf("standalone worker received Team leader tools: %s", encoded)
	}
	bearerBody := bytes.NewBufferString(`{"jsonrpc":"2.0","id":1,"method":"tools/list"}`)
	bearerReq := httptest.NewRequest(http.MethodPost, "/mcp/collaboration", bearerBody)
	bearerReq.Header.Set("Authorization", "Bearer "+token)
	bearerReq.Header.Set("Content-Type", "application/json")
	bearerResponse := httptest.NewRecorder()
	srv.router.ServeHTTP(bearerResponse, bearerReq)
	if bearerResponse.Code != http.StatusOK || !bytes.Contains(bearerResponse.Body.Bytes(), []byte(`"issue.get"`)) {
		t.Fatalf("task-scoped bearer MCP access failed: status=%d body=%s", bearerResponse.Code, bearerResponse.Body.String())
	}
	read := call("tools/call", map[string]any{"name": "issue.get", "arguments": map[string]any{"issueId": issue.ID.String()}})
	if read.Error != nil {
		t.Fatalf("issue.get RPC error: %+v", read.Error)
	}
	blocked := call("tools/call", map[string]any{"name": "issue.get", "arguments": map[string]any{"issueId": other.ID.String()}})
	blockedJSON, _ := json.Marshal(blocked.Result)
	if !bytes.Contains(blockedJSON, []byte(`"isError":true`)) {
		t.Fatalf("cross-Issue MCP call was not blocked: %s", blockedJSON)
	}
	created := call("tools/call", map[string]any{"name": "issue.comment.add", "arguments": map[string]any{"content": "durable MCP reply"}})
	createdJSON, _ := json.Marshal(created.Result)
	if bytes.Contains(createdJSON, []byte(`"isError":true`)) {
		t.Fatalf("comment add failed: %s", createdJSON)
	}
	comments, err := st.Collaboration().ListComments(ctx, issue.ID, store.CommentListOptions{Limit: 10})
	if err != nil || len(comments) != 1 || comments[0].SourceTaskID == nil || *comments[0].SourceTaskID != tasks[0].ID {
		t.Fatalf("MCP comment attribution: %+v %v", comments, err)
	}

	uploaded := call("tools/call", map[string]any{"name": "artifact.upload", "arguments": map[string]any{
		"filename": "result.txt", "contentType": "text/plain", "contentBase64": base64.StdEncoding.EncodeToString([]byte("shared result")),
	}})
	uploadedJSON, _ := json.Marshal(uploaded.Result)
	if bytes.Contains(uploadedJSON, []byte(`"isError":true`)) || !bytes.Contains(uploadedJSON, []byte(`"result.txt"`)) {
		t.Fatalf("artifact upload failed: %s", uploadedJSON)
	}
	var uploadEnvelope struct {
		StructuredContent struct {
			Artifact controlmodel.Artifact `json:"artifact"`
		} `json:"structuredContent"`
	}
	if err := json.Unmarshal(uploadedJSON, &uploadEnvelope); err != nil || uploadEnvelope.StructuredContent.Artifact.ID == uuid.Nil {
		t.Fatalf("decode uploaded artifact: envelope=%+v err=%v", uploadEnvelope, err)
	}

	otherTasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: other.ID, Limit: 2})
	if err != nil || len(otherTasks) != 1 {
		t.Fatalf("other task setup: %+v %v", otherTasks, err)
	}
	otherToken, err := srv.taskTokens.Mint(otherTasks[0].ID, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	crossTask := callWithToken(otherToken, "tools/call", map[string]any{"name": "artifact.download", "arguments": map[string]any{
		"artifactId": uploadEnvelope.StructuredContent.Artifact.ID.String(),
	}})
	crossTaskJSON, _ := json.Marshal(crossTask.Result)
	if !bytes.Contains(crossTaskJSON, []byte(`"isError":true`)) {
		t.Fatalf("cross-task artifact read was not blocked: %s", crossTaskJSON)
	}

	createArtifact := func(checksum string, expiresAt *time.Time) uuid.UUID {
		t.Helper()
		id := uuid.New()
		key := "tenant-a/default/" + id.String()
		info, err := provider.Put(ctx, key, bytes.NewReader([]byte("protected result")))
		if err != nil {
			t.Fatal(err)
		}
		if checksum == "" {
			checksum = info.Checksum
		}
		created, err := st.Collaboration().CreateArtifact(ctx, &controlmodel.Artifact{
			ID: id, Tenant: "tenant-a", Namespace: "default", StorageProvider: provider.Name(), StorageKey: key,
			Filename: "protected.txt", ContentType: "text/plain", SizeBytes: info.Size, Checksum: checksum,
			Uploader: creator, ExpiresAt: expiresAt,
		}, []controlmodel.ArtifactLink{{TargetType: "issue", TargetRef: issue.ID.String(), Relation: "attachment"}})
		if err != nil {
			t.Fatal(err)
		}
		return created.ID
	}
	expiredAt := time.Now().UTC().Add(-time.Minute)
	expiredID := createArtifact("", &expiredAt)
	expired := call("tools/call", map[string]any{"name": "artifact.download", "arguments": map[string]any{"artifactId": expiredID.String()}})
	expiredJSON, _ := json.Marshal(expired.Result)
	if !bytes.Contains(expiredJSON, []byte(`"isError":true`)) || !bytes.Contains(expiredJSON, []byte(`expired`)) {
		t.Fatalf("expired artifact read was not blocked: %s", expiredJSON)
	}

	corruptID := createArtifact("sha256:not-the-content-checksum", nil)
	corrupt := call("tools/call", map[string]any{"name": "artifact.download", "arguments": map[string]any{"artifactId": corruptID.String()}})
	corruptJSON, _ := json.Marshal(corrupt.Result)
	if !bytes.Contains(corruptJSON, []byte(`"isError":true`)) || !bytes.Contains(corruptJSON, []byte(`integrity`)) {
		t.Fatalf("artifact checksum mismatch was not blocked: %s", corruptJSON)
	}
}

func TestCollaborationMCPCompletedLeaderTokenOnlyFinalizesCoordinator(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant-a", Namespace: "default", Name: "finalizers", LeaderAgentRef: "leader",
	})
	if err != nil {
		t.Fatal(err)
	}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: team.Tenant, Namespace: team.Namespace, Title: "explicit finish",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String(),
	})
	if err != nil {
		t.Fatal(err)
	}
	tasks, _ := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
	claimed, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
			State: controlmodel.ExecutionAssigned})
	if err != nil {
		t.Fatal(err)
	}
	running, err := st.Collaboration().StartAgentTask(ctx, claimed.ID, claimed.Version)
	if err != nil {
		t.Fatal(err)
	}
	srv := NewServer(ServerOptions{Store: st, TaskTokenSecret: "0123456789abcdef0123456789abcdef"})
	token, err := srv.taskTokens.MintScoped(running.ID, attempt.ID, attempt.DispatchGeneration, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = (&collaboration.Service{Store: st}).CompleteTask(ctx, running.ID,
		store.TaskCompletion{ExpectedVersion: running.Version, Summary: "leader result"},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	call := func(name string) mcpResponse {
		t.Helper()
		body, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/call",
			"params": map[string]any{"name": name, "arguments": map[string]any{"output": map[string]any{"ok": true}}}})
		req := httptest.NewRequest(http.MethodPost, "/mcp/collaboration", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Agent-Task-Token", token)
		response := httptest.NewRecorder()
		srv.router.ServeHTTP(response, req)
		if response.Code != http.StatusOK {
			t.Fatalf("%s: status=%d body=%s", name, response.Code, response.Body.String())
		}
		var value mcpResponse
		if err := json.Unmarshal(response.Body.Bytes(), &value); err != nil {
			t.Fatal(err)
		}
		return value
	}
	blocked, _ := json.Marshal(call("issue.get").Result)
	if !bytes.Contains(blocked, []byte(`"isError":true`)) || !bytes.Contains(blocked, []byte(`restricted`)) {
		t.Fatalf("completed token accessed non-final tool: %s", blocked)
	}
	completed, _ := json.Marshal(call("run.node.complete").Result)
	if bytes.Contains(completed, []byte(`"isError":true`)) {
		t.Fatalf("completed leader could not finalize coordinator: %s", completed)
	}
	node, _ := st.Orchestration().GetNode(ctx, running.RunNodeID)
	if node.State != controlmodel.RunNodeSucceeded {
		t.Fatalf("coordinator state=%s", node.State)
	}
}

func TestCollaborationMCPRejectsNonTaskCredentials(t *testing.T) {
	gin.SetMode(gin.TestMode)
	st, err := store.Open(context.Background(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	srv := NewServer(ServerOptions{Store: st, AuthToken: "human-token"})
	body := bytes.NewBufferString(`{"jsonrpc":"2.0","id":1,"method":"tools/list"}`)
	req := httptest.NewRequest(http.MethodPost, "/mcp/collaboration", body)
	req.Header.Set("Authorization", "Bearer human-token")
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	srv.router.ServeHTTP(w, req)
	if w.Code != http.StatusForbidden {
		t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
	}
}

func TestMCPArtifactUploadEnforcesTeamSizeAndMediaPolicy(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant-a", Namespace: "default", Name: "bounded", LeaderAgentRef: "leader",
		Policy: controlmodel.TeamPolicy{MaxArtifactBytes: 4, AllowedArtifactMediaTypes: []string{"text/*"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "default", Title: "bounded artifacts",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String(),
	})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("task setup: %+v %v", tasks, err)
	}
	srv := NewServer(ServerOptions{Store: st, ArtifactProvider: &artifact.LocalProvider{Root: t.TempDir()}})
	upload := func(content, contentType string) error {
		_, err := srv.uploadMCPArtifact(ctx, tasks[0], map[string]any{
			"filename": "result.txt", "contentType": contentType,
			"contentBase64": base64.StdEncoding.EncodeToString([]byte(content)),
		})
		return err
	}
	if err := upload("12345", "text/plain"); err == nil || !bytes.Contains([]byte(err.Error()), []byte("size limit")) {
		t.Fatalf("expected Team size policy rejection, got %v", err)
	}
	if err := upload("{}", "application/json"); err == nil || !bytes.Contains([]byte(err.Error()), []byte("media type")) {
		t.Fatalf("expected Team media policy rejection, got %v", err)
	}
	if err := upload("ok", "text/plain; charset=utf-8"); err != nil {
		t.Fatalf("expected matching text wildcard to pass, got %v", err)
	}
}

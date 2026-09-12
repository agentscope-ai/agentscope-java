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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/artifact"
	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/features"
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
		wantRespond   bool
	}{
		{name: "standalone", task: &controlmodel.AgentTask{}, wantRespond: true},
		{name: "team worker", task: &controlmodel.AgentTask{TeamID: &teamID}, wantTeam: true},
		{name: "team leader", task: &controlmodel.AgentTask{TeamID: &teamID, LeaderTask: true}, wantTeam: true, wantLeaderOps: true, wantRespond: true},
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
			if names["task.respond"] != tt.wantRespond {
				t.Fatalf("task.respond visibility=%v, want %v", names["task.respond"], tt.wantRespond)
			}
		})
	}
}

func TestCollaborationMCPRejectsTeamWorkerRespondFromStaleClient(t *testing.T) {
	teamID := uuid.New()
	context, _ := gin.CreateTestContext(httptest.NewRecorder())
	context.Request = httptest.NewRequest(http.MethodPost, "/mcp/collaboration", nil)
	_, err := (&Server{}).callCollaborationMCPTool(context, &controlmodel.AgentTask{
		TeamID: &teamID,
	}, "task.respond", map[string]any{"content": "interim acknowledgement"})
	if err == nil || !strings.Contains(err.Error(), "task.complete") {
		t.Fatalf("expected Team worker task.respond rejection, got %v", err)
	}
}

func TestBlockedLeaderFollowUpRequiresDurableDecisionOrHumanNotification(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant", Namespace: "default", Name: "decision", LeaderAgentRef: "leader",
		Policy: controlmodel.TeamPolicy{MaxChildDepth: 4, MaxChildIssues: 4},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.Collaboration().AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{
		TeamID: team.ID, AgentRef: "worker", Role: "researcher",
	}); err != nil {
		t.Fatal(err)
	}
	svc := &collaboration.Service{Store: st}
	_, leader, err := svc.CreateIssue(ctx, collaboration.CreateIssueRequest{
		Tenant: "tenant", Namespace: "default", Title: "coordinate",
		Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String(),
	})
	if err != nil {
		t.Fatal(err)
	}
	leader, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: leader.ID, ExpectedVersion: leader.Version})
	if err == nil {
		leader, err = st.Collaboration().StartAgentTask(ctx, leader.ID, leader.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	child, worker, err := svc.CreateChildFromTask(ctx, leader.ID, collaboration.CreateIssueRequest{
		Title: "research", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker",
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = svc.CompleteTask(ctx, leader.ID, store.TaskCompletion{ExpectedVersion: leader.Version,
		Summary: "delegated"}, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	worker, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: worker.ID, ExpectedVersion: worker.Version})
	if err == nil {
		worker, err = st.Collaboration().StartAgentTask(ctx, worker.ID, worker.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	worker, err = svc.FailTask(ctx, worker.ID, worker.Version, "missing_key", "credential unavailable")
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = svc.ConvergeFailedWorker(ctx, worker.ID); err != nil {
		t.Fatal(err)
	}
	followUps, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{
		IssueID: child.ID, AgentRef: "leader", Status: controlmodel.AgentTaskQueued, Limit: 10,
	})
	if err != nil || len(followUps) != 1 {
		t.Fatalf("leader follow-up missing: tasks=%+v err=%v", followUps, err)
	}
	followUp, err := st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{
		TaskID: followUps[0].ID, ExpectedVersion: followUps[0].Version,
	})
	if err == nil {
		followUp, err = st.Collaboration().StartAgentTask(ctx, followUp.ID, followUp.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	srv := NewServer(ServerOptions{Store: st})
	if err = srv.validateMCPTeamLeaderCompletion(ctx, followUp); err == nil ||
		!strings.Contains(err.Error(), "worker has finished") {
		t.Fatalf("blocked follow-up completed without a durable decision: %v", err)
	}
	notification, err := svc.AddComment(ctx, collaboration.AddCommentRequest{IssueID: child.ID,
		Author:  controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"},
		Content: "please provide the credential", Type: controlmodel.CommentStatus,
		Mentions:     []collaboration.MentionTarget{{Type: controlmodel.AssigneeHuman, Ref: "owner"}},
		SourceTaskID: &followUp.ID})
	if err != nil {
		t.Fatal(err)
	}
	progress, err := svc.AddComment(ctx, collaboration.AddCommentRequest{IssueID: child.ID,
		Author:  controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"},
		Content: "still waiting for the credential", Type: controlmodel.CommentProgress,
		SourceTaskID: &followUp.ID})
	if err != nil || progress.Comment.ID != notification.Comment.ID {
		t.Fatalf("leader decision status was duplicated: first=%+v progress=%+v err=%v", notification, progress, err)
	}
	response, err := svc.AddComment(ctx, collaboration.AddCommentRequest{IssueID: child.ID,
		Author:  controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"},
		Content: "waiting for the human response", Type: controlmodel.CommentResult,
		Mentions:     []collaboration.MentionTarget{{Type: controlmodel.AssigneeHuman, Ref: "owner"}},
		SourceTaskID: &followUp.ID})
	if err != nil || response.Comment.ID != notification.Comment.ID {
		t.Fatalf("leader human-wait result was duplicated: first=%+v response=%+v err=%v", notification, response, err)
	}
	if err = srv.validateMCPTeamLeaderCompletion(ctx, followUp); err != nil {
		t.Fatalf("explicit human notification did not satisfy waiting decision: %v", err)
	}
	completed, reused, err := svc.CompleteTask(ctx, followUp.ID, store.TaskCompletion{
		ExpectedVersion: followUp.Version, Summary: "waiting for human"},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"})
	if err != nil || completed.Status != controlmodel.AgentTaskCompleted || reused == nil ||
		reused.ID != notification.Comment.ID {
		t.Fatalf("human notification was not reused on completion: task=%+v comment=%+v err=%v", completed, reused, err)
	}
	comments, err := st.Collaboration().ListComments(ctx, child.ID, store.CommentListOptions{Limit: 20})
	if err != nil {
		t.Fatal(err)
	}
	decisionComments := 0
	for _, comment := range comments {
		if comment.SourceTaskID != nil && *comment.SourceTaskID == followUp.ID {
			decisionComments++
		}
	}
	if decisionComments != 1 {
		t.Fatalf("leader decision comments=%d, want 1: %+v", decisionComments, comments)
	}
}

func TestChildCreateToolDescribesAcceptanceCriteriaObject(t *testing.T) {
	var childTool *mcpTool
	tools := collaborationMCPTools()
	for i := range tools {
		if tools[i].Name == "issue.child.create" {
			childTool = &tools[i]
			break
		}
	}
	if childTool == nil {
		t.Fatal("issue.child.create tool is missing")
	}
	properties, ok := childTool.InputSchema["properties"].(map[string]any)
	if !ok {
		t.Fatalf("child input properties=%T", childTool.InputSchema["properties"])
	}
	criteria, ok := properties["acceptanceCriteria"].(map[string]any)
	if !ok || criteria["type"] != "object" || !strings.Contains(childTool.Description, "never a top-level array") {
		t.Fatalf("acceptanceCriteria schema is ambiguous: tool=%+v", childTool)
	}
	criteriaProperties, ok := criteria["properties"].(map[string]any)
	if !ok {
		t.Fatalf("acceptanceCriteria properties=%T", criteria["properties"])
	}
	checklist, ok := criteriaProperties["checklist"].(map[string]any)
	if !ok || checklist["type"] != "array" {
		t.Fatalf("acceptanceCriteria checklist schema=%+v", checklist)
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
	currentTask := call("tools/call", map[string]any{"name": "task.get", "arguments": map[string]any{"taskId": "current"}})
	currentTaskJSON, _ := json.Marshal(currentTask.Result)
	if bytes.Contains(currentTaskJSON, []byte(`"isError":true`)) || !bytes.Contains(currentTaskJSON, []byte(tasks[0].ID.String())) {
		t.Fatalf("task.get current did not resolve the token-scoped task: %s", currentTaskJSON)
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
	expired := call("tools/call", map[string]any{"name": "artifact.download", "arguments": map[string]any{
		"artifactId": expiredID.String(), "_toolCallId": "call-expired-artifact",
	}})
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
	runEvents, err := st.Orchestration().ListRunEvents(ctx, tasks[0].OrchestrationRunID, 0, 100)
	if err != nil {
		t.Fatal(err)
	}
	foundToolFailure := false
	for _, event := range runEvents {
		if event.Type == "agent_tool.failed" && event.CausationID == "call-expired-artifact" &&
			bytes.Contains(event.Payload, []byte(`"source":"collaboration_mcp"`)) {
			foundToolFailure = true
		}
	}
	if !foundToolFailure {
		t.Fatalf("MCP tool failure was not projected into Run events: %+v", runEvents)
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
	if _, err = st.Collaboration().AddTeamMember(ctx, &controlmodel.CollaborationTeamMember{
		TeamID: team.ID, AgentRef: "worker", Role: "implementer",
	}); err != nil {
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
	svc := &collaboration.Service{Store: st}
	childIssue, childTask, err := svc.CreateChildFromTask(ctx, running.ID, collaboration.CreateIssueRequest{
		Title: "delegated work", AssigneeType: controlmodel.AssigneeAgent, AssigneeRef: "worker",
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = svc.CompleteTask(ctx, running.ID,
		store.TaskCompletion{ExpectedVersion: running.Version, Summary: "leader result"},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "leader"}); err != nil {
		t.Fatal(err)
	}
	childTask, err = st.Collaboration().ClaimAgentTask(ctx,
		store.TaskClaim{TaskID: childTask.ID, ExpectedVersion: childTask.Version})
	if err == nil {
		childTask, err = st.Collaboration().StartAgentTask(ctx, childTask.ID, childTask.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	_, childResult, err := svc.CompleteTask(ctx, childTask.ID,
		store.TaskCompletion{ExpectedVersion: childTask.Version, Summary: "worker result"},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "worker"})
	if err != nil {
		t.Fatal(err)
	}
	followUps, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{
		IssueID: childIssue.ID, AgentRef: "leader", Limit: 10,
	})
	if err != nil {
		t.Fatal(err)
	}
	var followUp *controlmodel.AgentTask
	for _, candidate := range followUps {
		if candidate.TriggerCommentID != nil && *candidate.TriggerCommentID == childResult.ID {
			followUp = candidate
			break
		}
	}
	if followUp == nil {
		t.Fatal("worker result did not create a leader follow-up task")
	}
	followUp, followAttempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: followUp.ID, ExpectedVersion: followUp.Version},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
			State: controlmodel.ExecutionAssigned})
	if err == nil {
		followUp, err = st.Collaboration().StartAgentTask(ctx, followUp.ID, followUp.Version)
	}
	if err != nil {
		t.Fatal(err)
	}
	token, err = srv.taskTokens.MintScoped(followUp.ID, followAttempt.ID,
		followAttempt.DispatchGeneration, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	if _, err = svc.AcceptIssueFromTask(ctx, followUp.ID, "worker result verified"); err != nil {
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
	completed, _ := json.Marshal(call("run.node.complete").Result)
	if bytes.Contains(completed, []byte(`"isError":true`)) {
		t.Fatalf("active leader could not atomically conclude coordinator: %s", completed)
	}
	node, _ := st.Orchestration().GetNode(ctx, running.RunNodeID)
	if node.State != controlmodel.RunNodeSucceeded {
		t.Fatalf("coordinator state=%s", node.State)
	}
	completedTask, _ := st.Collaboration().GetAgentTask(ctx, followUp.ID)
	completedAttempt, _ := st.ExecutionAttempts().Get(ctx, followAttempt.ID)
	if completedTask.Status != controlmodel.AgentTaskCompleted ||
		completedAttempt.State != controlmodel.ExecutionSucceeded {
		t.Fatalf("coordinator conclusion left physical work active: task=%+v attempt=%+v",
			completedTask, completedAttempt)
	}
	rootComments, err := st.Collaboration().ListComments(ctx, issue.ID, store.CommentListOptions{Limit: 20})
	if err != nil {
		t.Fatal(err)
	}
	foundRootResult := false
	for _, comment := range rootComments {
		if comment.SourceTaskID != nil && *comment.SourceTaskID == followUp.ID && comment.Type == controlmodel.CommentResult {
			foundRootResult = true
		}
	}
	if !foundRootResult {
		t.Fatalf("follow-up coordinator result was not projected to root Issue: comments=%+v", rootComments)
	}
	blocked, _ := json.Marshal(call("issue.get").Result)
	if !bytes.Contains(blocked, []byte(`"isError":true`)) || !bytes.Contains(blocked, []byte(`restricted`)) {
		t.Fatalf("completed token accessed non-final tool: %s", blocked)
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

func TestLeaderFailurePublishesRootSummaryBeforeBlocked(t *testing.T) {
	for _, tool := range []string{"run.node.fail", "task.fail", "task.complete"} {
		t.Run(tool, func(t *testing.T) {
			ctx := context.Background()
			st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = st.Close() })
			team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
				Tenant: "tenant-a", Namespace: "default", Name: "failing-team", LeaderAgentRef: "leader",
			})
			if err != nil {
				t.Fatal(err)
			}
			issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
				Tenant: team.Tenant, Namespace: team.Namespace, Title: "fail coordinator",
				Creator:      controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
				AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String(),
			})
			if err != nil {
				t.Fatal(err)
			}
			tasks, _ := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 2})
			running, attempt, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
				store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version},
				&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneManaged,
					State: controlmodel.ExecutionAssigned})
			if err == nil {
				running, err = st.Collaboration().StartAgentTask(ctx, running.ID, running.Version)
			}
			if err != nil {
				t.Fatal(err)
			}
			srv := NewServer(ServerOptions{Store: st, TaskTokenSecret: "0123456789abcdef0123456789abcdef"})
			token, err := srv.taskTokens.MintScoped(running.ID, attempt.ID,
				attempt.DispatchGeneration, time.Now().UTC())
			if err != nil {
				t.Fatal(err)
			}
			body, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/call",
				"params": map[string]any{"name": tool, "arguments": map[string]any{
					"code": "unrecoverable", "message": "cannot converge", "outcome": "failed", "result": "partial research evidence"}}})
			req := httptest.NewRequest(http.MethodPost, "/mcp/collaboration", bytes.NewReader(body))
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("X-Agent-Task-Token", token)
			response := httptest.NewRecorder()
			srv.router.ServeHTTP(response, req)
			if response.Code != http.StatusOK || bytes.Contains(response.Body.Bytes(), []byte(`"isError":true`)) {
				t.Fatalf("run.node.fail: status=%d body=%s", response.Code, response.Body.String())
			}
			failedTask, _ := st.Collaboration().GetAgentTask(ctx, running.ID)
			failedAttempt, _ := st.ExecutionAttempts().Get(ctx, attempt.ID)
			failedNode, _ := st.Orchestration().GetNode(ctx, running.RunNodeID)
			failedRun, _ := st.Orchestration().GetRun(ctx, running.OrchestrationRunID)
			if failedTask.Status != controlmodel.AgentTaskFailed ||
				failedAttempt.State != controlmodel.ExecutionFailed ||
				failedNode.State != controlmodel.RunNodeFailed ||
				failedRun.State != controlmodel.RunFailed {
				t.Fatalf("coordinator failure left non-terminal work: task=%+v attempt=%+v node=%+v run=%+v",
					failedTask, failedAttempt, failedNode, failedRun)
			}
			issue, err = st.Collaboration().GetIssue(ctx, issue.ID)
			if err != nil || issue.Status != controlmodel.IssueBlocked {
				t.Fatalf("failed coordinator did not block root Issue: issue=%+v err=%v", issue, err)
			}
			comments, err := st.Collaboration().ListComments(ctx, issue.ID, store.CommentListOptions{Limit: 20})
			if err != nil || len(comments) != 1 || comments[0].Type != controlmodel.CommentStatus ||
				comments[0].SourceTaskID == nil || *comments[0].SourceTaskID != running.ID {
				t.Fatalf("failed coordinator did not leave a root Issue response: comments=%+v err=%v", comments, err)
			}

			if !strings.Contains(comments[0].Content, "partial research evidence") || !strings.Contains(comments[0].Content, "cannot converge") || !strings.Contains(comments[0].Content, "下一步") || comments[0].CreatedAt.After(issue.UpdatedAt) {
				t.Fatalf("summary must explain the outcome before the status change: comment=%+v issue=%+v", comments[0], issue)
			}
		})
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

func TestCollaborationMCPCompleteProjectsHostedConversationTerminal(t *testing.T) {
	st, server, session, running, token := startHostedMCPConversation(t, "test completion")

	responded := callMCPTool(t, server, token, "task.respond", map[string]any{
		"content": "Qoder returned the final answer.",
	})
	respondedJSON, _ := json.Marshal(responded.Result)
	if bytes.Contains(respondedJSON, []byte(`"isError":true`)) {
		t.Fatalf("task.respond failed: %s", respondedJSON)
	}
	completed := callMCPTool(t, server, token, "task.complete", map[string]any{
		"taskId":  "current",
		"summary": "short machine summary",
		"result":  map[string]any{"status": "ok", "message": "machine result"},
	})
	completedJSON, _ := json.Marshal(completed.Result)
	if bytes.Contains(completedJSON, []byte(`"isError":true`)) {
		t.Fatalf("task.complete failed: %s", completedJSON)
	}

	assertHostedTerminalEvents(t, st, session, "assistant.message", "Qoder returned the final answer.")
	currentSession, err := st.Sessions().GetByID(context.Background(), session.ID)
	if err != nil || currentSession.Phase != store.SessionPhaseIdle {
		t.Fatalf("hosted session was not released after MCP completion: session=%+v err=%v", currentSession, err)
	}
	attempt, err := st.ExecutionAttempts().Get(context.Background(), running.ID)
	if err != nil || attempt.State != controlmodel.ExecutionSucceeded {
		t.Fatalf("attempt did not complete: attempt=%+v err=%v", attempt, err)
	}
	if err = server.projectHostedAttemptTerminalWithOutput(context.Background(), attempt,
		"Qoder returned the final answer."); err != nil {
		t.Fatal(err)
	}
	assertHostedTerminalEvents(t, st, session, "assistant.message", "Qoder returned the final answer.")
}

func TestCollaborationMCPFailProjectsHostedConversationTerminal(t *testing.T) {
	st, server, session, running, token := startHostedMCPConversation(t, "test failure")

	failed := callMCPTool(t, server, token, "task.fail", map[string]any{
		"code": "provider_error", "message": "Qoder failed cleanly",
	})
	failedJSON, _ := json.Marshal(failed.Result)
	if bytes.Contains(failedJSON, []byte(`"isError":true`)) {
		t.Fatalf("task.fail failed: %s", failedJSON)
	}

	assertHostedTerminalEvents(t, st, session, "turn.failed", "Qoder failed cleanly")
	currentSession, err := st.Sessions().GetByID(context.Background(), session.ID)
	if err != nil || currentSession.Phase != store.SessionPhaseIdle {
		t.Fatalf("hosted session was not released after MCP failure: session=%+v err=%v", currentSession, err)
	}
	attempt, err := st.ExecutionAttempts().Get(context.Background(), running.ID)
	if err != nil || attempt.State != controlmodel.ExecutionFailed {
		t.Fatalf("attempt did not fail: attempt=%+v err=%v", attempt, err)
	}
}

func startHostedMCPConversation(t *testing.T, message string) (store.Store, *Server, *store.Session,
	*controlmodel.ExecutionAttempt, string) {
	t.Helper()
	st, agent, _, host := setupHostedConversationAgent(t)
	server := NewServer(ServerOptions{Store: st, AuthToken: "console",
		TaskTokenSecret: "0123456789abcdef0123456789abcdef", Features: features.Gates{RuntimeHost: true}})
	createBody, _ := json.Marshal(map[string]any{"tenant": "t", "namespace": "n", "name": "MCP hosted chat",
		"slug": "mcp-hosted-chat", "targetType": "agent", "targetRef": agent.ID.String(), "invocationMode": "conversation"})
	createReq := httptest.NewRequest(http.MethodPost, "/api/v1/endpoints", bytes.NewReader(createBody))
	createReq.Header.Set("Authorization", "Bearer console")
	createReq.Header.Set("Content-Type", "application/json")
	createOut := httptest.NewRecorder()
	server.router.ServeHTTP(createOut, createReq)
	var endpointResource struct {
		Credential string `json:"credential"`
		Endpoint   struct {
			ID      uuid.UUID `json:"id"`
			Version int64     `json:"version"`
		} `json:"endpoint"`
	}
	if createOut.Code != http.StatusCreated || json.Unmarshal(createOut.Body.Bytes(), &endpointResource) != nil {
		t.Fatalf("create hosted Endpoint: %d %s", createOut.Code, createOut.Body)
	}
	publishBody, _ := json.Marshal(map[string]any{"version": endpointResource.Endpoint.Version})
	publishReq := httptest.NewRequest(http.MethodPost,
		"/api/v1/endpoints/"+endpointResource.Endpoint.ID.String()+"/publish", bytes.NewReader(publishBody))
	publishReq.Header.Set("Authorization", "Bearer console")
	publishReq.Header.Set("Content-Type", "application/json")
	publishOut := httptest.NewRecorder()
	server.router.ServeHTTP(publishOut, publishReq)
	if publishOut.Code != http.StatusOK {
		t.Fatalf("publish hosted Endpoint: %d %s", publishOut.Code, publishOut.Body)
	}
	invokeBody, _ := json.Marshal(map[string]any{"message": message})
	invokeReq := httptest.NewRequest(http.MethodPost, "/invoke/v1/endpoints/mcp-hosted-chat/conversations",
		bytes.NewReader(invokeBody))
	invokeReq.Header.Set("X-API-Key", endpointResource.Credential)
	invokeReq.Header.Set("Idempotency-Key", "mcp-hosted-"+uuid.NewString())
	invokeReq.Header.Set("Content-Type", "application/json")
	invokeOut := httptest.NewRecorder()
	server.router.ServeHTTP(invokeOut, invokeReq)
	if invokeOut.Code != http.StatusAccepted {
		t.Fatalf("invoke hosted Endpoint conversation: %d %s", invokeOut.Code, invokeOut.Body)
	}
	var invocation struct {
		SessionID  string    `json:"sessionId"`
		SessionRef uuid.UUID `json:"sessionRef"`
	}
	if err := json.Unmarshal(invokeOut.Body.Bytes(), &invocation); err != nil {
		t.Fatal(err)
	}
	attempts, err := st.ExecutionAttempts().List(context.Background(), store.ExecutionAttemptFilter{
		Tenant: "t", Namespace: "n", AgentID: agent.ID, SessionID: invocation.SessionID,
	})
	if err != nil || len(attempts) != 1 {
		t.Fatalf("hosted attempt setup: attempts=%+v err=%v", attempts, err)
	}
	claimed, err := server.taskPlane.Claim(context.Background(), store.ExecutionClaim{Tenant: "t", Namespace: "n",
		RuntimePoolName: attempts[0].RuntimePoolName, HostID: host.ID, HostGeneration: host.LeaseGeneration,
		LeaseOwner: "host/mcp", LeaseToken: "mcp-lease", LeaseTTL: time.Minute})
	if err != nil {
		t.Fatal(err)
	}
	preparing, err := server.taskPlane.MarkPreparing(context.Background(), claimed.ID,
		claimed.LeaseToken, claimed.FencingToken)
	if err != nil {
		t.Fatal(err)
	}
	running, err := server.taskPlane.MarkRunning(context.Background(), preparing.ID,
		preparing.LeaseToken, preparing.FencingToken, "qoder-session", "workspace")
	if err != nil {
		t.Fatal(err)
	}
	token, err := server.taskTokens.MintScoped(running.AgentTaskID, running.ID,
		running.DispatchGeneration, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	session, err := st.Sessions().GetByID(context.Background(), invocation.SessionRef)
	if err != nil {
		t.Fatal(err)
	}
	return st, server, session, running, token
}

func callMCPTool(t *testing.T, server *Server, token, name string, arguments map[string]any) mcpResponse {
	t.Helper()
	body, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/call",
		"params": map[string]any{"name": name, "arguments": arguments}})
	req := httptest.NewRequest(http.MethodPost, "/mcp/collaboration", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Agent-Task-Token", token)
	out := httptest.NewRecorder()
	server.router.ServeHTTP(out, req)
	if out.Code != http.StatusOK {
		t.Fatalf("%s: HTTP %d: %s", name, out.Code, out.Body)
	}
	var response mcpResponse
	if err := json.Unmarshal(out.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	return response
}

func assertHostedTerminalEvents(t *testing.T, st store.Store, session *store.Session,
	eventType, content string) {
	t.Helper()
	events, err := st.Events().List(context.Background(), session.ID)
	if err != nil {
		t.Fatal(err)
	}
	terminalCount, completedCount := 0, 0
	for _, event := range events {
		if event.EventType == eventType {
			terminalCount++
			if event.Content != content {
				t.Fatalf("%s content=%q, want %q", eventType, event.Content, content)
			}
		}
		if event.EventType == "turn.completed" {
			completedCount++
		}
	}
	if terminalCount != 1 {
		t.Fatalf("%s count=%d, events=%+v", eventType, terminalCount, events)
	}
	if eventType == "assistant.message" && completedCount != 1 {
		t.Fatalf("turn.completed count=%d, events=%+v", completedCount, events)
	}
}

func TestHostedCompletionOutcomeAndTurns(t *testing.T) {
	for _, outcome := range []string{"succeeded", "blocked", "failed"} {
		t.Run(outcome, func(t *testing.T) {
			st, srv, session, running, token := startHostedMCPConversation(t, "research with required evidence")
			turns, err := st.Turns().List(context.Background(), session.ID, 10)
			if err != nil || len(turns) != 1 || turns[0].Status != store.TurnStatusRunning {
				t.Fatalf("missing hosted running turn: %+v %v", turns, err)
			}
			reply := callMCPTool(t, srv, token, "task.complete", map[string]any{"outcome": outcome, "summary": "search tool unavailable", "code": "missing_tool", "message": "required source evidence unavailable", "result": map[string]any{"sources": []string{}}})
			raw, _ := json.Marshal(reply.Result)
			if bytes.Contains(raw, []byte(`"isError":true`)) {
				t.Fatalf("completion failed: %s", raw)
			}
			attempt, err := st.ExecutionAttempts().Get(context.Background(), running.ID)
			expectedAttempt, expectedTurn := controlmodel.ExecutionSucceeded, store.TurnStatusCompleted
			if outcome != "succeeded" {
				expectedAttempt, expectedTurn = controlmodel.ExecutionFailed, store.TurnStatusFailed
			}
			if err != nil || attempt.State != expectedAttempt {
				t.Fatalf("outcome %s became %+v (%v)", outcome, attempt, err)
			}
			if err := srv.projectHostedAttemptTerminal(context.Background(), attempt); err != nil {
				t.Fatal(err)
			}
			turns, err = st.Turns().List(context.Background(), session.ID, 10)
			if err != nil || len(turns) != 1 || turns[0].Status != expectedTurn || turns[0].EndedAt == nil {
				t.Fatalf("missing/duplicate terminal turn: %+v %v", turns, err)
			}
		})
	}
}

func TestStatelessMCPGetRejectsSSEWithoutHTML(t *testing.T) {
	_, srv, _, _, token := startHostedMCPConversation(t, "MCP GET")
	req := httptest.NewRequest(http.MethodGet, "/mcp/collaboration", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Accept", "text/event-stream")
	out := httptest.NewRecorder()
	srv.router.ServeHTTP(out, req)
	if out.Code != http.StatusMethodNotAllowed || out.Header().Get("Allow") != "POST" || bytes.Contains(out.Body.Bytes(), []byte("html")) {
		t.Fatalf("SSE probe: %d %s", out.Code, out.Body)
	}
}

func TestMCPFailureUsesTransportCallIdentity(t *testing.T) {
	st, srv, _, attempt, token := startHostedMCPConversation(t, "tool diagnostic")
	body, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/call",
		"params": map[string]any{"name": "issue.get", "arguments": map[string]any{"issueId": uuid.NewString()},
			"_meta": map[string]any{"io.agentscope/toolCallId": "call-schema-1"}}})
	for range 2 {
		req := httptest.NewRequest(http.MethodPost, "/mcp/collaboration", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Agent-Task-Token", token)
		out := httptest.NewRecorder()
		srv.router.ServeHTTP(out, req)
		if out.Code != http.StatusOK || !bytes.Contains(out.Body.Bytes(), []byte(`"isError":true`)) {
			t.Fatalf("expected scoped tool error: %d %s", out.Code, out.Body)
		}
	}
	events, err := st.Orchestration().ListRunEvents(context.Background(), attempt.RunID, 0, 100)
	if err != nil {
		t.Fatal(err)
	}
	count := 0
	for _, event := range events {
		if event.Type == "agent_tool.failed" {
			count++
			if event.CausationID != "call-schema-1" || !bytes.Contains(event.Payload, []byte(`"toolCallId":"call-schema-1"`)) {
				t.Fatalf("lost identity: %+v", event)
			}
		}
	}
	if count != 1 {
		t.Fatalf("failure count=%d, expected one durable diagnostic", count)
	}
}

func TestHostedConsecutiveTurnsKeepHistoryAndIgnoreOldTerminalProjection(t *testing.T) {
	ctx := context.Background()
	st, srv, session, first, token := startHostedMCPConversation(t, "first turn")
	callMCPTool(t, srv, token, "task.complete", map[string]any{"outcome": "succeeded", "result": "first answer"})
	first, err := st.ExecutionAttempts().Get(ctx, first.ID)
	if err != nil || first.State != controlmodel.ExecutionSucceeded {
		t.Fatalf("first turn: %+v %v", first, err)
	}
	binding, err := st.AgentCatalog().GetBinding(ctx, session.BindingID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := srv.dispatchHostedConversationTurn(ctx, session, binding, "second turn", uuid.NewString(), "chat", ""); err != nil {
		t.Fatal(err)
	}
	if err := srv.projectHostedAttemptTerminal(ctx, first); err != nil {
		t.Fatal(err)
	}
	turns, err := st.Turns().List(ctx, session.ID, 10)
	if err != nil || len(turns) != 2 || turns[0].Status != store.TurnStatusRunning || turns[1].Status != store.TurnStatusCompleted {
		t.Fatalf("old projection ended the next turn: %+v %v", turns, err)
	}
	host, err := st.RuntimeRegistry().GetRuntimeHost(ctx, *first.HostID)
	if err != nil {
		t.Fatal(err)
	}
	second, err := srv.taskPlane.Claim(ctx, store.ExecutionClaim{Tenant: first.Tenant, Namespace: first.Namespace, RuntimePoolName: first.RuntimePoolName, HostID: host.ID, HostGeneration: host.LeaseGeneration, LeaseOwner: "host/second", LeaseToken: "second-lease", LeaseTTL: time.Minute})
	if err == nil {
		second, err = srv.taskPlane.MarkPreparing(ctx, second.ID, second.LeaseToken, second.FencingToken)
	}
	if err == nil {
		second, err = srv.taskPlane.MarkRunning(ctx, second.ID, second.LeaseToken, second.FencingToken, "qoder-session", "workspace")
	}
	if err != nil {
		t.Fatal(err)
	}
	token, err = srv.taskTokens.MintScoped(second.AgentTaskID, second.ID, second.DispatchGeneration, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	callMCPTool(t, srv, token, "task.complete", map[string]any{"outcome": "succeeded", "result": "second answer"})
	turns, err = st.Turns().List(ctx, session.ID, 10)
	if err != nil || len(turns) != 2 || turns[0].Status != store.TurnStatusCompleted || turns[1].Status != store.TurnStatusCompleted {
		t.Fatalf("two completed turns missing: %+v %v", turns, err)
	}
	srv.attachAttemptSessionRefs(ctx, []*controlmodel.ExecutionAttempt{first, second})
	if first.SessionRef == nil || second.SessionRef == nil || *first.SessionRef != session.ID || *second.SessionRef != session.ID {
		t.Fatal("consecutive attempt history lost the shared session reference")
	}
}

func TestReviewFeedbackCanFinishButCannotDelegateOrChangeWork(t *testing.T) {
	teamID := uuid.New()
	task := &controlmodel.AgentTask{ID: uuid.New(), TeamID: &teamID, LeaderTask: true, TriggerType: controlmodel.AgentTaskReviewComment}
	server := &Server{}
	if err := server.validateMCPTeamLeaderCompletion(context.Background(), task); err != nil {
		t.Fatal(err)
	}
	c, _ := gin.CreateTestContext(httptest.NewRecorder())
	c.Request = httptest.NewRequest(http.MethodPost, "/mcp/collaboration", nil)
	for _, name := range []string{"issue.child.create", "issue.accept", "issue.cancel", "run.node.complete", "run.node.fail", "run.replan", "run.signal", "approval.request"} {
		if _, err := server.callCollaborationMCPTool(c, task, name, map[string]any{}); err == nil || !strings.Contains(err.Error(), "task.begin_work") {
			t.Fatalf("%s bypassed feedback boundary: %v", name, err)
		}
	}
	for _, name := range []string{"issue.comment.add", "task.respond", "task.progress"} {
		if _, err := server.callCollaborationMCPTool(c, task, name, map[string]any{"mentions": []any{map[string]any{"type": "agent", "ref": "worker"}}}); err == nil {
			t.Fatalf("%s delegated through mentions", name)
		}
	}
}

func TestCompletionPreservesMessageDeliverableAlongsideSummary(t *testing.T) {
	for _, explicit := range []bool{false, true} {
		t.Run(fmt.Sprint(explicit), func(t *testing.T) {
			st, srv, _, attempt, token := startHostedMCPConversation(t, "write a poem")
			args := map[string]any{"outcome": "succeeded", "summary": "draft produced", "message": "the complete poem"}
			want := "the complete poem"
			if explicit {
				args["result"] = "explicit final poem"
				want = "explicit final poem"
			}
			response := callMCPTool(t, srv, token, "task.complete", args)
			raw, _ := json.Marshal(response.Result)
			if bytes.Contains(raw, []byte(`"isError":true`)) {
				t.Fatalf("completion failed: %s", raw)
			}
			task, err := st.Collaboration().GetAgentTask(t.Context(), attempt.AgentTaskID)
			if err != nil {
				t.Fatal(err)
			}
			var result string
			if err := json.Unmarshal(task.Result, &result); err != nil || result != want {
				t.Fatalf("lost actual deliverable: result=%s err=%v", task.Result, err)
			}
			comments, err := st.Collaboration().ListComments(t.Context(), task.IssueID, store.CommentListOptions{Limit: 10})
			if err != nil {
				t.Fatal(err)
			}
			found := false
			for _, comment := range comments {
				if strings.Contains(comment.Content, want) {
					found = true
				}
			}
			if !found {
				t.Fatal("visible result comment lost deliverable")
			}
		})
	}
}

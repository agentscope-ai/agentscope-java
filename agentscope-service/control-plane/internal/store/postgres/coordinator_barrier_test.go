// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

func completedCoordinatorFixture(t *testing.T, st store.Store) (*model.Issue, *model.RunNode, *model.AgentTask) {
	t.Helper()
	ctx := t.Context()
	actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
	issue, err := st.Collaboration().CreateIssue(ctx, &model.Issue{Tenant: "t", Namespace: "n", Title: "coordinator", Creator: actor})
	if err != nil {
		t.Fatal(err)
	}
	run, err := st.Orchestration().CreateRun(ctx, &model.OrchestrationRun{Tenant: "t", Namespace: "n", RootIssueID: issue.ID, Mode: model.RunModeAdaptive, State: model.RunRunning, CreatedBy: actor})
	if err != nil {
		t.Fatal(err)
	}
	node, err := st.Orchestration().CreateNode(ctx, &model.RunNode{RunID: run.ID, Tenant: "t", Namespace: "n", IssueID: &issue.ID, NodeKey: "leader", Type: model.RunNodeTeam, State: model.RunNodeWaiting})
	if err != nil {
		t.Fatal(err)
	}
	task, err := st.Collaboration().CreateRunAgentTask(ctx, store.RunTaskRequest{RunID: run.ID, NodeID: node.ID, IssueID: issue.ID, AgentRef: "leader", TeamRole: "leader", Leader: true, Originator: actor})
	if err != nil {
		t.Fatal(err)
	}
	task, err = st.Collaboration().ClaimAgentTask(ctx, store.TaskClaim{TaskID: task.ID, ExpectedVersion: task.Version})
	if err != nil {
		t.Fatal(err)
	}
	task, err = st.Collaboration().StartAgentTask(ctx, task.ID, task.Version)
	if err != nil {
		t.Fatal(err)
	}
	task, err = st.Collaboration().CompleteAgentTask(ctx, task.ID, store.TaskCompletion{ExpectedVersion: task.Version, Result: json.RawMessage(`{"ok":true}`)})
	if err != nil {
		t.Fatal(err)
	}
	node, err = st.Orchestration().GetNode(ctx, node.ID)
	if err != nil {
		t.Fatal(err)
	}
	return issue, node, task
}

func TestCoordinatorCommitAndChildCreationSerializeAcrossReplicas(t *testing.T) {
	cfg := serviceMigrationConfig(t)
	first, err := store.Open(t.Context(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	second, err := store.Open(t.Context(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	for iteration := range 12 {
		root, node, task := completedCoordinatorFixture(t, first)
		start := make(chan struct{})
		completion := make(chan error, 1)
		creation := make(chan error, 1)
		go func() {
			<-start
			_, err := first.Orchestration().FinalizeCoordinatorNode(t.Context(), task.ID, node.Version, json.RawMessage(`{"ok":true}`), model.Actor{Type: model.ActorAgent, Ref: "leader"})
			completion <- err
		}()
		go func() {
			<-start
			child := &model.Issue{Tenant: "t", Namespace: "n", Title: "late delegation", Creator: model.Actor{Type: model.ActorAgent, Ref: "leader"}, ParentIssueID: &root.ID}
			if iteration%2 == 0 {
				child.SourceType, child.SourceRef = "agent-task", task.ID.String()
			}
			_, err := second.Collaboration().CreateIssue(t.Context(), child)
			creation <- err
		}()
		close(start)
		completeErr, createErr := <-completion, <-creation
		if completeErr == nil && createErr == nil {
			t.Fatal("successful coordinator accepted new unfinished work")
		}
		if completeErr != nil && !errors.Is(completeErr, store.ErrConflict) {
			t.Fatal("unexpected completion error", completeErr)
		}
		if createErr != nil && !errors.Is(createErr, store.ErrConflict) {
			t.Fatal("unexpected creation error", createErr)
		}
		persisted, err := second.Orchestration().GetNode(t.Context(), node.ID)
		if err != nil {
			t.Fatal(err)
		}
		children, err := second.Collaboration().ListIssues(t.Context(), store.IssueFilter{ParentID: &root.ID, Limit: 10})
		if err != nil {
			t.Fatal(err)
		}
		if persisted.State == model.RunNodeSucceeded && len(children) > 0 {
			t.Fatal("commit and child were both visible")
		}
		events, err := second.Orchestration().ListRunEvents(t.Context(), task.OrchestrationRunID, 0, 20)
		if err != nil {
			t.Fatal(err)
		}
		succeeded := 0
		for _, event := range events {
			if event.Type == "node.succeeded" {
				succeeded++
			}
		}
		if completeErr == nil && succeeded != 1 || completeErr != nil && succeeded != 0 {
			t.Fatalf("barrier and event not atomic: %v %d", completeErr, succeeded)
		}
	}
}

func TestCoordinatorCommitReturnsConflictForUncommittedChildWriter(t *testing.T) {
	cfg := serviceMigrationConfig(t)
	st, err := store.Open(t.Context(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	root, node, task := completedCoordinatorFixture(t, st)
	tx, err := st.(*Store).pool.Begin(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(context.Background())
	child := &model.Issue{Tenant: "t", Namespace: "n", ParentIssueID: &root.ID, SourceType: "agent-task", SourceRef: task.ID.String()}
	if err = guardCoordinatorChildTx(t.Context(), tx, child); err != nil {
		t.Fatal(err)
	}
	if _, err = tx.Exec(t.Context(), `INSERT INTO issues(id,tenant,namespace,title,parent_issue_id,source_type,source_ref,status,creator_type) VALUES($1,'t','n','uncommitted child',$2,'agent-task',$3,'backlog','agent')`, uuid.New(), root.ID, task.ID.String()); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(t.Context(), 2*time.Second)
	defer cancel()
	if _, err = st.Orchestration().FinalizeCoordinatorNode(ctx, task.ID, node.Version, json.RawMessage(`true`), model.Actor{Type: model.ActorAgent, Ref: "leader"}); !errors.Is(err, store.ErrConflict) {
		t.Fatal("completion waited behind a writer", err)
	}
	if err = tx.Commit(t.Context()); err != nil {
		t.Fatal(err)
	}
	if _, err = st.Orchestration().FinalizeCoordinatorNode(t.Context(), task.ID, node.Version, json.RawMessage(`true`), model.Actor{Type: model.ActorAgent, Ref: "leader"}); !errors.Is(err, store.ErrConflict) || !strings.Contains(err.Error(), "active child issue") {
		t.Fatal("committed child omitted", err)
	}
	persisted, err := st.Orchestration().GetNode(t.Context(), node.ID)
	if err != nil || persisted.State != model.RunNodeWaiting {
		t.Fatal(persisted, err)
	}
}

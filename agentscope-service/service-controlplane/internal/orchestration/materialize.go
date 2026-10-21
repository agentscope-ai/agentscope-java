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

package orchestration

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

// A planned Run is a durable materialization checkpoint. All identities are
// deterministic so a process crash between writes can be resumed by any worker.
// Each call commits a finite batch while holding the cross-replica Run lock.
// The planned state prevents execution before every node and edge is durable.
const materializeNodeBatch = 16
const materializeEdgeBatch = 64

var errMaterializationPending = errors.New("graph materialization pending")

func (e *Engine) materialize(ctx context.Context, run *controlmodel.OrchestrationRun) error {
	revision, err := serviceapi.Revision(ctx, e.Store, run.RootIssueID, *run.DefinitionRevisionID)
	if err != nil {
		return err
	}
	if revision.Tenant != run.Tenant || revision.Namespace != run.Namespace {
		return store.ErrConflict
	}
	spec, err := ParseAndValidateSpec(revision.Spec, e.CEL)
	if err != nil {
		return err
	}
	issue, err := e.Store.Collaboration().GetIssue(ctx, run.RootIssueID)
	if err != nil {
		return err
	}
	existing, err := e.Store.Orchestration().ListNodes(ctx, run.ID)
	if err != nil {
		return err
	}
	byKey := map[string]*controlmodel.RunNode{}
	for _, node := range existing {
		byKey[node.NodeKey] = node
	}
	incoming := map[string]int{}
	for _, edge := range spec.Edges {
		incoming[edge.To]++
	}
	created := 0
	for _, n := range spec.Nodes {
		if byKey[n.Key] != nil {
			continue
		}
		if created >= materializeNodeBatch {
			return errMaterializationPending
		}
		issueID := issue.ID
		if n.IssueMode == "child" {
			childID := uuid.NewSHA1(run.ID, []byte("issue:"+n.Key))
			child, err := e.Store.Collaboration().GetIssue(ctx, childID)
			if err == store.ErrNotFound {
				child, err = e.Store.Collaboration().CreateIssue(ctx, &controlmodel.Issue{ID: childID, Tenant: run.Tenant, Namespace: run.Namespace, Title: issue.Title + " / " + n.Key, Description: "Work item for orchestration node " + n.Key, Status: controlmodel.IssueTodo, Priority: issue.Priority, Creator: run.CreatedBy, ParentIssueID: &issue.ID, SourceType: "orchestration-run-node", SourceRef: run.ID.String() + ":" + n.Key})
			}
			if err != nil {
				return err
			}
			issueID = child.ID
		}
		state := controlmodel.RunNodePending
		if incoming[n.Key] == 0 {
			state = controlmodel.RunNodeReady
		}
		config, _ := json.Marshal(n)
		node, err := e.Store.Orchestration().CreateNode(ctx, &controlmodel.RunNode{ID: uuid.NewSHA1(run.ID, []byte("node:"+n.Key)), RunID: run.ID, Tenant: run.Tenant, Namespace: run.Namespace, NodeKey: n.Key, DefinitionNodeKey: n.Key, Type: n.Type, Role: n.Role, IssueID: &issueID, State: state, Config: config, Input: json.RawMessage(`{}`), Iteration: 1})
		if err != nil {
			return err
		}
		byKey[n.Key] = node
		created++
	}
	existingEdges, err := e.Store.Orchestration().ListEdges(ctx, run.ID)
	if err != nil {
		return err
	}
	seen := map[uuid.UUID]bool{}
	for _, edge := range existingEdges {
		seen[edge.ID] = true
	}
	edges := []*controlmodel.RunEdge{}
	moreEdges := false
	for i, edge := range spec.Edges {
		id := uuid.NewSHA1(run.ID, []byte(fmt.Sprintf("edge:%d", i)))
		if seen[id] {
			continue
		}
		if len(edges) >= materializeEdgeBatch {
			moreEdges = true
			break
		}
		on := edge.On
		if len(on) == 0 {
			on = []controlmodel.RunNodeState{controlmodel.RunNodeSucceeded}
		}
		edges = append(edges, &controlmodel.RunEdge{ID: id, RunID: run.ID, Tenant: run.Tenant, Namespace: run.Namespace, FromNodeID: byKey[edge.From].ID, ToNodeID: byKey[edge.To].ID, OnStates: on, Condition: edge.Condition, Ordinal: edge.Ordinal})
	}
	if err = e.Store.Orchestration().CreateEdges(ctx, edges); err != nil {
		return err
	}
	if moreEdges {
		return errMaterializationPending
	}
	if _, err = e.Store.Orchestration().AppendRunEvent(ctx, &controlmodel.RunEvent{RunID: run.ID, Tenant: run.Tenant, Namespace: run.Namespace, Type: "run.started", Actor: run.CreatedBy, IdempotencyKey: "run-started:" + run.ID.String()}); err != nil {
		return err
	}
	_, err = e.Store.Orchestration().TransitionRun(ctx, run.ID, run.Version, controlmodel.RunRunning, nil, "", "")
	return err
}

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

package collaboration

import (
	"context"
	"encoding/json"
	"sort"
	"time"

	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// ExecutionBrief separates historical objectives from revised human constraints.
// Worker failure notifications are triggers, not new requirements.
type ExecutionBrief struct {
	Workflow          *WorkflowStepContext `json:"workflow,omitempty"`
	OriginalObjective string               `json:"originalObjective"`
	TriggerInput      string               `json:"triggerInput"`
	HumanRevisions    []HumanRevision      `json:"humanRevisions,omitempty"`
	DecisionRule      string               `json:"decisionRule"`
}

type HumanRevision struct {
	CommentID uuid.UUID `json:"commentId"`
	Content   string    `json:"content"`
	CreatedAt time.Time `json:"createdAt"`
}

const executionDecisionRule = "Apply the human revisions in chronological order to the original objective; the newest instruction wins where they conflict. Acknowledgements or praise alone do not authorize new work; preserve review-feedback handling. The trigger input may be a worker failure notification, not a requirement to repeat that failure. Decide the deliverable and required evidence BEFORE choosing tools. If the human permits an answer from existing knowledge, answer directly without web search or credentials and state the freshness/verification limits. If fresh sources or external verification remain required, do not substitute an unverified answer. A tool error blocks the task only when that tool is still necessary for the revised objective and no authorized alternative can produce the deliverable. Before task.fail or asking the human for a key, re-evaluate that necessity; leaders must make the same check before repeating a worker's blocker."

func (s *Service) buildExecutionBrief(ctx context.Context, envelope *ContextEnvelope) (*ExecutionBrief, error) {
	comments, err := s.Store.Collaboration().ListComments(ctx, envelope.Issue.ID, store.CommentListOptions{Limit: 50, Tail: 50})
	if err != nil {
		return nil, err
	}
	brief := &ExecutionBrief{OriginalObjective: envelope.Issue.Title + "\n\n" + envelope.Issue.Description,
		TriggerInput: envelope.CurrentRequest, DecisionRule: executionDecisionRule}
	// Later, separately routed requests do not belong to an older task.
	// Coalesced input comments extend this task's own input boundary.
	cutoff := envelope.Task.CreatedAt
	for _, input := range envelope.Inputs {
		if input.Comment.CreatedAt.After(cutoff) {
			cutoff = input.Comment.CreatedAt
		}
	}
	for _, comment := range comments {
		if comment.Author.Type != controlmodel.ActorHuman || comment.DeletedAt != nil || comment.CreatedAt.After(cutoff) {
			continue
		}
		brief.HumanRevisions = append(brief.HumanRevisions, HumanRevision{CommentID: comment.ID, Content: comment.Content, CreatedAt: comment.CreatedAt})
	}
	sort.SliceStable(brief.HumanRevisions, func(i, j int) bool {
		return brief.HumanRevisions[i].CreatedAt.Before(brief.HumanRevisions[j].CreatedAt)
	})
	if envelope.Node != nil {
		brief.Workflow, err = s.workflowStepContext(ctx, envelope)
		if err != nil {
			return nil, err
		}
	}
	return brief, nil
}

// WorkflowStepContext gives a step its direct dependencies without exposing unrelated branches.
// Node input remains authoritative; predecessor outputs are context, not implicit input mappings.
type WorkflowStepContext struct {
	ProtocolCorrection string               `json:"protocolCorrection,omitempty"`
	NodeKey            string               `json:"nodeKey"`
	RunInput           json.RawMessage      `json:"runInput,omitempty"`
	NodeInput          json.RawMessage      `json:"nodeInput,omitempty"`
	Predecessors       []WorkflowStepResult `json:"predecessors"`
}
type WorkflowStepResult struct {
	NodeKey        string                    `json:"nodeKey"`
	State          controlmodel.RunNodeState `json:"state"`
	Output         json.RawMessage           `json:"output,omitempty"`
	FailureMessage string                    `json:"failureMessage,omitempty"`
}

func (s *Service) workflowStepContext(ctx context.Context, envelope *ContextEnvelope) (*WorkflowStepContext, error) {
	step := &WorkflowStepContext{NodeKey: envelope.Node.NodeKey, RunInput: envelope.Run.Input,
		NodeInput: envelope.Node.Input, Predecessors: []WorkflowStepResult{}}
	attempts, err := s.Store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{Tenant: envelope.Task.Tenant, Namespace: envelope.Task.Namespace, AgentTaskID: envelope.Task.ID, State: controlmodel.ExecutionFailed, NewestFirst: true, Limit: 1})
	if err != nil {
		return nil, err
	}
	if len(attempts) > 0 && attempts[0].FailureCode == "managed_turn_incomplete" {
		step.ProtocolCorrection = "Your previous turn did not submit the deliverable through a completion tool. Reuse its draft if useful; do not redo successful actions or ask for optional preferences. Call task.complete with outcome=succeeded and the FULL deliverable in result now, or task.fail if the objective truly cannot be achieved. Another plain response will fail this node. Previous turn: " + attempts[0].FailureMessage
	}
	edges, err := s.Store.Orchestration().ListEdges(ctx, envelope.Run.ID)
	if err != nil {
		return nil, err
	}
	seen := map[uuid.UUID]bool{}
	for _, edge := range edges {
		if edge.ToNodeID != envelope.Node.ID || seen[edge.FromNodeID] {
			continue
		}
		seen[edge.FromNodeID] = true
		node, err := s.Store.Orchestration().GetNode(ctx, edge.FromNodeID)
		if err != nil {
			return nil, err
		}
		step.Predecessors = append(step.Predecessors, WorkflowStepResult{NodeKey: node.NodeKey,
			State: node.State, Output: node.Output, FailureMessage: node.FailureMessage})
	}
	sort.Slice(step.Predecessors, func(i, j int) bool { return step.Predecessors[i].NodeKey < step.Predecessors[j].NodeKey })
	return step, nil
}

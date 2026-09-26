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

package collaboration

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

// UpdateAcceptanceFromTask records evidence on an existing checklist item using
// task-scoped authority. It never edits requirements or the human-review policy.
func (s *Service) UpdateAcceptanceFromTask(ctx context.Context, taskID uuid.UUID, itemID string, satisfied bool, evidence string) (*controlmodel.Issue, error) {
	task, err := s.Store.Collaboration().GetAgentTask(ctx, taskID)
	if err != nil {
		return nil, err
	}
	if !task.LeaderTask || task.TeamID == nil || task.TriggerType == controlmodel.AgentTaskReviewComment || controlmodel.IsAgentTaskTerminal(task.Status) {
		return nil, fmt.Errorf("only an active Team leader can record checklist evidence")
	}
	issue, err := s.Store.Collaboration().GetIssue(ctx, task.IssueID)
	if err != nil {
		return nil, err
	}
	if issue.ParentIssueID == nil || issue.Status == controlmodel.IssueDone || issue.Status == controlmodel.IssueCancelled {
		return nil, fmt.Errorf("checklist updates require the current open delegated child")
	}
	if _, err = s.TeamForTask(ctx, task); err != nil {
		return nil, err
	}
	if strings.TrimSpace(evidence) == "" || strings.TrimSpace(itemID) == "" {
		return nil, fmt.Errorf("itemId and concrete evidence are required")
	}
	var criteria map[string]json.RawMessage
	if err = json.Unmarshal(issue.AcceptanceCriteria, &criteria); err != nil {
		return nil, fmt.Errorf("no valid acceptance checklist: %w", err)
	}
	var checklist []map[string]json.RawMessage
	if err = json.Unmarshal(criteria["checklist"], &checklist); err != nil {
		return nil, fmt.Errorf("no valid acceptance checklist: %w", err)
	}
	found := false
	for _, item := range checklist {
		var id string
		if json.Unmarshal(item["id"], &id) != nil || id != itemID {
			continue
		}
		item["satisfied"], _ = json.Marshal(satisfied)
		item["evidence"], _ = json.Marshal(strings.TrimSpace(evidence))
		item["sourceTaskId"], _ = json.Marshal(task.ID)
		found = true
	}
	if !found {
		return nil, fmt.Errorf("unknown checklist item %q; read issue.get and use an existing item ID", itemID)
	}
	criteria["checklist"], _ = json.Marshal(checklist)
	issue.AcceptanceCriteria, err = json.Marshal(criteria)
	if err != nil {
		return nil, err
	}
	return s.Store.Collaboration().UpdateIssue(ctx, issue, issue.Version, controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef})
}

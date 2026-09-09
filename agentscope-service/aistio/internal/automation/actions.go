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

package automation

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/google/uuid"
	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"strings"
)

type IssueAction struct {
	Title              string                        `json:"title"`
	Description        string                        `json:"description,omitempty"`
	Priority           string                        `json:"priority,omitempty"`
	AssigneeType       controlmodel.AssigneeType     `json:"assigneeType,omitempty"`
	AssigneeRef        string                        `json:"assigneeRef,omitempty"`
	AcceptanceCriteria json.RawMessage               `json:"acceptanceCriteria,omitempty"`
	ContextRefs        json.RawMessage               `json:"contextRefs,omitempty"`
	InitialComment     string                        `json:"initialComment,omitempty"`
	Mentions           []collaboration.MentionTarget `json:"mentions,omitempty"`
}
type CommentAction struct {
	IssueID  uuid.UUID                     `json:"issueId"`
	ParentID *uuid.UUID                    `json:"parentId,omitempty"`
	Content  string                        `json:"content"`
	Mentions []collaboration.MentionTarget `json:"mentions,omitempty"`
}

type StartOrchestrationAction struct {
	DefinitionID uuid.UUID           `json:"definitionId"`
	RevisionID   *uuid.UUID          `json:"revisionId,omitempty"`
	IssueID      *uuid.UUID          `json:"issueId,omitempty"`
	Issue        *controlmodel.Issue `json:"issue,omitempty"`
	Input        json.RawMessage     `json:"input,omitempty"`
}

type SignalOrchestrationAction struct {
	RunID   uuid.UUID       `json:"runId"`
	Name    string          `json:"name"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

func validateAction(kind controlmodel.AutomationActionType, raw json.RawMessage) error {
	switch kind {
	case controlmodel.AutomationCreateIssue:
		var v IssueAction
		if err := json.Unmarshal(raw, &v); err != nil {
			return err
		}
		if strings.TrimSpace(v.Title) == "" {
			return fmt.Errorf("actionConfig.title is required")
		}
	case controlmodel.AutomationAddComment:
		var v CommentAction
		if err := json.Unmarshal(raw, &v); err != nil {
			return err
		}
		if v.IssueID == uuid.Nil || strings.TrimSpace(v.Content) == "" {
			return fmt.Errorf("actionConfig.issueId and content are required")
		}
	case controlmodel.AutomationStartRun:
		var v StartOrchestrationAction
		if err := json.Unmarshal(raw, &v); err != nil {
			return err
		}
		if v.DefinitionID == uuid.Nil || (v.IssueID == nil) == (v.Issue == nil) {
			return fmt.Errorf("actionConfig.definitionId and exactly one of issueId or issue are required")
		}
	case controlmodel.AutomationSignalRun:
		var v SignalOrchestrationAction
		if err := json.Unmarshal(raw, &v); err != nil {
			return err
		}
		if v.RunID == uuid.Nil || strings.TrimSpace(v.Name) == "" {
			return fmt.Errorf("actionConfig.runId and name are required")
		}
	default:
		return fmt.Errorf("unsupported actionType %q", kind)
	}
	return nil
}

func (s *Service) validateLegacyTarget(ctx context.Context, rule *controlmodel.Automation) error {
	checkIssue := func(id uuid.UUID) error {
		issue, err := s.Store.Collaboration().GetIssue(ctx, id)
		if err != nil {
			return err
		}
		if issue.Tenant != rule.Tenant || issue.Namespace != rule.Namespace {
			return fmt.Errorf("issue is outside automation scope")
		}
		return nil
	}
	switch rule.ActionType {
	case controlmodel.AutomationAddComment:
		var v CommentAction
		if err := json.Unmarshal(rule.ActionConfig, &v); err != nil {
			return err
		}
		return checkIssue(v.IssueID)
	case controlmodel.AutomationStartRun:
		var v StartOrchestrationAction
		if err := json.Unmarshal(rule.ActionConfig, &v); err != nil {
			return err
		}
		d, err := s.Store.Orchestration().GetDefinition(ctx, v.DefinitionID)
		if err != nil {
			return err
		}
		if d.Tenant != rule.Tenant || d.Namespace != rule.Namespace {
			return fmt.Errorf("workflow is outside automation scope")
		}
		if v.IssueID != nil {
			return checkIssue(*v.IssueID)
		}
		if v.Issue != nil && ((v.Issue.Tenant != "" && v.Issue.Tenant != rule.Tenant) || (v.Issue.Namespace != "" && v.Issue.Namespace != rule.Namespace)) {
			return fmt.Errorf("issue is outside automation scope")
		}
	case controlmodel.AutomationSignalRun:
		var v SignalOrchestrationAction
		if err := json.Unmarshal(rule.ActionConfig, &v); err != nil {
			return err
		}
		run, err := s.Store.Orchestration().GetRun(ctx, v.RunID)
		if err != nil {
			return err
		}
		if run.Tenant != rule.Tenant || run.Namespace != rule.Namespace {
			return fmt.Errorf("workflow run is outside automation scope")
		}
	}
	return nil
}

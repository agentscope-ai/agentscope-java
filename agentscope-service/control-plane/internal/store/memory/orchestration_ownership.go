// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package memory

import (
	"encoding/json"

	"github.com/google/uuid"
)

func (s *Store) agentServiceIssueOwnedLocked(id uuid.UUID) bool {
	seen := map[uuid.UUID]bool{}
	for id != uuid.Nil && len(seen) < 256 {
		if seen[id] {
			return false
		}
		seen[id] = true
		issue := s.issues[id]
		if issue == nil {
			return false
		}
		if issue.SourceType == "endpoint" || issue.SourceType == "session_turn" || issue.SourceType == "endpoint_conversation" {
			invocationID, err := uuid.Parse(issue.SourceRef)
			if err != nil {
				return false
			}
			invocation := s.endpointInvocations[invocationID]
			if invocation == nil {
				return false
			}
			var reference struct {
				AgentServiceCommandID string `json:"portal_command_id"`
			}
			return json.Unmarshal(invocation.Contract, &reference) == nil && reference.AgentServiceCommandID != ""
		}
		if issue.ParentIssueID == nil {
			return false
		}
		id = *issue.ParentIssueID
	}
	return false
}

func (s *Store) agentServiceRuntimeEventOwnedLocked(kind, ref string) bool {
	id, err := uuid.Parse(ref)
	if err != nil {
		return false
	}
	var runID uuid.UUID
	switch kind {
	case "agent-task":
		if task := s.agentTasks[id]; task != nil {
			runID = task.OrchestrationRunID
		}
	case "execution-attempt":
		if attempt := s.executions[id]; attempt != nil {
			runID = attempt.RunID
		}
	case "approval":
		if approval := s.approvals[id]; approval != nil {
			if approval.RunID != nil {
				runID = *approval.RunID
			}
			if approval.IssueID != nil && s.agentServiceIssueOwnedLocked(*approval.IssueID) {
				return true
			}
		}
	}
	run := s.runs[runID]
	return run != nil && run.AgentServiceOwned
}

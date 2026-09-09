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
package store

import (
	"fmt"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"strings"
)

// Review feedback is a physical response turn, not authorization to redo work.
func ReviewCommentTrigger(issue *model.Issue, trigger string, author model.Actor) string {
	if trigger == "comment" && author.Type == model.ActorHuman && issue.ParentIssueID == nil && (issue.Status == model.IssueInReview || issue.Status == model.IssueDone) {
		return model.AgentTaskReviewComment
	}
	return trigger
}

func ValidateBeginReviewWork(issue *model.Issue, task *model.AgentTask, quote string, comments []*model.Comment) error {
	if task.TriggerType != model.AgentTaskReviewComment || task.Status != model.AgentTaskRunning || task.Originator.Type != model.ActorHuman {
		return fmt.Errorf("only a running human review feedback task can begin new work")
	}
	if issue.ArchivedAt != nil || !AgentTaskOwnsIssueLifecycle(issue, task) || (issue.Status != model.IssueInReview && issue.Status != model.IssueDone) {
		return fmt.Errorf("only the accountable assignee can reopen reviewed work; read the current Issue state")
	}
	quote = strings.TrimSpace(quote)
	if quote != "" {
		for _, c := range comments {
			if c.Author.Type == model.ActorHuman && c.DeletedAt == nil && strings.Contains(c.Content, quote) {
				return nil
			}
		}
	}
	return fmt.Errorf("requestQuote must quote the current human input requesting new work; Issue history is not a new assignment")
}

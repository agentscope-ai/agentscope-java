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
	"errors"
	"fmt"
	"strings"

	"github.com/google/uuid"
	controlmodel "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

// PublishCoordinatorSummary persists one root delivery before its Issue status
// changes. A stable ID makes concurrent reconciliation and transport retries
// safe, including recovery after the Run has already become terminal.
func (s *Service) PublishCoordinatorSummary(ctx context.Context, run *controlmodel.OrchestrationRun,
	leader *controlmodel.AgentTask, output json.RawMessage, code, message string) error {
	if run == nil || run.ParentRunID != nil {
		return nil
	}
	root, err := s.Store.Collaboration().GetIssue(ctx, run.RootIssueID)
	if err != nil {
		return err
	}
	if root.AssigneeType != controlmodel.AssigneeTeam {
		return nil
	}
	id := uuid.NewSHA1(run.ID, []byte("root-coordinator-summary-v1"))
	if _, err = s.Store.Collaboration().GetComment(ctx, id); err == nil {
		return nil
	} else if err != store.ErrNotFound {
		return err
	}
	failure := strings.TrimSpace(code) != "" || strings.TrimSpace(message) != "" || run.State == controlmodel.RunFailed
	state, next := "in_review（等待验收）", "请查看汇总结果并验收；如有遗漏，可在主 Issue 中补充要求。"
	if root.CompletionPolicy == controlmodel.IssueCompletionAutomatic {
		state, next = "done（已完成）", "本次请求已完成。"
	}
	if failure {
		state, next = "blocked（未能完成）", "请先解决上述未完成原因（补充所需输入或修复执行问题），再重试未完成的任务；已完成内容保留。"
	}
	if run.State == controlmodel.RunCancelled {
		state, next = "cancelled（已取消）", "本次执行已取消；需要继续时请重新发起任务。"
	}
	var b coordinatorSummaryBuilder
	fmt.Fprintf(&b, "本次团队处理总结\n\n处理结果：%s\n", state)
	if leader == nil {
		b.WriteString("\n本总结由系统根据执行记录生成。\n")
	}
	if failure {
		if message == "" {
			message = "团队执行未能完成。"
		}
		fmt.Fprintf(&b, "\n原因：%s\n", message)
		if code != "" {
			fmt.Fprintf(&b, "错误代码：%s\n", code)
		}
	}
	if len(output) == 0 && leader != nil {
		output = leader.Result
	}
	if text := CoordinatorOutcomeText(output); text != "" {
		fmt.Fprintf(&b, "\n%s\n", text)
	}
	fallback := func() error { return s.publishSummaryDetailReference(ctx, root, run, leader, id, state, next) }
	if b.err != nil {
		return fallback()
	}
	tasks, err := s.listRunSummaryTasks(ctx, run)
	if err != nil {
		if errors.Is(err, store.ErrResourceLimit) {
			return fallback()
		}
		return err
	}
	// A fallback uses the system identity, never inventing an LLM-authored reply.
	actor := controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "orchestration-run:" + run.ID.String()}
	if leader != nil {
		actor = controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: leader.AgentRef}
	}
	if b.err != nil {
		return fallback()
	}
	byIssue := map[uuid.UUID][]uuid.UUID{}
	latestWorker := map[uuid.UUID]*controlmodel.AgentTask{}
	for _, candidate := range tasks {
		byIssue[candidate.IssueID] = append(byIssue[candidate.IssueID], candidate.ID)
		latest := latestWorker[candidate.IssueID]
		if !candidate.LeaderTask && (latest == nil || candidate.CreatedAt.After(latest.CreatedAt) || candidate.CreatedAt.Equal(latest.CreatedAt) && candidate.ID.String() > latest.ID.String()) {
			latestWorker[candidate.IssueID] = candidate
		}
	}
	children, err := store.ReadChildIssuesBounded(ctx, s.Store.Collaboration(), store.IssueFilter{Tenant: root.Tenant, Namespace: root.Namespace, ParentID: &root.ID})
	if err != nil {
		if errors.Is(err, store.ErrResourceLimit) {
			return fallback()
		}
		return err
	}

	if len(children) > 0 {
		b.WriteString("\n子任务结果：\n")
	}
	for _, child := range children {
		fmt.Fprintf(&b, "\n- [%s](/work/issues/%s)：%s\n", child.Title, child.ID, child.Status)
		latest := latestWorker[child.ID]
		var deliverable *controlmodel.Comment
		if ids := byIssue[child.ID]; len(ids) > 0 {
			comments, listErr := s.Store.Collaboration().ListComments(ctx, child.ID, store.CommentListOptions{SourceTaskIDs: ids, ResultOnly: true, OmitRelations: true, Limit: 1, Tail: 1})
			if listErr != nil {
				if errors.Is(listErr, store.ErrResourceLimit) {
					return fallback()
				}
				return listErr
			}
			if len(comments) > 0 {
				deliverable = comments[0]
			}
		}
		if deliverable != nil {
			fmt.Fprintf(&b, "  已保存交付（验收状态：%s）：%s\n", child.Status, deliverable.Content)
		}
		if b.err != nil {
			return fallback()
		}
		if latest == nil {
			continue
		}
		fmt.Fprintf(&b, "  最近执行：%s\n", latest.Status)
		if text := CoordinatorOutcomeText(latest.Result); text != "" && (deliverable == nil || !strings.Contains(deliverable.Content, text)) {
			fmt.Fprintf(&b, "  交付内容：%s\n", text)
		}
		if latest.ErrorMessage != "" {
			fmt.Fprintf(&b, "  未完成原因：%s\n", latest.ErrorMessage)
		}
		if failure && latest.Status == controlmodel.AgentTaskCancelled {
			b.WriteString("  此次后续执行已取消，没有产生新的交付结果。\n")
		}
		if failure && !controlmodel.IsAgentTaskTerminal(latest.Status) {
			b.WriteString("  主执行失败，尚未完成的工作将停止；此项不计为成功。\n")
		}
		if b.err != nil {
			return fallback()
		}
	}
	fmt.Fprintf(&b, "\n下一步：%s", next)
	if b.err != nil {
		return fallback()
	}
	comment := &controlmodel.Comment{ID: id, IssueID: root.ID, Author: actor, Type: controlmodel.CommentResult, Content: b.String()}
	if failure {
		comment.Type = controlmodel.CommentStatus
	}
	if leader != nil {
		comment.SourceTaskID, comment.SourceAttemptID = &leader.ID, leader.CurrentAttemptID
	}
	// No implicit routing: publishing the final summary must not start more work.
	_, err = s.Store.Collaboration().CreateComment(ctx, store.CreateCommentRequest{Comment: comment})
	if errors.Is(err, store.ErrCommentCapacity) {
		return fallback()
	}
	if err != nil {
		if _, readErr := s.Store.Collaboration().GetComment(ctx, id); readErr == nil {
			return nil
		}
	}
	return err
}

func (s *Service) listRunSummaryTasks(ctx context.Context, run *controlmodel.OrchestrationRun) ([]*controlmodel.AgentTask, error) {
	return store.ReadTasksBounded(ctx, s.Store.Collaboration(), store.AgentTaskFilter{Tenant: run.Tenant, Namespace: run.Namespace, RunID: run.ID, OmitInputs: true})
}

// EnsureTerminalTeamSummary is also used when the runtime fails before the
// leader can invoke a semantic conclusion tool.
func (s *Service) EnsureTerminalTeamSummary(ctx context.Context, run *controlmodel.OrchestrationRun) error {
	if run == nil || run.TriggerType == controlmodel.AgentTaskReviewComment || !controlmodel.IsOrchestrationRunTerminal(run.State) {
		return nil
	}
	return s.PublishCoordinatorSummary(ctx, run, nil, run.Output, run.FailureCode, run.FailureMessage)
}

func CoordinatorOutcomeText(output json.RawMessage) string {
	if len(output) == 0 || string(output) == "null" {
		return ""
	}
	var value map[string]any
	if json.Unmarshal(output, &value) == nil {
		for _, key := range []string{"result", "output", "summary", "message", "reason"} {
			if nested, ok := value[key].(map[string]any); ok && len(nested) > 0 {
				raw, _ := json.Marshal(nested)
				return CoordinatorOutcomeText(raw)
			}
			if text, ok := value[key].(string); ok && strings.TrimSpace(text) != "" {
				return strings.TrimSpace(text)
			}
		}
	}
	var text string
	if json.Unmarshal(output, &text) == nil {
		return strings.TrimSpace(text)
	}
	return strings.TrimSpace(string(output))
}

// A failed size check switches publication to an explicit detail-reference
// summary; it never publishes the accumulated partial delivery text.
type coordinatorSummaryBuilder struct {
	text strings.Builder
	err  error
}

func (b *coordinatorSummaryBuilder) Write(value []byte) (int, error) {
	if b.err != nil {
		return 0, b.err
	}
	if len(value) > store.MaxRunGraphBytes-b.text.Len() {
		b.err = fmt.Errorf("%w: coordinator summary exceeds capacity", store.ErrResourceLimit)
		return 0, b.err
	}
	return b.text.Write(value)
}
func (b *coordinatorSummaryBuilder) WriteString(value string) (int, error) {
	if b.err != nil {
		return 0, b.err
	}
	if len(value) > store.MaxRunGraphBytes-b.text.Len() {
		b.err = fmt.Errorf("%w: coordinator summary exceeds capacity", store.ErrResourceLimit)
		return 0, b.err
	}
	return b.text.WriteString(value)
}
func (b *coordinatorSummaryBuilder) String() string { return b.text.String() }

// Detailed records remain durable. This explicit reference summary lets lifecycle
// convergence finish without pretending an omitted body is a full delivery.
func (s *Service) publishSummaryDetailReference(ctx context.Context, root *controlmodel.Issue, run *controlmodel.OrchestrationRun, leader *controlmodel.AgentTask, id uuid.UUID, state, next string) error {
	content := fmt.Sprintf("本次团队处理总结\n\n处理结果：%s\n\n完整详情超过内联汇总容量，本评论不包含完整交付正文。原始交付保存在各任务、评论及产物中；请查看 [Run %s](/work/executions/%s) 和关联子 Issue。\n\n下一步：%s", state, run.ID, run.ID, next)
	comment := &controlmodel.Comment{ID: id, IssueID: root.ID, Author: controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "orchestration-summary:" + run.ID.String()}, Type: controlmodel.CommentStatus, Content: content}
	if leader != nil {
		comment.SourceTaskID, comment.SourceAttemptID = &leader.ID, leader.CurrentAttemptID
	}
	_, err := s.Store.Collaboration().CreateComment(ctx, store.CreateCommentRequest{Comment: comment})
	if err != nil {
		if _, readErr := s.Store.Collaboration().GetComment(ctx, id); readErr == nil {
			return nil
		}
	}
	return err
}

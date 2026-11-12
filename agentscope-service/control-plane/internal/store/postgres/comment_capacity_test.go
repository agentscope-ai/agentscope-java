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
	"errors"
	"fmt"
	"github.com/google/uuid"
	"testing"
	"time"

	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

func TestCommentsReleaseSingleConnectionBeforeRelationsAndSelectDeliveries(t *testing.T) {
	cfg := serviceMigrationConfig(t)
	cfg.MaxOpenConns = 1
	st, err := store.Open(t.Context(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
	issue, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "comments", Creator: actor, AssigneeType: model.AssigneeAgent, AssigneeRef: "writer"})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(t.Context(), store.AgentTaskFilter{IssueID: issue.ID, Limit: 10})
	if err != nil || len(tasks) != 1 {
		t.Fatal(tasks, err)
	}
	source := tasks[0]
	other, err := st.Collaboration().CreateRunAgentTask(t.Context(), store.RunTaskRequest{RunID: source.OrchestrationRunID, NodeID: source.RunNodeID, IssueID: issue.ID, AgentRef: "other", Originator: actor})
	if err != nil {
		t.Fatal(err)
	}
	saved, err := st.Collaboration().CreateComment(t.Context(), store.CreateCommentRequest{Comment: &model.Comment{IssueID: issue.ID, Author: actor, Content: "saved delivery", Type: model.CommentResult, SourceTaskID: &source.ID}, Mentions: []model.Mention{{TargetType: model.AssigneeHuman, TargetRef: "reader"}}, Targets: []store.CommentTarget{{TargetType: model.AssigneeHuman, TargetRef: "reader", RouteType: model.RouteExplicit}}})
	if err != nil {
		t.Fatal(err)
	}
	for _, row := range []struct {
		task *model.AgentTask
		kind model.CommentType
		text string
	}{{other, model.CommentResult, "other source"}, {source, model.CommentGeneral, "newer discussion"}, {source, model.CommentResult, "deleted delivery"}} {
		result, e := st.Collaboration().CreateComment(t.Context(), store.CreateCommentRequest{Comment: &model.Comment{IssueID: issue.ID, Author: actor, Content: row.text, Type: row.kind, SourceTaskID: &row.task.ID}})
		if e != nil {
			t.Fatal(e)
		}
		if row.text == "deleted delivery" {
			if _, e = st.Collaboration().DeleteComment(t.Context(), result.Comment.ID, result.Comment.Version, actor); e != nil {
				t.Fatal(e)
			}
		}
	}
	ctx, cancel := context.WithTimeout(t.Context(), 3*time.Second)
	defer cancel()
	all, err := st.Collaboration().ListComments(ctx, issue.ID, store.CommentListOptions{Limit: 10})
	if err != nil || len(all) != 4 {
		t.Fatal("one-connection list blocked", len(all), err)
	}
	for _, comment := range all {
		if comment.ID == saved.Comment.ID && (len(comment.Mentions) != 1 || len(comment.Routes) != 1) {
			t.Fatal("relations omitted", comment)
		}
	}
	deliveries, err := st.Collaboration().ListComments(ctx, issue.ID, store.CommentListOptions{SourceTaskIDs: []uuid.UUID{source.ID}, ResultOnly: true, OmitRelations: true, Tail: 1, Limit: 1})
	if err != nil || len(deliveries) != 1 || deliveries[0].ID != saved.Comment.ID || len(deliveries[0].Mentions) != 0 || len(deliveries[0].Routes) != 0 {
		t.Fatal("wrong latest delivery", deliveries, err)
	}
}

func TestCommentCorrectionCapacityRollsBackVersionAndRoutedInputs(t *testing.T) {
	st, err := store.Open(t.Context(), serviceMigrationConfig(t))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	issue, task := taskInputFixture(t, st)
	routed, err := st.Collaboration().CreateComment(t.Context(), store.CreateCommentRequest{Comment: &model.Comment{IssueID: issue.ID, Author: issue.Creator, Content: "original"}, Targets: []store.CommentTarget{{TargetType: model.AssigneeAgent, TargetRef: task.AgentRef, AgentRef: task.AgentRef, RouteType: model.RouteExplicit}}})
	if err != nil {
		t.Fatal(err)
	}
	comment := routed.Comment
	current, err := st.Collaboration().GetAgentTask(t.Context(), task.ID)
	if err != nil || len(current.Inputs) != 1 {
		t.Fatal(current, err)
	}
	_, err = st.(*Store).pool.Exec(t.Context(), `INSERT INTO comment_routes(id,tenant,namespace,issue_id,comment_id,comment_version,target_type,target_ref,route_type,outcome)
 SELECT md5($1::text||':route:'||g::text)::uuid,'t','n',$2,$1::uuid,1,'human','reader-'||g,'explicit','queued'
 FROM generate_series(1,$3) g`, comment.ID, issue.ID, store.MaxCommentRelations-1)
	if err != nil {
		t.Fatal(err)
	}
	comment.Content = "correction"
	if _, err := st.Collaboration().UpdateComment(t.Context(), comment, comment.Version); !errors.Is(err, store.ErrCommentCapacity) {
		t.Fatal("correction exceeded retained relation capacity", err)
	}
	stored, err := st.Collaboration().GetComment(t.Context(), comment.ID)
	if err != nil || stored.Version != comment.Version || stored.Content != "original" || len(stored.Routes) != store.MaxCommentRelations {
		t.Fatal("rejected correction changed comment", err)
	}
	after, err := st.Collaboration().GetAgentTask(t.Context(), task.ID)
	if err != nil || after.Version != current.Version || len(after.Inputs) != 1 {
		t.Fatal("rejected correction routed new input", after, err)
	}
	_, err = st.(*Store).pool.Exec(t.Context(), `INSERT INTO comment_mentions(id,tenant,namespace,issue_id,comment_id,target_type,target_ref) VALUES($1,'t','n',$2,$3,'human','extra')`, uuid.New(), issue.ID, comment.ID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.Collaboration().GetComment(t.Context(), comment.ID); !errors.Is(err, store.ErrCommentCapacity) {
		t.Fatal("mixed mention/route overflow was truncated", err)
	}
	if _, err = st.Collaboration().ListComments(t.Context(), issue.ID, store.CommentListOptions{Limit: 1}); !errors.Is(err, store.ErrCommentCapacity) {
		t.Fatal("page returned partial relations", err)
	}
}

func TestCommentHistoryKeysetRetainsEveryEqualTimestampRow(t *testing.T) {
	cfg := serviceMigrationConfig(t)
	cfg.MaxOpenConns = 1
	st, err := store.Open(t.Context(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	issue, err := st.Collaboration().CreateIssue(t.Context(), &model.Issue{Tenant: "t", Namespace: "n", Title: "history", Creator: model.Actor{Type: model.ActorHuman, Ref: "owner"}})
	if err != nil {
		t.Fatal(err)
	}
	_, err = st.(*Store).pool.Exec(t.Context(), `INSERT INTO comments(id,tenant,namespace,issue_id,thread_root_id,author_type,author_ref,content,type,created_at)
 SELECT id,'t','n',$1::uuid,id,'human','owner','row-'||g,'general','2026-10-11T00:00:00Z'::timestamptz
 FROM (SELECT g,md5($1::text||':'||g::text)::uuid AS id FROM generate_series(1,521) g) fixture`, issue.ID)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	defer cancel()
	comments, err := store.ReadCommentsBounded(ctx, st.Collaboration(), issue.ID)
	if err != nil || len(comments) != 521 {
		t.Fatal("incomplete history", len(comments), err)
	}
	seen := map[string]bool{}
	for i, comment := range comments {
		if seen[comment.ID.String()] {
			t.Fatal("duplicate keyset entry", comment.ID)
		}
		seen[comment.ID.String()] = true
		if i > 0 && comments[i-1].ID.String() >= comment.ID.String() {
			t.Fatal("unstable equal-time order", fmt.Sprint(i))
		}
	}
}

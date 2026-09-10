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
	"strings"
	"testing"

	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func TestEndpointIssueDescriptionPreservesPayload(t *testing.T) {
	for _, test := range []struct{ input, want string }{
		{`{"prompt":"查看当前目录，写一首诗","count":9007199254740993}`, `9007199254740993`},
		{`"查看当前目录，写一首诗"`, `查看当前目录，写一首诗`},
		{`[{"task":"poem"},{"task":"files"}]`, `"files"`},
		{`42`, `42`},
		{`{"prompt":"\u5199\u8bd7","count":9007199254740993}`, `写诗`},
	} {
		t.Run(test.input, func(t *testing.T) {
			got := EndpointIssueDescription("额外要求：中文", json.RawMessage(test.input))
			if !strings.Contains(got, test.want) || !strings.Contains(got, "额外要求：中文") {
				t.Fatal(got)
			}
			if twice := EndpointIssueDescription(got, json.RawMessage(test.input)); twice != got {
				t.Fatal("duplicated endpoint input")
			}
		})
	}
	if got := EndpointIssueDescription("仅描述", json.RawMessage(`null`)); got != "仅描述" {
		t.Fatal(got)
	}
}

func TestEndpointContextUsesRootInputWithoutReplacingChildOrFollowup(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)
	svc := &Service{Store: st}
	actor := controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "endpoint:test"}
	root, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "t", Namespace: "n", Title: "API invocation", Creator: actor, Status: controlmodel.IssueInProgress})
	if err != nil {
		t.Fatal(err)
	}
	run, err := st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{Tenant: "t", Namespace: "n", RootIssueID: root.ID, Mode: controlmodel.RunModeDirect, TriggerType: "endpoint", State: controlmodel.RunRunning, CreatedBy: actor, Input: json.RawMessage(`{"prompt":"查看目录并写诗"}`)})
	if err != nil {
		t.Fatal(err)
	}
	makeTask := func(issue *controlmodel.Issue, key string) *controlmodel.AgentTask {
		n, err := st.Orchestration().CreateNode(ctx, &controlmodel.RunNode{RunID: run.ID, Tenant: "t", Namespace: "n", NodeKey: key, Type: controlmodel.RunNodeAgent, IssueID: &issue.ID, State: controlmodel.RunNodeReady})
		if err != nil {
			t.Fatal(err)
		}
		task, err := st.Collaboration().CreateRunAgentTask(ctx, store.RunTaskRequest{RunID: run.ID, NodeID: n.ID, IssueID: issue.ID, AgentRef: uuid.NewString(), Originator: actor})
		if err != nil {
			t.Fatal(err)
		}
		return task
	}
	task := makeTask(root, "root")
	context, err := svc.BuildContext(ctx, task.ID)
	if err != nil || !strings.Contains(context.CurrentRequest, "查看目录并写诗") {
		t.Fatalf("lost endpoint input: %+v %v", context, err)
	}
	stored, _ := st.Collaboration().GetIssue(ctx, root.ID)
	if stored.Description != "" {
		t.Fatal("context enrichment mutated persisted historical issue")
	}
	child, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "t", Namespace: "n", Title: "child", Description: "只完成给定诗歌", ParentIssueID: &root.ID, Creator: actor})
	if err != nil {
		t.Fatal(err)
	}
	childTask := makeTask(child, "child")
	context, err = svc.BuildContext(ctx, childTask.ID)
	if err != nil || strings.Contains(context.CurrentRequest, "查看目录") || !strings.Contains(context.CurrentRequest, "只完成给定诗歌") {
		t.Fatalf("root request overrode child: %+v %v", context, err)
	}
	_, err = st.Collaboration().CreateComment(ctx, store.CreateCommentRequest{Comment: &controlmodel.Comment{IssueID: root.ID, Author: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "admin"}, Content: "最新追问：只修改最后一句"}, Targets: []store.CommentTarget{{TargetType: controlmodel.AssigneeAgent, TargetRef: task.AgentRef, AgentRef: task.AgentRef, RouteType: controlmodel.RouteExplicit}}})
	if err != nil {
		t.Fatal(err)
	}
	context, err = svc.BuildContext(ctx, task.ID)
	if err != nil || context.CurrentRequest != "最新追问：只修改最后一句" {
		t.Fatalf("old endpoint request overrode followup: %+v %v", context, err)
	}
}

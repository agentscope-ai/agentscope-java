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
// Creates isolated completed-work fixtures for live review feedback tests.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/postgres"
	"os"
	"time"
)

func main() {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverPostgres, PostgresDSN: os.Getenv("AISTIO_STORAGE_DSN")})
	if err != nil {
		panic("store unavailable")
	}
	defer st.Close()
	state := map[string]string{}
	for _, name := range []string{"praise", "mention", "accepted", "change"} {
		status := model.IssueInReview
		if name == "accepted" {
			status = model.IssueDone
		}
		issue, err := st.Collaboration().CreateIssue(ctx, &model.Issue{Tenant: "default", Namespace: "default", Title: "IT review-feedback 20260907 " + name, Description: "测试预置的已交付工作：A=42，B=42，A+B=84。两项计算均已完成，不需要再次执行。", Status: status, Kind: model.IssueKindUserWork, Visibility: model.IssueVisibilityWorkHub, CompletionPolicy: model.IssueCompletionReview, Creator: model.Actor{Type: model.ActorHuman, Ref: "admin"}})
		if err != nil {
			panic(err)
		}
		issue.AssigneeType = model.AssigneeTeam
		issue.AssigneeRef = "22ce6ec1-fdf5-4393-a9f0-43abff828377"
		issue, err = st.Collaboration().UpdateIssue(ctx, issue, issue.Version, model.Actor{Type: model.ActorSystem, Ref: "test-fixture"})
		if err != nil {
			panic(err)
		}
		_, err = st.Collaboration().CreateComment(ctx, store.CreateCommentRequest{Comment: &model.Comment{IssueID: issue.ID, Author: model.Actor{Type: model.ActorSystem, Ref: "test-fixture"}, Type: model.CommentResult, Content: "测试预置交付结果：A=42，B=42，合计=84。"}})
		if err != nil {
			panic(err)
		}
		for _, label := range []string{"A=42", "B=42"} {
			now := time.Now().UTC()
			_, err = st.Collaboration().CreateIssue(ctx, &model.Issue{Tenant: issue.Tenant, Namespace: issue.Namespace, Title: "已完成 " + label, Description: label, Status: model.IssueDone, Kind: model.IssueKindUserWork, Visibility: model.IssueVisibilityWorkHub, CompletionPolicy: model.IssueCompletionReview, ParentIssueID: &issue.ID, Creator: issue.Creator, ResolvedAt: &now})
			if err != nil {
				panic(err)
			}
		}
		state[name] = issue.ID.String()
	}
	out, _ := json.MarshalIndent(state, "", "  ")
	fmt.Println(string(out))
}

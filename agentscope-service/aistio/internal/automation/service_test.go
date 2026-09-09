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

package automation

import (
	"context"
	"encoding/json"
	"github.com/google/uuid"
	"testing"
	"time"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestAllIngressKindsUseIssueAndAgentTaskPath(t *testing.T) {
	ctx := context.Background()
	for _, triggerType := range []controlmodel.AutomationTriggerType{
		controlmodel.AutomationTriggerCron,
		controlmodel.AutomationTriggerWebhook,
		controlmodel.AutomationTriggerChannel,
	} {
		t.Run(string(triggerType), func(t *testing.T) {
			service, dataStore := newTestService(t)
			action := mustJSON(t, IssueAction{
				Title:          "automation work",
				AssigneeType:   controlmodel.AssigneeAgent,
				AssigneeRef:    "worker",
				InitialComment: "durable ingress",
			})
			triggerConfig := json.RawMessage(`{}`)
			if triggerType == controlmodel.AutomationTriggerCron {
				triggerConfig = json.RawMessage(`{"schedule":"@every 1m"}`)
			}
			item, err := service.Create(ctx, &controlmodel.Automation{
				Tenant: "acme", Namespace: "default", Name: "ingress-" + string(triggerType), Enabled: true,
				TriggerType: triggerType, TriggerConfig: triggerConfig,
				ActionType: controlmodel.AutomationCreateIssue, ActionConfig: action,
				CreatedBy: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
			})
			if err != nil {
				t.Fatalf("create automation: %v", err)
			}
			run, err := service.Trigger(ctx, item.ID, "ingress-event", "event-1", json.RawMessage(`{"delivery":1}`))
			if err != nil {
				t.Fatalf("trigger automation: %v", err)
			}
			if run.Status != controlmodel.AutomationRunQueued || run.IssueID != nil {
				t.Fatalf("acceptance must precede dispatch: %+v", run)
			}
			run, err = service.ProcessRun(ctx, run)
			if err != nil {
				t.Fatal(err)
			}
			if run.Status == controlmodel.AutomationRunCompleted || run.IssueID == nil || run.AgentTaskID == nil {
				t.Fatalf("automation did not produce Issue/AgentTask: %+v", run)
			}
			issue, err := dataStore.Collaboration().GetIssue(ctx, *run.IssueID)
			if err != nil || issue.SourceType != "automation" || issue.SourceRef != run.ID.String() {
				t.Fatalf("issue causality missing: issue=%+v err=%v", issue, err)
			}
			comments, err := dataStore.Collaboration().ListComments(ctx, issue.ID, store.CommentListOptions{Limit: 10})
			if err != nil || len(comments) != 1 || comments[0].Author.Type != controlmodel.ActorAutomation {
				t.Fatalf("initial comment did not use collaboration path: comments=%+v err=%v", comments, err)
			}
			duplicate, err := service.Trigger(ctx, item.ID, "redelivery", "event-1", json.RawMessage(`{"delivery":1}`))
			if err != nil || duplicate.ID != run.ID {
				t.Fatalf("idempotent redelivery created a different run: run=%+v err=%v", duplicate, err)
			}
			runs, err := dataStore.Collaboration().ListAutomationRuns(ctx, item.ID, 10, 0)
			if err != nil || len(runs) != 1 {
				t.Fatalf("expected one durable run: runs=%+v err=%v", runs, err)
			}
		})
	}
}

func TestRunDueAdvancesScheduleAndIsIdempotent(t *testing.T) {
	ctx := context.Background()
	service, dataStore := newTestService(t)
	item, err := service.Create(ctx, &controlmodel.Automation{
		Tenant: "acme", Namespace: "default", Name: "scheduled", Enabled: true,
		TriggerType:   controlmodel.AutomationTriggerCron,
		TriggerConfig: json.RawMessage(`{"schedule":"@every 1m"}`),
		ActionType:    controlmodel.AutomationCreateIssue,
		ActionConfig:  mustJSON(t, IssueAction{Title: "scheduled work"}),
		CreatedBy:     controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
	})
	if err != nil {
		t.Fatalf("create cron automation: %v", err)
	}
	due := time.Now().UTC().Add(-time.Second)
	item.NextRunAt = &due
	item.Triggers[0].NextRunAt = &due
	item.Triggers[0].ID = uuid.New()
	item, err = dataStore.Collaboration().UpdateAutomation(ctx, item, item.Version)
	if err != nil {
		t.Fatalf("make automation due: %v", err)
	}
	now := time.Now().UTC()
	count, err := service.RunDue(ctx, now, 10)
	if err != nil || count != 1 {
		t.Fatalf("run due: count=%d err=%v", count, err)
	}
	updated, err := dataStore.Collaboration().GetAutomation(ctx, item.ID)
	if err != nil || updated.NextRunAt == nil || !updated.NextRunAt.After(due) {
		t.Fatalf("schedule did not advance: automation=%+v err=%v", updated, err)
	}
	count, err = service.RunDue(ctx, now, 10)
	if err != nil || count != 0 {
		t.Fatalf("same schedule fired twice: count=%d err=%v", count, err)
	}
}

func newTestService(t *testing.T) (*Service, store.Store) {
	t.Helper()
	dataStore, err := store.Open(context.Background(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatalf("open memory store: %v", err)
	}
	t.Cleanup(func() { _ = dataStore.Close() })
	return &Service{Store: dataStore}, dataStore
}

func mustJSON(t *testing.T, value any) json.RawMessage {
	t.Helper()
	raw, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("marshal JSON: %v", err)
	}
	return raw
}

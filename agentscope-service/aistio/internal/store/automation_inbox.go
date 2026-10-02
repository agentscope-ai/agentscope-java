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

package store

import (
	"encoding/json"
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"net/url"
)

// Failures before dispatch and automation deadlines have no failed AgentTask
// to notify the owner. Other execution failures use the existing task/issue inbox.
func AutomationFailureInbox(run *controlmodel.AutomationRun) *controlmodel.InboxItem {
	if run.Status != controlmodel.AutomationRunFailed || run.Snapshot == nil || run.Snapshot.CreatedBy.Type != controlmodel.ActorHuman || run.Snapshot.CreatedBy.Ref == "" {
		return nil
	}
	if run.IssueID != nil && run.ErrorCode != "queue_timeout" && run.ErrorCode != "execution_timeout" {
		return nil
	}
	key := "automation-failure:" + run.ID.String()
	query := url.Values{"automation": {run.AutomationID.String()}, "run": {run.ID.String()}, "tenant": {run.Tenant}, "namespace": {run.Namespace}}
	details, _ := json.Marshal(map[string]string{"automationId": run.AutomationID.String(), "runId": run.ID.String(), "errorCode": run.ErrorCode})
	return &controlmodel.InboxItem{ID: uuid.NewSHA1(run.ID, []byte("failure-inbox")), Tenant: run.Tenant, Namespace: run.Namespace, RecipientType: controlmodel.AssigneeHuman, RecipientRef: run.Snapshot.CreatedBy.Ref, Type: "automation_failure", Severity: "error", Actor: controlmodel.Actor{Type: controlmodel.ActorAutomation, Ref: run.AutomationID.String()}, Title: "Automation failed: " + run.Snapshot.Name, Body: run.ErrorMessage + "\n\n[Open automation run](/work/automations?" + query.Encode() + ")", Details: details, NeedsAction: true, DedupeKey: key, CreatedAt: run.UpdatedAt}
}

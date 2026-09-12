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

package orchestration

import (
	"context"
	"encoding/json"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
	"testing"
)

func TestWorkflowAuditEarlySignal(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "audit"}
	svc := &Service{Store: st}
	spec := json.RawMessage(`{"nodes":[{"key":"delay","type":"timer","timer":{"durationSeconds":3600}},{"key":"release","type":"signal","signalName":"ready"}],"edges":[{"from":"delay","to":"release"}]}`)
	d, err := st.Orchestration().CreateDefinition(ctx, &controlmodel.OrchestrationDefinition{Tenant: "audit", Namespace: "default", Name: "early-signal", DraftSpec: spec, CreatedBy: actor})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = svc.Publish(ctx, d.ID, actor); err != nil {
		t.Fatal(err)
	}
	run, err := svc.Start(ctx, d.ID, StartRequest{IdempotencyKey: "once", Issue: &controlmodel.Issue{Title: "signal audit", Creator: actor}, Actor: actor})
	if err != nil {
		t.Fatal(err)
	}
	if err = svc.Signal(ctx, run.ID, "ready", "signal-once", json.RawMessage(`{"value":42}`), actor); err != nil {
		t.Fatal(err)
	}
	nodes, _ := st.Orchestration().ListNodes(ctx, run.ID)
	for _, n := range nodes {
		if n.NodeKey == "delay" {
			if _, err = st.Orchestration().TransitionNode(ctx, n.ID, n.Version, controlmodel.RunNodeSucceeded, nil, "", ""); err != nil {
				t.Fatal(err)
			}
		}
	}
	if err = (&Engine{Store: st}).ReconcileRun(ctx, run.ID); err != nil {
		t.Fatal(err)
	}
	got, _ := st.Orchestration().GetRun(ctx, run.ID)
	if got.State != controlmodel.RunSucceeded {
		t.Fatalf("early accepted signal did not release the later gate: run=%s", got.State)
	}
}
func TestWorkflowAuditStartIdempotency(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "audit"}
	svc := &Service{Store: st}
	d, err := st.Orchestration().CreateDefinition(ctx, &controlmodel.OrchestrationDefinition{Tenant: "audit", Namespace: "default", Name: "idempotency", DraftSpec: json.RawMessage(`{"nodes":[{"key":"release","type":"signal","signalName":"ready"}]}`), CreatedBy: actor})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = svc.Publish(ctx, d.ID, actor); err != nil {
		t.Fatal(err)
	}
	req := StartRequest{IdempotencyKey: "same-request", Issue: &controlmodel.Issue{Title: "single issue", Creator: actor}, Actor: actor}
	first, err := svc.Start(ctx, d.ID, req)
	if err != nil {
		t.Fatal(err)
	}
	second, err := svc.Start(ctx, d.ID, req)
	if err != nil {
		t.Fatal(err)
	}
	if first.ID != second.ID {
		t.Fatal("Run ID changed")
	}
	issues, err := st.Collaboration().ListIssues(ctx, store.IssueFilter{Tenant: "audit", Namespace: "default", Limit: 100})
	if err != nil {
		t.Fatal(err)
	}
	if len(issues) != 1 {
		t.Fatalf("same Run, but repeated start created %d Issues; want 1", len(issues))
	}
}

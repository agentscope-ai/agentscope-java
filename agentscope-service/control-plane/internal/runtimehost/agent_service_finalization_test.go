// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package runtimehost

import (
	"context"
	"encoding/json"
	"errors"
	"testing"

	"github.com/google/uuid"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimehost/provider"
)

type agentServiceFinalizerFixture struct {
	fakeControlPlane
	ordinals         []int64
	stops            int
	unknownFirstStop bool
}

func (f *agentServiceFinalizerFixture) PublishProviderEvent(_ context.Context, _ uuid.UUID, _ *model.ExecutionAttempt, name string, ordinal int64, _ provider.Event) error {
	if name != "fake" {
		return errors.New("provider identity lost during replay")
	}
	f.ordinals = append(f.ordinals, ordinal)
	return nil
}
func (f *agentServiceFinalizerFixture) PhysicalStopped(context.Context, uuid.UUID, *model.ExecutionAttempt) error {
	f.stops++
	if f.unknownFirstStop && f.stops == 1 {
		return errors.New("unknown stop ACK")
	}
	return nil
}

func TestAgentServiceHostFinalizationDrainsBoundedEventsBeforeStopAndSurvivesUnknownACK(t *testing.T) {
	snapshot, _ := json.Marshal(model.RuntimeDispatchSnapshot{AgentServiceCommandID: "portal-turn"})
	record := &JournalRecord{Attempt: &model.ExecutionAttempt{ID: uuid.New(), RuntimeBinding: snapshot, State: model.ExecutionSucceeded}, HostID: uuid.New(), ProviderName: "fake"}
	journal := &Journal{Root: t.TempDir()}
	if err := journal.Save(record); err != nil {
		t.Fatal(err)
	}
	for range 129 {
		if _, err := journal.AppendEvent(record, provider.Event{Type: "assistant", Raw: json.RawMessage(`{"text":"done"}`)}); err != nil {
			t.Fatal(err)
		}
	}
	record.PendingTerminal = &PendingTerminal{Action: "physical-stop"}
	if err := journal.Save(record); err != nil {
		t.Fatal(err)
	}
	client := &agentServiceFinalizerFixture{unknownFirstStop: true}
	engine := &Engine{Client: client}
	for index := 1; index <= 2; index++ {
		engine.deliverPendingTerminal(t.Context(), journal, record)
		if client.stops != 0 || len(client.ordinals) != index*64 {
			t.Fatalf("unbounded drain or premature stop: events=%d stops=%d", len(client.ordinals), client.stops)
		}
	}
	engine.deliverPendingTerminal(t.Context(), journal, record)
	rows, err := journal.List()
	if err != nil || len(rows) != 1 || rows[0].FinalEventsDelivered != 129 || client.stops != 1 {
		t.Fatalf("unknown ACK lost outbox: rows=%d stops=%d err=%v", len(rows), client.stops, err)
	}
	restarted := &Engine{Client: client}
	restarted.deliverPendingTerminal(t.Context(), journal, rows[0])
	rows, err = journal.List()
	if err != nil || len(rows) != 1 || rows[0].PendingTerminal.Action != "cleanup" || len(client.ordinals) != 129 || client.stops != 2 {
		t.Fatalf("restart replay=%d rows=%d stops=%d %v", len(client.ordinals), len(rows), client.stops, err)
	}
	for range 2 {
		restarted.deliverPendingTerminal(t.Context(), journal, rows[0])
	}
	rows, err = journal.List()
	if err != nil || len(rows) != 0 || client.stops != 2 {
		t.Fatalf("bounded cleanup repeated stop: rows=%d stops=%d %v", len(rows), client.stops, err)
	}
	for index, ordinal := range client.ordinals {
		if ordinal != int64(index+1) {
			t.Fatalf("event order changed: %+v", client.ordinals)
		}
	}
}

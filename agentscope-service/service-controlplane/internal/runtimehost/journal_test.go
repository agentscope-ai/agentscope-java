// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package runtimehost

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimehost/provider"
	"github.com/google/uuid"
)

func TestJournalAppendsEventsWithoutGrowingMetadataAndRejectsOversizedEvent(t *testing.T) {
	journal := &Journal{Root: t.TempDir()}
	record := &JournalRecord{Attempt: &model.ExecutionAttempt{ID: uuid.New()}}
	if err := journal.Save(record); err != nil {
		t.Fatal(err)
	}
	for index := range 3 {
		raw, _ := json.Marshal(map[string]string{"text": strings.Repeat("x", 10000)})
		ordinal, err := journal.AppendEvent(record, provider.Event{Type: "assistant", Raw: raw})
		if err != nil || ordinal != int64(index+1) {
			t.Fatalf("append=%d %v", ordinal, err)
		}
	}
	bytes, err := os.ReadFile(journal.path(record.Attempt.ID))
	if err != nil || len(bytes) > 1024 || strings.Contains(string(bytes), `"events"`) {
		t.Fatalf("event history grew metadata: %d bytes, %v", len(bytes), err)
	}
	restarted, err := journal.Load(record.Attempt.ID)
	if err != nil || restarted.EventCount != 3 || len(restarted.Events) != 0 {
		t.Fatalf("durable event index=%+v %v", restarted, err)
	}
	for index := range 3 {
		event, err := journal.Event(restarted, index)
		if err != nil || event.Type != "assistant" || len(event.Raw) < 10000 {
			t.Fatalf("event %d=%+v %v", index, event, err)
		}
	}
	raw, _ := json.Marshal(strings.Repeat("x", maxJournalEventBytes))
	if _, err := journal.AppendEvent(restarted, provider.Event{Raw: raw}); err == nil || restarted.EventCount != 3 {
		t.Fatal("oversized event committed or advanced its cursor")
	}
	if _, err := os.Stat(filepath.Join(journal.eventDir(record.Attempt.ID), "00000004.json")); !os.IsNotExist(err) {
		t.Fatalf("rejected event left a committed file: %v", err)
	}
}

func TestJournalAdmissionStopsAtCapacityButKeepsExistingOutboxWritable(t *testing.T) {
	journal := &Journal{Root: t.TempDir()}
	dir := filepath.Join(journal.Root, "execution-attempts")
	if err := os.MkdirAll(dir, 0o750); err != nil {
		t.Fatal(err)
	}
	var existing *JournalRecord
	for range maxJournalAttempts {
		existing = &JournalRecord{Attempt: &model.ExecutionAttempt{ID: uuid.New()}}
		data, _ := json.Marshal(existing)
		if err := os.WriteFile(journal.path(existing.Attempt.ID), data, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	newRecord := &JournalRecord{Attempt: &model.ExecutionAttempt{ID: uuid.New()}}
	if err := journal.Save(newRecord); err == nil {
		t.Fatal("full journal admitted another attempt")
	}
	existing.PendingTerminal = &PendingTerminal{Action: "cleanup"}
	if err := journal.Save(existing); err != nil {
		t.Fatalf("full journal blocked existing outbox progress: %v", err)
	}
	if err := journal.Remove(existing.Attempt.ID); err != nil {
		t.Fatal(err)
	}
	if err := journal.Save(newRecord); err != nil {
		t.Fatalf("cleanup did not restore admission capacity: %v", err)
	}
}

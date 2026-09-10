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

package runtimehost

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

type Journal struct{ Root string }

type JournalRecord struct {
	Attempt         *controlmodel.ExecutionAttempt `json:"attempt"`
	Task            *controlmodel.AgentTask        `json:"task"`
	Context         *collaboration.ContextEnvelope `json:"context"`
	HostID          uuid.UUID                      `json:"hostId,omitempty"`
	AttemptToken    string                         `json:"attemptToken,omitempty"`
	Workspace       string                         `json:"workspace,omitempty"`
	Events          []provider.Event               `json:"events,omitempty"`
	PendingTerminal *PendingTerminal               `json:"pendingTerminal,omitempty"`
	UpdatedAt       time.Time                      `json:"updatedAt"`
}

type PendingTerminal struct {
	Action         string          `json:"action"`
	Result         json.RawMessage `json:"result,omitempty"`
	Checkpoint     json.RawMessage `json:"checkpoint,omitempty"`
	FailureCode    string          `json:"failureCode,omitempty"`
	FailureMessage string          `json:"failureMessage,omitempty"`
}

func (j *Journal) path(id uuid.UUID) string {
	return filepath.Join(j.Root, "execution-attempts", id.String()+".json")
}

func (j *Journal) Save(record *JournalRecord) error {
	if record == nil || record.Attempt == nil {
		return fmt.Errorf("journal record execution is required")
	}
	path := j.path(record.Attempt.ID)
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return err
	}
	record.UpdatedAt = time.Now().UTC()
	data, err := json.MarshalIndent(record, "", "  ")
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func (j *Journal) Remove(id uuid.UUID) error {
	err := os.Remove(j.path(id))
	if os.IsNotExist(err) {
		return nil
	}
	return err
}

// List loads durable attempt records in stable order. Terminal records form a
// local outbox and are replayed after daemon restarts.
func (j *Journal) List() ([]*JournalRecord, error) {
	dir := filepath.Join(j.Root, "execution-attempts")
	entries, err := os.ReadDir(dir)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	paths := make([]string, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() && filepath.Ext(entry.Name()) == ".json" {
			paths = append(paths, filepath.Join(dir, entry.Name()))
		}
	}
	sort.Strings(paths)
	records := make([]*JournalRecord, 0, len(paths))
	for _, path := range paths {
		data, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil, readErr
		}
		var record JournalRecord
		if unmarshalErr := json.Unmarshal(data, &record); unmarshalErr != nil || record.Attempt == nil || record.Attempt.ID == uuid.Nil {
			if unmarshalErr == nil {
				unmarshalErr = fmt.Errorf("missing execution attempt")
			}
			return nil, fmt.Errorf("decode runtime host journal %s: %w", path, unmarshalErr)
		}
		records = append(records, &record)
	}
	return records, nil
}

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
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
)

type Journal struct{ Root string }

type JournalRecord struct {
	Attempt   *controlmodel.ExecutionAttempt `json:"attempt"`
	Task      *controlmodel.AgentTask        `json:"task"`
	Context   *collaboration.ContextEnvelope `json:"context"`
	Workspace string                         `json:"workspace,omitempty"`
	Events    []provider.Event               `json:"events,omitempty"`
	UpdatedAt time.Time                      `json:"updatedAt"`
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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package orchestration

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
)

type coordinatorCompletionIntent struct {
	Version  int             `json:"version"`
	Accepted bool            `json:"accepted"`
	Output   json.RawMessage `json:"output"`
	Actor    model.Actor     `json:"actor"`
}

func coordinatorIntentPath(task *model.AgentTask) string {
	return store.CoordinatorCompletionIntentPath(task.Namespace)
}

func canonicalCoordinatorOutput(raw json.RawMessage) (json.RawMessage, error) {
	return store.CanonicalCoordinatorOutput(raw)
}

// Persist the semantic decision before closing its physical Attempt. Recovery
// may only complete a leader that explicitly requested this operation, and it
// must still pass the full transactional barrier.
func (s *Service) RequestCoordinatorCompletion(ctx context.Context, task *model.AgentTask, output json.RawMessage, actor model.Actor) error {
	if task == nil || !task.LeaderTask {
		return store.ErrConflict
	}
	canonical, err := canonicalCoordinatorOutput(output)
	if err != nil {
		return err
	}
	intent := coordinatorCompletionIntent{Version: 1, Output: canonical, Actor: actor}
	raw, err := json.Marshal(intent)
	if err != nil {
		return err
	}
	stored, err := s.Store.Orchestration().SaveCoordinatorCompletionIntent(ctx, task.ID, task.Version, raw)
	if err != nil {
		return err
	}
	var previous coordinatorCompletionIntent
	if err = json.Unmarshal(stored, &previous); err != nil {
		return err
	}
	old, err := canonicalCoordinatorOutput(previous.Output)
	if err != nil {
		return err
	}
	if !bytes.Equal(old, canonical) {
		return fmt.Errorf("%w: coordinator completion output changed", store.ErrConflict)
	}
	return nil
}

func (s *Service) coordinatorCompletionIntent(ctx context.Context, task *model.AgentTask) (*coordinatorCompletionIntent, error) {
	row, err := s.Store.KV().Get(ctx, task.Tenant, coordinatorIntentPath(task), task.ID.String())
	if err != nil {
		return nil, err
	}
	if len(row.Value) > store.MaxRunGraphBytes+4096 {
		return nil, store.ErrResourceLimit
	}
	var intent coordinatorCompletionIntent
	if err = json.Unmarshal(row.Value, &intent); err != nil {
		return nil, err
	}
	if intent.Version != 1 {
		return nil, fmt.Errorf("unsupported coordinator completion intent version")
	}
	return &intent, nil
}

func (s *Service) recoverCoordinatorCompletion(ctx context.Context, task *model.AgentTask) (bool, error) {
	intent, err := s.coordinatorCompletionIntent(ctx, task)
	if errors.Is(err, store.ErrNotFound) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if !intent.Accepted {
		return false, nil
	}
	_, err = s.completeCoordinatorNode(ctx, task.ID, intent.Output, intent.Actor)
	if errors.Is(err, store.ErrConflict) {
		return false, nil
	}
	return err == nil, err
}

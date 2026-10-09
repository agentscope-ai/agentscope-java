/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package invocation

import (
	"context"
	"encoding/json"
	"errors"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

type contractContextKey struct{}

func WithContract(ctx context.Context, c *Contract) context.Context {
	return context.WithValue(ctx, contractContextKey{}, c)
}
func ContextContract(ctx context.Context) *Contract {
	c, _ := ctx.Value(contractContextKey{}).(*Contract)
	return c
}
func ContextDefinition(ctx context.Context, id string) (json.RawMessage, bool) {
	c := ContextContract(ctx)
	if c == nil {
		return nil, false
	}
	raw, ok := c.Definitions[id]
	return raw, ok
}
func ContextVersion(ctx context.Context, id string) int {
	raw, ok := ContextDefinition(ctx, id)
	if !ok {
		return 0
	}
	var d struct {
		Version int `json:"version"`
	}
	_ = json.Unmarshal(raw, &d)
	return d.Version
}
func ContractForIssue(ctx context.Context, st store.Store, id uuid.UUID) (*Contract, error) {
	if c := ContextContract(ctx); c != nil {
		return c, nil
	}
	seen := map[uuid.UUID]bool{}
	for id != uuid.Nil && !seen[id] {
		seen[id] = true
		issue, err := st.Collaboration().GetIssue(ctx, id)
		if errors.Is(err, store.ErrNotFound) {
			return nil, nil
		}
		if err != nil {
			return nil, err
		}
		if issue.SourceType == "endpoint" || issue.SourceType == "session_turn" {
			invID, err := uuid.Parse(issue.SourceRef)
			if err != nil {
				return nil, err
			}
			inv, err := st.Endpoints().GetInvocation(ctx, invID)
			if err != nil {
				return nil, err
			}
			return ReadContract(inv.Contract)
		}
		if issue.ParentIssueID == nil {
			break
		}
		id = *issue.ParentIssueID
	}
	return nil, nil
}
func TaskContext(ctx context.Context, st store.Store, task *model.AgentTask) (context.Context, error) {
	c, err := ContractForIssue(ctx, st, task.IssueID)
	if err != nil {
		return ctx, err
	}
	if c != nil {
		ctx = WithContract(ctx, c)
	}
	return ctx, nil
}
func RuntimePolicy(ctx context.Context, st store.Store, task *model.AgentTask) (*model.AgentRuntimePolicy, error) {
	c, err := ContractForIssue(ctx, st, task.IssueID)
	if err != nil {
		return nil, err
	}
	if c != nil {
		if p, ok := c.Policies[task.AgentRef]; ok {
			return p, nil
		}
		return nil, store.ErrNotFound
	}
	return st.Orchestration().GetRuntimePolicy(ctx, task.Tenant, task.Namespace, task.AgentRef)
}

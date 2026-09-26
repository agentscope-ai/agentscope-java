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

package memory

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type commandRepo struct{ s *Store }

func (r *commandRepo) Insert(_ context.Context, cmd *store.SessionCommand) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if cmd.ID == uuid.Nil {
		cmd.ID = uuid.New()
	}
	if cmd.RequestedAt.IsZero() {
		cmd.RequestedAt = time.Now().UTC()
	}
	if cmd.Status == "" {
		cmd.Status = store.CommandStatusAccepted
	}
	cp := cloneCommand(cmd)
	r.s.commands = append(r.s.commands, *cp)
	return nil
}

func (r *commandRepo) Update(_ context.Context, cmd *store.SessionCommand) error {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	for i := range r.s.commands {
		if r.s.commands[i].ID != cmd.ID {
			continue
		}
		r.s.commands[i].Status = cmd.Status
		r.s.commands[i].Code = cmd.Code
		r.s.commands[i].Error = cmd.Error
		r.s.commands[i].CompletedAt = cmd.CompletedAt
		r.s.commands[i].DurationMs = cmd.DurationMs
		return nil
	}
	return store.ErrNotFound
}

func (r *commandRepo) GetByCommandID(_ context.Context, commandID string) (*store.SessionCommand, error) {
	if commandID == "" {
		return nil, store.ErrNotFound
	}
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	for i := range r.s.commands {
		if r.s.commands[i].CommandID == commandID {
			return cloneCommand(&r.s.commands[i]), nil
		}
	}
	return nil, store.ErrNotFound
}

func (r *commandRepo) List(_ context.Context, f store.SessionCommandFilter) ([]*store.SessionCommand, error) {
	r.s.mu.RLock()
	defer r.s.mu.RUnlock()
	var out []*store.SessionCommand
	for i := range r.s.commands {
		c := &r.s.commands[i]
		if f.SessionFK != uuid.Nil {
			if c.SessionFK == nil || *c.SessionFK != f.SessionFK {
				continue
			}
		}
		if f.AgentName != "" && c.AgentName != f.AgentName {
			continue
		}
		if f.Namespace != "" && c.Namespace != f.Namespace {
			continue
		}
		if f.Status != "" && c.Status != f.Status {
			continue
		}
		if f.Since != nil && c.RequestedAt.Before(*f.Since) {
			continue
		}
		out = append(out, cloneCommand(c))
	}
	for i := 0; i < len(out); i++ {
		for j := i + 1; j < len(out); j++ {
			if out[j].RequestedAt.After(out[i].RequestedAt) {
				out[i], out[j] = out[j], out[i]
			}
		}
	}
	limit := f.Limit
	if limit <= 0 {
		limit = 50
	}
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

func cloneCommand(c *store.SessionCommand) *store.SessionCommand {
	cp := *c
	if c.SessionFK != nil {
		fk := *c.SessionFK
		cp.SessionFK = &fk
	}
	if c.CompletedAt != nil {
		t := *c.CompletedAt
		cp.CompletedAt = &t
	}
	return &cp
}

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

package team

import (
	"context"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// TaskStore adapts store.TeamTaskRepository to the synchronous, context-free
// API used by REST handlers, controllers, and CLI callers. All calls use
// context.Background() since the underlying store operations are fast and
// callers historically did not thread a context through this layer.
type TaskStore struct {
	Repo store.TeamTaskRepository
}

// NewTaskStore creates a TaskStore backed by the given repository.
func NewTaskStore(repo store.TeamTaskRepository) *TaskStore {
	return &TaskStore{Repo: repo}
}

func (s *TaskStore) Create(namespace, teamName, subject, description string, blockedBy []string) (*store.TeamTask, error) {
	return s.Repo.Create(context.Background(), namespace, teamName, subject, description, blockedBy, "")
}

func (s *TaskStore) CreateWithOwner(namespace, teamName, subject, description string, blockedBy []string, owner string) (*store.TeamTask, error) {
	return s.Repo.Create(context.Background(), namespace, teamName, subject, description, blockedBy, owner)
}

func (s *TaskStore) Get(namespace, teamName, taskID string) (*store.TeamTask, error) {
	return s.Repo.Get(context.Background(), namespace, teamName, taskID)
}

func (s *TaskStore) List(namespace, teamName string) []*store.TeamTask {
	tasks, _ := s.Repo.List(context.Background(), namespace, teamName)
	return tasks
}

func (s *TaskStore) Assign(namespace, teamName, taskID, owner string, expectedVersion int64) (*store.TeamTask, error) {
	return s.Repo.Assign(context.Background(), namespace, teamName, taskID, owner, expectedVersion)
}

func (s *TaskStore) Claim(namespace, teamName, taskID, claimedBy string, expectedVersion int64) (*store.TeamTask, error) {
	return s.Repo.Claim(context.Background(), namespace, teamName, taskID, claimedBy, expectedVersion)
}

func (s *TaskStore) Complete(namespace, teamName, taskID, result string) (*store.TeamTask, error) {
	return s.Repo.Complete(context.Background(), namespace, teamName, taskID, result)
}

func (s *TaskStore) Fail(namespace, teamName, taskID, reason string) (*store.TeamTask, error) {
	return s.Repo.Fail(context.Background(), namespace, teamName, taskID, reason)
}

func (s *TaskStore) Unclaim(namespace, teamName, taskID string) (*store.TeamTask, error) {
	return s.Repo.Unclaim(context.Background(), namespace, teamName, taskID)
}

func (s *TaskStore) GetUnblockedPending(namespace, teamName string) []*store.TeamTask {
	tasks, _ := s.Repo.GetUnblockedPending(context.Background(), namespace, teamName)
	return tasks
}

func (s *TaskStore) GetSummary(namespace, teamName string) (total, pending, inProgress, completed int32) {
	total, pending, inProgress, completed, _ = s.Repo.GetSummary(context.Background(), namespace, teamName)
	return
}

func (s *TaskStore) DeleteTeam(namespace, teamName string) {
	_ = s.Repo.DeleteByTeam(context.Background(), namespace, teamName)
}

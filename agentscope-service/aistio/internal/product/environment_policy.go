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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package product

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/jackc/pgx/v5"
)

const localEnvironmentType = "local"

var (
	// ErrLocalEnvironmentDisabled is returned whenever a new binding would let
	// Managed Agent code execute in the data-plane process filesystem/shell.
	ErrLocalEnvironmentDisabled = errors.New("local environments are disabled by deployment policy")
	ErrEnvironmentNotAvailable  = errors.New("environment not found, not owned by the user, or archived")
	ErrNoRunnableEnvironment    = errors.New("no runnable environment is bound")
)

func normalizeEnvironmentType(value string) string {
	typ := strings.ToLower(strings.TrimSpace(value))
	if typ == "" {
		return localEnvironmentType
	}
	return typ
}

// validateEnvironmentBinding applies the same policy to every path that can
// create a session, deployment, or Agent default. Existing sessions are not
// revalidated, so an in-flight local turn can finish after the policy changes.
func (s *Server) validateEnvironmentBinding(ctx context.Context, ownerID, environmentID string) (envRow, error) {
	id := strings.TrimSpace(environmentID)
	if id == "" {
		return envRow{}, ErrNoRunnableEnvironment
	}
	var environment envRow
	err := s.db.Pool.QueryRow(ctx,
		envSelect+` WHERE environment_id=$1 AND owner_id=$2 AND archived_at IS NULL`, id, ownerID).Scan(
		&environment.EnvironmentID, &environment.OwnerID, &environment.Name, &environment.Type,
		&environment.ConfigJSON, &environment.ArchivedAt, &environment.CreatedAt, &environment.UpdatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return envRow{}, ErrEnvironmentNotAvailable
	}
	if err != nil {
		return envRow{}, fmt.Errorf("load environment: %w", err)
	}
	if normalizeEnvironmentType(environment.Type) == localEnvironmentType && !s.cfg.AllowLocalEnvironment {
		return envRow{}, ErrLocalEnvironmentDisabled
	}
	return environment, nil
}

func (s *Server) defaultEnvironmentForAgentCreate(ctx context.Context, ownerID, requestedID string) (string, error) {
	requestedID = strings.TrimSpace(requestedID)
	if requestedID != "" {
		if _, err := s.validateEnvironmentBinding(ctx, ownerID, requestedID); err != nil {
			return "", err
		}
		return requestedID, nil
	}
	if !s.cfg.AllowLocalEnvironment {
		return "", nil
	}
	return s.ensureDefaultLocalEnvironment(ctx, ownerID)
}

func environmentBindingHTTPStatus(err error) int {
	if errors.Is(err, ErrLocalEnvironmentDisabled) ||
		errors.Is(err, ErrEnvironmentNotAvailable) ||
		errors.Is(err, ErrNoRunnableEnvironment) {
		return 400
	}
	return 500
}

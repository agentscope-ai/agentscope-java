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

package policychanges

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
)

var ErrClaim = errors.New("policy change claim is stale")

// Change contains only invalidation scopes, never credentials or resource contents.
type Change struct {
	ID            uuid.UUID `json:"id"`
	Tenant        string    `json:"tenant"`
	Namespace     string    `json:"namespace"`
	ApplicationID string    `json:"application_id"`
	ClaimToken    string    `json:"claim_token"`
	CreatedAt     time.Time `json:"created_at"`
}

type Repository interface {
	Claim(context.Context, time.Time, time.Duration, int) ([]Change, error)
	Acknowledge(context.Context, uuid.UUID, string) error
}

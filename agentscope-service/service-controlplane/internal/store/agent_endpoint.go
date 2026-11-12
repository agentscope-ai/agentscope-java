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

package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
)

type EndpointInvocationFilter struct {
	ConversationID uuid.UUID
	ApplicationID  uuid.UUID
	DueBefore      *time.Time
	EndpointID     uuid.UUID
	RunID          uuid.UUID
	Mode           controlmodel.EndpointInvocationMode
	Status         controlmodel.EndpointInvocationStatus
	ActiveOnly     bool
	OldestFirst    bool
	Offset         int
	Limit          int
}

type EndpointRepository interface {
	Create(context.Context, *controlmodel.Endpoint) (*controlmodel.Endpoint, error)
	Get(context.Context, uuid.UUID) (*controlmodel.Endpoint, error)
	GetBySlug(context.Context, string) (*controlmodel.Endpoint, error)
	List(context.Context, string, string) ([]*controlmodel.Endpoint, error)
	Update(context.Context, *controlmodel.Endpoint, int64) (*controlmodel.Endpoint, error)
	DeployRelease(context.Context, uuid.UUID, controlmodel.EndpointTargetType, uuid.UUID, int64, controlmodel.Actor, string, ...json.RawMessage) (*controlmodel.Endpoint, *controlmodel.EndpointRelease, error)
	GetRelease(context.Context, uuid.UUID, uuid.UUID) (*controlmodel.EndpointRelease, error)
	ListReleases(context.Context, uuid.UUID) ([]*controlmodel.EndpointRelease, error)

	CreateCredential(context.Context, *controlmodel.EndpointCredential) (*controlmodel.EndpointCredential, error)
	ListCredentials(context.Context, uuid.UUID) ([]*controlmodel.EndpointCredential, error)
	GetCredentialByPrefix(context.Context, uuid.UUID, string) (*controlmodel.EndpointCredential, error)
	UpdateCredential(context.Context, *controlmodel.EndpointCredential) (*controlmodel.EndpointCredential, error)
	TouchCredential(context.Context, uuid.UUID, time.Time) error
	ConsumeRateLimit(context.Context, uuid.UUID, string, int, int, time.Time) (bool, time.Duration, error)

	ReserveInvocation(context.Context, *controlmodel.EndpointInvocation) (*controlmodel.EndpointInvocation, bool, error)
	GetInvocation(context.Context, uuid.UUID) (*controlmodel.EndpointInvocation, error)
	GetInvocationByIdempotency(context.Context, uuid.UUID, controlmodel.EndpointInvocationMode, string, string) (*controlmodel.EndpointInvocation, error)
	CountActiveInvocations(context.Context, EndpointInvocationFilter) (int, error)
	ClaimInvocationPoll(context.Context, uuid.UUID, time.Time, time.Time) (bool, error)
	ListInvocations(context.Context, EndpointInvocationFilter) ([]*controlmodel.EndpointInvocation, error)
	UpdateInvocation(context.Context, *controlmodel.EndpointInvocation) (*controlmodel.EndpointInvocation, error)
	ScheduleInvocation(context.Context, uuid.UUID, time.Time) error
	// RecordInvocationTokens monotonically records usage and atomically charges its Application once.
	RecordInvocationTokens(context.Context, uuid.UUID, int64) error

	CreateConversation(context.Context, *controlmodel.EndpointConversation) (*controlmodel.EndpointConversation, error)
	GetConversation(context.Context, uuid.UUID) (*controlmodel.EndpointConversation, error)
	UpdateConversation(context.Context, *controlmodel.EndpointConversation) (*controlmodel.EndpointConversation, error)
}

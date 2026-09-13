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
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

type EndpointInvocationFilter struct {
	EndpointID uuid.UUID
	RunID      uuid.UUID
	Mode       controlmodel.EndpointInvocationMode
	Status     controlmodel.EndpointInvocationStatus
	ActiveOnly bool
	Limit      int
}

type EndpointRepository interface {
	Create(context.Context, *controlmodel.Endpoint) (*controlmodel.Endpoint, error)
	Get(context.Context, uuid.UUID) (*controlmodel.Endpoint, error)
	GetBySlug(context.Context, string) (*controlmodel.Endpoint, error)
	List(context.Context, string, string) ([]*controlmodel.Endpoint, error)
	Update(context.Context, *controlmodel.Endpoint, int64) (*controlmodel.Endpoint, error)
	DeployRelease(context.Context, uuid.UUID, controlmodel.EndpointTargetType, uuid.UUID, int64, controlmodel.Actor, string) (*controlmodel.Endpoint, *controlmodel.EndpointRelease, error)
	GetRelease(context.Context, uuid.UUID, uuid.UUID) (*controlmodel.EndpointRelease, error)
	ListReleases(context.Context, uuid.UUID) ([]*controlmodel.EndpointRelease, error)

	CreateCredential(context.Context, *controlmodel.EndpointCredential) (*controlmodel.EndpointCredential, error)
	ListCredentials(context.Context, uuid.UUID) ([]*controlmodel.EndpointCredential, error)
	GetCredentialByPrefix(context.Context, uuid.UUID, string) (*controlmodel.EndpointCredential, error)
	UpdateCredential(context.Context, *controlmodel.EndpointCredential) (*controlmodel.EndpointCredential, error)
	ConsumeRateLimit(context.Context, uuid.UUID, string, int, int, time.Time) (bool, time.Duration, error)

	ReserveInvocation(context.Context, *controlmodel.EndpointInvocation) (*controlmodel.EndpointInvocation, bool, error)
	GetInvocation(context.Context, uuid.UUID) (*controlmodel.EndpointInvocation, error)
	ListInvocations(context.Context, EndpointInvocationFilter) ([]*controlmodel.EndpointInvocation, error)
	UpdateInvocation(context.Context, *controlmodel.EndpointInvocation) (*controlmodel.EndpointInvocation, error)

	CreateConversation(context.Context, *controlmodel.EndpointConversation) (*controlmodel.EndpointConversation, error)
	GetConversation(context.Context, uuid.UUID) (*controlmodel.EndpointConversation, error)
	UpdateConversation(context.Context, *controlmodel.EndpointConversation) (*controlmodel.EndpointConversation, error)
}

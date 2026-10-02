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
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

type TeamProposalRepository interface {
	Create(context.Context, *controlmodel.TeamProposal) (*controlmodel.TeamProposal, error)
	Get(context.Context, uuid.UUID) (*controlmodel.TeamProposal, error)
	Confirm(context.Context, uuid.UUID, int64, uuid.UUID) (*controlmodel.TeamProposal, error)
}

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

type ChatFilter struct {
	Tenant     string
	Namespace  string
	CreatorRef string
	Archived   bool
	Deleted    bool
	Limit      int
	Offset     int
}

// ChatRepository persists the user-facing Chat aggregate independently of the
// runtime Session inventory.
type ChatRepository interface {
	Create(ctx context.Context, chat *controlmodel.Chat) (*controlmodel.Chat, error)
	Get(ctx context.Context, id uuid.UUID) (*controlmodel.Chat, error)
	List(ctx context.Context, filter ChatFilter) ([]*controlmodel.Chat, error)
	Update(ctx context.Context, chat *controlmodel.Chat, expectedVersion int64) (*controlmodel.Chat, error)
	Touch(ctx context.Context, id uuid.UUID) error
}

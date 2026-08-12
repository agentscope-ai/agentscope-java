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

package store

import (
	"context"
	"fmt"
)

// Opener opens a Store for a given driver. Sub-packages register themselves
// via RegisterOpener in init().
type Opener func(ctx context.Context, cfg Config) (Store, error)

var openers = map[string]Opener{}

// RegisterOpener registers a driver opener. Called from sub-package init().
func RegisterOpener(driver string, opener Opener) {
	openers[driver] = opener
}

// Open creates a Store for the configured driver and runs Migrate.
func Open(ctx context.Context, cfg Config) (Store, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	opener, ok := openers[cfg.Driver]
	if !ok {
		return nil, fmt.Errorf("store: no opener registered for driver %q (did you import the driver package?)", cfg.Driver)
	}
	s, err := opener(ctx, cfg)
	if err != nil {
		return nil, err
	}
	if err := s.Migrate(ctx); err != nil {
		_ = s.Close()
		return nil, fmt.Errorf("store: migrate: %w", err)
	}
	return s, nil
}

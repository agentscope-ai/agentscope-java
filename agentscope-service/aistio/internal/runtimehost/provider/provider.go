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

package provider

import (
	"context"
	"encoding/json"
)

type Request struct {
	Prompt            string
	Workspace         string
	ProviderSessionID string
	Configuration     json.RawMessage
}

type Event struct {
	Type              string          `json:"type"`
	ProviderSessionID string          `json:"providerSessionId,omitempty"`
	Raw               json.RawMessage `json:"raw"`
}

type Result struct {
	ProviderSessionID string          `json:"providerSessionId,omitempty"`
	Output            string          `json:"output,omitempty"`
	Checkpoint        json.RawMessage `json:"checkpoint,omitempty"`
}

type EventSink func(Event) error

// Adapter is the process-level contract implemented by Codex, Claude Code,
// and future coding runtimes.
type Adapter interface {
	Name() string
	Detect(ctx context.Context) (version string, err error)
	Run(ctx context.Context, request Request, sink EventSink) (*Result, error)
}

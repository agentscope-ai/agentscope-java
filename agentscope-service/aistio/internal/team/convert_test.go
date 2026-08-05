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

package team_test

import (
	"encoding/json"
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/team"
)

func TestDefaultSpecExtra_SurvivesRoundTrip(t *testing.T) {
	extra := team.DefaultSpecExtra()
	if extra.DynamicMembers == nil || !extra.DynamicMembers.Enabled {
		t.Fatalf("dynamic members should be enabled by default: %+v", extra.DynamicMembers)
	}
	if extra.Recovery == nil || extra.Recovery.ReschedulePolicy != "Auto" {
		t.Fatalf("recovery should default to Auto: %+v", extra.Recovery)
	}
	if extra.Lifecycle == nil || extra.Lifecycle.TTLAfterCompleted == "" || extra.Lifecycle.TTLAfterFailed == "" {
		t.Fatalf("lifecycle TTLs should be set: %+v", extra.Lifecycle)
	}

	raw, err := json.Marshal(extra)
	if err != nil {
		t.Fatal(err)
	}
	parsed := team.ParseSpecExtra(&store.Team{SpecExtra: raw})
	if parsed.Recovery == nil || parsed.Recovery.MaxRestarts != extra.Recovery.MaxRestarts {
		t.Fatalf("round trip lost recovery: %+v", parsed.Recovery)
	}
	if parsed.Lifecycle == nil || parsed.Lifecycle.TTLAfterCompleted != extra.Lifecycle.TTLAfterCompleted {
		t.Fatalf("round trip lost lifecycle: %+v", parsed.Lifecycle)
	}
	if parsed.DynamicMembers == nil || parsed.DynamicMembers.MaxTotal != extra.DynamicMembers.MaxTotal {
		t.Fatalf("round trip lost dynamic members: %+v", parsed.DynamicMembers)
	}
}

func TestParseSpecExtra_EmptyTeam(t *testing.T) {
	extra := team.ParseSpecExtra(nil)
	if extra.Recovery != nil || extra.Lifecycle != nil || extra.DynamicMembers != nil {
		t.Fatalf("nil team should parse to zero SpecExtra: %+v", extra)
	}
}

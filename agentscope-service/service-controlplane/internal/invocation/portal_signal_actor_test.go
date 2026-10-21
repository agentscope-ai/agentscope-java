// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package invocation

import (
	"encoding/json"
	"errors"
	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
	"testing"
)

func TestPortalSignalAttributionCannotTurnTaskProofIntoHumanApproval(t *testing.T) {
	actor := map[string]any{"user": "owner", "key_hash": "", "internal": false,
		"agent_task": uuid.NewString(), "agent_attempt": uuid.NewString(), "agent_fence": json.Number("7"), "agent_ref": "agent-1"}
	result, err := PortalSignalActor(actor)
	if err != nil || result.Type != model.ActorAgent || result.Ref != "agent-1" {
		t.Fatalf("task attribution=%+v, %v", result, err)
	}
	actor["agent_fence"] = 0
	if _, err = PortalSignalActor(actor); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("stale proof=%v", err)
	}
	delete(actor, "agent_task")
	if _, err = PortalSignalActor(actor); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("partial proof=%v", err)
	}
	result, err = PortalSignalActor(map[string]any{"user": "owner"})
	if err != nil || result.Type != model.ActorHuman || result.Ref != "owner" {
		t.Fatalf("human attribution=%+v, %v", result, err)
	}
}

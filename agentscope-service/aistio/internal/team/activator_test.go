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
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/store/memory"
	"github.com/spring-ai-alibaba/aistio/internal/team"
)

type fakeManagedAPI struct {
	mu       sync.Mutex
	sessions map[string]string // externalKey -> sessionID
	wakes    []wakeCall
	deleted  []wakeCall
	nextID   int
}

type wakeCall struct {
	sessionID string
	ownerID   string
	text      string
}

func (f *fakeManagedAPI) FindOrCreateSessionID(_ context.Context, ownerID, agentID, _, externalKey string) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.sessions == nil {
		f.sessions = map[string]string{}
	}
	if id, ok := f.sessions[externalKey]; ok {
		return id, nil
	}
	f.nextID++
	id := fmt.Sprintf("sess_managed_%d", f.nextID)
	f.sessions[externalKey] = id
	_ = ownerID
	_ = agentID
	return id, nil
}

func (f *fakeManagedAPI) PostSessionWakeEvent(_ context.Context, sessionID, ownerID, text string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.wakes = append(f.wakes, wakeCall{sessionID: sessionID, ownerID: ownerID, text: text})
	return nil
}

func (f *fakeManagedAPI) DeleteManagedSession(_ context.Context, ownerID, sessionID string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.deleted = append(f.deleted, wakeCall{sessionID: sessionID, ownerID: ownerID})
	return nil
}

func TestActivateManaged_FindOrCreateBindAndWake(t *testing.T) {
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()

	teamRow, err := st.Teams().Create(ctx, &store.Team{
		Name: "research", Namespace: "default", Objective: "ship it", Phase: store.TeamPhasePending,
	})
	if err != nil {
		t.Fatal(err)
	}
	member, err := st.Teams().UpsertMember(ctx, &store.TeamMember{
		TeamName:       teamRow.Name,
		Namespace:      teamRow.Namespace,
		MemberName:     "lead",
		AgentRef:       "lead-agent",
		DeployMode:     store.MemberDeployManaged,
		ManagedAgentID: "agt_lead",
		OwnerID:        "user_1",
		Phase:          store.MemberPhaseJoining,
	})
	if err != nil {
		t.Fatal(err)
	}

	api := &fakeManagedAPI{}
	act := team.NewActivator(st, nil, nil)
	act.SetManagedSessionAPI(api)

	teamCtx := &team.TeamContext{
		TeamName: "research", Objective: "ship it", MyRole: "lead", IsLead: true,
		AvailableActions: []string{"createTask"},
	}
	sess, err := act.ActivateManaged(ctx, teamRow, member, teamCtx)
	if err != nil {
		t.Fatal(err)
	}
	if sess.SessionID == "" || sess.SessionID != "sess_managed_1" {
		t.Fatalf("session id = %q", sess.SessionID)
	}
	if len(sess.TeamContext) == 0 {
		t.Fatal("expected TeamContext on store session")
	}
	var parsed team.TeamContext
	if err := json.Unmarshal(sess.TeamContext, &parsed); err != nil {
		t.Fatal(err)
	}
	if !parsed.IsLead || parsed.Objective != "ship it" {
		t.Fatalf("parsed context = %+v", parsed)
	}

	got, err := st.Teams().GetMember(ctx, "default", "research", "lead")
	if err != nil {
		t.Fatal(err)
	}
	if got.SessionID != "sess_managed_1" || got.ManagedSessionID != "sess_managed_1" {
		t.Fatalf("bind = session=%q managed=%q", got.SessionID, got.ManagedSessionID)
	}
	if len(api.wakes) != 1 || api.wakes[0].sessionID != "sess_managed_1" || api.wakes[0].ownerID != "user_1" {
		t.Fatalf("wakes = %+v", api.wakes)
	}

	// Idempotent find-or-create key reuses the same product session id.
	sess2, err := act.ActivateManaged(ctx, teamRow, member, teamCtx)
	if err != nil {
		t.Fatal(err)
	}
	if sess2.SessionID != sess.SessionID {
		t.Fatalf("expected idempotent session id, got %q vs %q", sess2.SessionID, sess.SessionID)
	}
	if len(api.wakes) != 2 {
		t.Fatalf("expected second wake, got %d", len(api.wakes))
	}
}

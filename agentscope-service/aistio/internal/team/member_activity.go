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

/*
Copyright 2026 the original author or authors.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package team

import (
	"context"
	"errors"
	"strings"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// SyncMemberPhaseFromSessionStatus maps a product session runtime status onto the
// team member bound to that session (if any). Used when Managed turns flip
// session status via PATCH /api/internal/sessions/{id}/runtime.
//
// After updating the member, reconciles Team phase between Running and Idle based
// on the roster (all active members Idle → Team Idle; any Working/Joining → Running).
//
// No-op when the session is not a team member, status is unrecognized, or the
// member is already in a terminal phase (Failed / Shutdown / Lost).
func SyncMemberPhaseFromSessionStatus(ctx context.Context, st store.Store, sessionID, status string) error {
	if st == nil || sessionID == "" {
		return nil
	}
	phase := memberPhaseForSessionStatus(status)
	if phase == "" {
		return nil
	}
	m, err := st.Teams().FindMemberBySessionID(ctx, sessionID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return nil
		}
		return err
	}
	switch m.Phase {
	case store.MemberPhaseFailed, store.MemberPhaseShutdown, store.MemberPhaseLost:
		return nil
	}
	if m.Phase != phase {
		if err := st.Teams().UpdateMemberPhase(ctx, m.Namespace, m.TeamName, m.MemberName, phase); err != nil {
			return err
		}
	}
	return ReconcileTeamActivityPhase(ctx, st, m.Namespace, m.TeamName)
}

// ReconcileTeamActivityPhase sets Team phase to Idle when every non-terminal
// member is Idle, or Running when any is Joining/Working. Does not touch
// Pending / Completed / Failed.
func ReconcileTeamActivityPhase(ctx context.Context, st store.Store, namespace, teamName string) error {
	if st == nil || teamName == "" {
		return nil
	}
	t, err := st.Teams().Get(ctx, namespace, teamName)
	if err != nil {
		return err
	}
	switch t.Phase {
	case store.TeamPhasePending, store.TeamPhaseCompleted, store.TeamPhaseFailed:
		return nil
	case store.TeamPhaseRunning, store.TeamPhaseIdle:
		// ok
	default:
		return nil
	}

	members, err := st.Teams().ListMembers(ctx, namespace, teamName)
	if err != nil {
		return err
	}

	alive := 0
	busy := 0
	for _, m := range members {
		switch m.Phase {
		case store.MemberPhaseFailed, store.MemberPhaseShutdown, store.MemberPhaseLost:
			continue
		case store.MemberPhaseJoining, store.MemberPhaseWorking:
			alive++
			busy++
		case store.MemberPhaseIdle:
			alive++
		default:
			// Unknown phase: treat as busy so we don't falsely Idle the team.
			alive++
			busy++
		}
	}
	if alive == 0 {
		return nil
	}

	desired := store.TeamPhaseRunning
	if busy == 0 {
		desired = store.TeamPhaseIdle
	}
	if t.Phase == desired {
		return nil
	}
	return st.Teams().UpdatePhase(ctx, namespace, teamName, desired)
}

func memberPhaseForSessionStatus(status string) string {
	switch strings.ToLower(strings.TrimSpace(status)) {
	case "idle":
		return store.MemberPhaseIdle
	case "running", "requires_action":
		return store.MemberPhaseWorking
	default:
		return ""
	}
}

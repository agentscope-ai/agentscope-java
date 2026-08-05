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

package team

import (
	"encoding/json"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// SpecExtra is the JSON blob stored in store.Team.SpecExtra.
type SpecExtra struct {
	DynamicMembers *v1alpha1.DynamicMembersSpec `json:"dynamicMembers,omitempty"`
	SharedContext  *v1alpha1.SharedContextSpec  `json:"sharedContext,omitempty"`
	Recovery       *v1alpha1.RecoverySpec       `json:"recovery,omitempty"`
	Lifecycle      *v1alpha1.TeamLifecycle      `json:"lifecycle,omitempty"`
}

// TeamConfigJSON is the JSON blob stored in store.Team.Config.
type TeamConfigJSON struct {
	// TaskClaimHint is informational only (assign + self-claim both always available).
	TaskClaimHint  string `json:"taskClaimStrategy,omitempty"`
	ShutdownPolicy string `json:"shutdownPolicy,omitempty"`
}

// FromCRD projects an AgentTeam CRD into the store-backed Team record (spec only).
func FromCRD(at *v1alpha1.AgentTeam) (*store.Team, error) {
	if at == nil {
		return nil, nil
	}
	extra := SpecExtra{
		DynamicMembers: at.Spec.DynamicMembers,
		SharedContext:  at.Spec.SharedContext,
		Recovery:       at.Spec.Recovery,
		Lifecycle:      at.Spec.Lifecycle,
	}
	extraJSON, err := json.Marshal(extra)
	if err != nil {
		return nil, err
	}
	cfg := TeamConfigJSON{TaskClaimHint: "both", ShutdownPolicy: "lead-decides"}
	if at.Spec.Config != nil {
		if at.Spec.Config.TaskClaimStrategy != "" {
			cfg.TaskClaimHint = at.Spec.Config.TaskClaimStrategy
		}
		if at.Spec.Config.ShutdownPolicy != "" {
			cfg.ShutdownPolicy = at.Spec.Config.ShutdownPolicy
		}
	}
	cfgJSON, err := json.Marshal(cfg)
	if err != nil {
		return nil, err
	}
	phase := string(at.Status.Phase)
	if phase == "" {
		phase = store.TeamPhasePending
	}
	return &store.Team{
		Name:       at.Name,
		Namespace:  at.Namespace,
		Objective:  at.Spec.Objective,
		Phase:      phase,
		LeadRef:    at.Spec.Lead.AgentRef.Name,
		LeadPrompt: at.Spec.Lead.Prompt,
		Config:     cfgJSON,
		SpecExtra:  extraJSON,
	}, nil
}

// MembersFromCRD returns store members derived from a CRD (lead + static members).
func MembersFromCRD(at *v1alpha1.AgentTeam) []*store.TeamMember {
	if at == nil {
		return nil
	}
	out := []*store.TeamMember{
		{
			TeamName:   at.Name,
			Namespace:  at.Namespace,
			MemberName: "lead",
			AgentRef:   at.Spec.Lead.AgentRef.Name,
			Prompt:     at.Spec.Lead.Prompt,
			Origin:     store.MemberOriginStatic,
			DeployMode: store.MemberDeployBYO,
			Phase:      store.MemberPhaseJoining,
		},
	}
	for _, m := range at.Spec.Members {
		out = append(out, &store.TeamMember{
			TeamName:     at.Name,
			Namespace:    at.Namespace,
			MemberName:   m.Name,
			AgentRef:     m.AgentRef.Name,
			Prompt:       m.Prompt,
			PlanApproval: m.PlanApproval,
			Origin:       store.MemberOriginStatic,
			DeployMode:   store.MemberDeployBYO,
			Phase:        store.MemberPhaseJoining,
		})
	}
	return out
}

// ApplyStoreStatusToCRD copies store member/task runtime onto CRD status for projection.
func ApplyStoreStatusToCRD(at *v1alpha1.AgentTeam, team *store.Team, members []*store.TeamMember, tasks *v1alpha1.TeamTaskSummary) {
	if at == nil || team == nil {
		return
	}
	at.Status.Phase = v1alpha1.TeamPhase(team.Phase)
	if team.StartedAt != nil {
		at.Status.StartedAt = team.StartedAt.Format("2006-01-02T15:04:05Z07:00")
	}
	at.Status.Tasks = tasks
	at.Status.Members = nil
	for _, m := range members {
		st := v1alpha1.TeamMemberStatus{
			Name:              m.MemberName,
			Origin:            v1alpha1.MemberOrigin(m.Origin),
			AgentRef:          m.AgentRef,
			SessionID:         m.SessionID,
			InstanceRef:       m.InstanceRef,
			Phase:             v1alpha1.MemberPhase(m.Phase),
			CurrentTask:       m.CurrentTask,
			RestartCount:      m.RestartCount,
			LastRestartReason: m.LastRestartReason,
		}
		if m.LastRestartAt != nil {
			st.LastRestartAt = m.LastRestartAt.Format("2006-01-02T15:04:05Z07:00")
		}
		if m.MemberName == "lead" {
			lead := st
			at.Status.Lead = &lead
			continue
		}
		at.Status.Members = append(at.Status.Members, st)
	}
}

// ParseSpecExtra unmarshals SpecExtra from a team record.
func ParseSpecExtra(team *store.Team) SpecExtra {
	var extra SpecExtra
	if team != nil && len(team.SpecExtra) > 0 {
		_ = json.Unmarshal(team.SpecExtra, &extra)
	}
	return extra
}

// DefaultSpecExtra returns store SpecExtra defaults for REST-created teams
// (Auto recovery + retention TTLs) so the shared TeamSweeper has policies to apply.
func DefaultSpecExtra() SpecExtra {
	return SpecExtra{
		DynamicMembers: &v1alpha1.DynamicMembersSpec{Enabled: true, MaxTotal: 8},
		Recovery: &v1alpha1.RecoverySpec{
			ReschedulePolicy: "Auto",
			MaxRestarts:      3,
			RestartBackoff:   "30s",
			GraceWindow:      "10s",
		},
		Lifecycle: &v1alpha1.TeamLifecycle{
			TTLAfterCompleted: "1h",
			TTLAfterFailed:    "24h",
		},
	}
}

// ParseTeamConfig unmarshals TeamConfigJSON from a team record.
func ParseTeamConfig(team *store.Team) TeamConfigJSON {
	cfg := TeamConfigJSON{TaskClaimHint: "both", ShutdownPolicy: "lead-decides"}
	if team != nil && len(team.Config) > 0 {
		_ = json.Unmarshal(team.Config, &cfg)
	}
	return cfg
}

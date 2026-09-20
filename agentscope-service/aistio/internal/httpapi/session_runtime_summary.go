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

package httpapi

import (
	"context"
	"encoding/json"
	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// SessionRuntimeSummary contains display metadata only. Provider credentials and
// definition snapshots remain outside this projection.
type SessionRuntimeSummary struct {
	Kind             model.DataPlaneKind `json:"kind,omitempty"`
	Source           string              `json:"source"`
	Provider         string              `json:"provider,omitempty"`
	Profile          string              `json:"profile,omitempty"`
	Pool             string              `json:"pool,omitempty"`
	HostID           *uuid.UUID          `json:"hostId,omitempty"`
	AttemptID        *uuid.UUID          `json:"attemptId,omitempty"`
	Framework        string              `json:"framework,omitempty"`
	FrameworkVersion string              `json:"frameworkVersion,omitempty"`
}

func (s *Server) enrichSessionRuntime(ctx context.Context, sess *store.Session, item *SessionWithSnapshot) {
	summary := &SessionRuntimeSummary{Source: "not_reported", Framework: sess.Framework, FrameworkVersion: sess.FrameworkVersion}
	// The attempt snapshot wins over a binding that may have changed since dispatch.
	attempts, err := s.store.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{Tenant: sess.Tenant, Namespace: sess.Namespace, AgentID: sess.AgentID, SessionID: sess.SessionID, NewestFirst: true, Limit: 20})
	if err == nil {
		for _, attempt := range attempts {
			if attempt.AgentID != sess.AgentID || attempt.SessionRef != nil && *attempt.SessionRef != sess.ID {
				continue
			}
			summary.Kind, summary.Source = attempt.BackendKind, "execution_attempt"
			summary.AttemptID, summary.HostID = &attempt.ID, attempt.HostID
			summary.Profile, summary.Pool = attempt.RuntimeProfileName, attempt.RuntimePoolName
			var snapshot model.RuntimeDispatchSnapshot
			if json.Unmarshal(attempt.RuntimeBinding, &snapshot) == nil {
				if snapshot.RuntimeProfile != nil {
					summary.Provider = snapshot.RuntimeProfile.Provider
					summary.Profile = snapshot.RuntimeProfile.Name
				}
				if snapshot.RuntimePool != nil {
					summary.Pool = snapshot.RuntimePool.Name
				}
			}
			break
		}
	}
	if summary.Kind == "" && sess.BindingID != uuid.Nil {
		if binding, err := s.store.AgentCatalog().GetBinding(ctx, sess.BindingID); err == nil && binding.AgentID == sess.AgentID && binding.Tenant == sess.Tenant && binding.Namespace == sess.Namespace {
			summary.Kind, summary.Source = binding.Kind, "session_binding"
		}
	}
	if summary.Kind == "" && sess.Framework == "managed" {
		summary.Kind, summary.Source = model.DataPlaneManaged, "session_report"
	}
	item.Runtime = summary
	// External health and capabilities must come from this exact generation.
	if summary.Kind == model.DataPlaneExternalApplication && sess.AgentInstanceID != uuid.Nil {
		if instance, err := s.store.RuntimeRegistry().GetAgentInstance(ctx, sess.AgentInstanceID); err == nil && instance.AgentID == sess.AgentID && instance.BindingID == sess.BindingID && instance.Generation == sess.InstanceGeneration {
			healthy := instance.Health == model.RuntimeHealthHealthy
			item.InstanceHealthy = &healthy
			summary.Framework, summary.FrameworkVersion = instance.Framework, instance.FrameworkVersion
			_ = json.Unmarshal(instance.Capabilities, &item.Capabilities)
		}
	}
}

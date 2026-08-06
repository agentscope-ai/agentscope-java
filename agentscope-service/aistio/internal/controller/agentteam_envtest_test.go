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

package controller_test

import (
	"testing"
	"time"

	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"

	v1alpha1 "github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

const testTimeout = 10 * time.Second

// waitFor polls condition every 100ms until it returns true or the timeout expires.
func waitFor(t *testing.T, timeout time.Duration, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatal("timed out waiting for condition")
}

func TestAgentTeamCreation(t *testing.T) {
	skipIfNoEnvtest(t)
	ns := createNamespace(t, "team-create")
	ctx, cancel := testContext()
	defer cancel()

	team := &v1alpha1.AgentTeam{
		ObjectMeta: metav1.ObjectMeta{
			Name:      "test-team",
			Namespace: ns,
		},
		Spec: v1alpha1.AgentTeamSpec{
			Objective: "Summarize daily reports",
			Lead: v1alpha1.TeamLeadSpec{
				AgentRef: v1alpha1.ObjectReference{Name: "lead-agent"},
			},
			Members: []v1alpha1.TeamMemberSpec{
				{
					Name:     "researcher",
					AgentRef: v1alpha1.ObjectReference{Name: "research-agent"},
				},
				{
					Name:     "writer",
					AgentRef: v1alpha1.ObjectReference{Name: "writer-agent"},
				},
			},
		},
	}

	// Create the AgentTeam
	if err := k8sClient.Create(ctx, team); err != nil {
		t.Fatalf("failed to create AgentTeam: %v", err)
	}

	// Fetch it back and verify spec
	var fetched v1alpha1.AgentTeam
	key := types.NamespacedName{Name: "test-team", Namespace: ns}
	if err := k8sClient.Get(ctx, key, &fetched); err != nil {
		t.Fatalf("failed to get AgentTeam: %v", err)
	}

	if fetched.Spec.Objective != "Summarize daily reports" {
		t.Errorf("expected objective 'Summarize daily reports', got %q", fetched.Spec.Objective)
	}
	if fetched.Spec.Lead.AgentRef.Name != "lead-agent" {
		t.Errorf("expected lead agentRef 'lead-agent', got %q", fetched.Spec.Lead.AgentRef.Name)
	}
	if len(fetched.Spec.Members) != 2 {
		t.Fatalf("expected 2 members, got %d", len(fetched.Spec.Members))
	}
	if fetched.Spec.Members[0].Name != "researcher" {
		t.Errorf("expected first member 'researcher', got %q", fetched.Spec.Members[0].Name)
	}
	if fetched.Spec.Members[1].Name != "writer" {
		t.Errorf("expected second member 'writer', got %q", fetched.Spec.Members[1].Name)
	}
}

func TestAgentTeamReconcileSetsStatus(t *testing.T) {
	skipIfNoEnvtest(t)
	ns := createNamespace(t, "team-status")

	team := &v1alpha1.AgentTeam{
		ObjectMeta: metav1.ObjectMeta{
			Name:      "status-team",
			Namespace: ns,
		},
		Spec: v1alpha1.AgentTeamSpec{
			Objective: "Test status transitions",
			Lead: v1alpha1.TeamLeadSpec{
				AgentRef: v1alpha1.ObjectReference{Name: "lead-agent"},
			},
			Members: []v1alpha1.TeamMemberSpec{
				{
					Name:     "worker",
					AgentRef: v1alpha1.ObjectReference{Name: "worker-agent"},
				},
			},
		},
	}

	ctx, cancel := testContext()
	defer cancel()

	if err := k8sClient.Create(ctx, team); err != nil {
		t.Fatalf("failed to create AgentTeam: %v", err)
	}

	key := types.NamespacedName{Name: "status-team", Namespace: ns}

	// Wait for the reconciler to add the finalizer.
	waitFor(t, testTimeout, func() bool {
		var fetched v1alpha1.AgentTeam
		if err := k8sClient.Get(ctx, key, &fetched); err != nil {
			return false
		}
		for _, f := range fetched.Finalizers {
			if f == "agentscope.io/team-finalizer" {
				return true
			}
		}
		return false
	})

	// Wait for the reconciler to transition status.Phase to Running
	// (the legacy path sets Phase=Running on the second reconcile after
	// the finalizer is added).
	waitFor(t, testTimeout, func() bool {
		var fetched v1alpha1.AgentTeam
		if err := k8sClient.Get(ctx, key, &fetched); err != nil {
			return false
		}
		return fetched.Status.Phase == v1alpha1.TeamPhaseRunning
	})

	// Fetch the final state and verify status fields.
	var result v1alpha1.AgentTeam
	if err := k8sClient.Get(ctx, key, &result); err != nil {
		t.Fatalf("failed to get AgentTeam: %v", err)
	}

	if result.Status.Phase != v1alpha1.TeamPhaseRunning {
		t.Errorf("expected phase Running, got %q", result.Status.Phase)
	}
	if result.Status.Lead == nil {
		t.Fatal("expected lead status to be set")
	}
	if result.Status.Lead.AgentRef != "lead-agent" {
		t.Errorf("expected lead agentRef 'lead-agent', got %q", result.Status.Lead.AgentRef)
	}
	if len(result.Status.Members) != 1 {
		t.Fatalf("expected 1 member in status, got %d", len(result.Status.Members))
	}
	if result.Status.Members[0].Name != "worker" {
		t.Errorf("expected member name 'worker', got %q", result.Status.Members[0].Name)
	}
	if result.Status.StartedAt == "" {
		t.Error("expected startedAt to be set")
	}

	// Verify the Ready condition is set.
	foundReady := false
	for _, c := range result.Status.Conditions {
		if c.Type == v1alpha1.ConditionReady && c.Status == metav1.ConditionTrue {
			foundReady = true
			break
		}
	}
	if !foundReady {
		t.Error("expected Ready=True condition to be set")
	}
}

func TestAgentTeamFinalizerAdded(t *testing.T) {
	skipIfNoEnvtest(t)
	ns := createNamespace(t, "team-finalizer")

	team := &v1alpha1.AgentTeam{
		ObjectMeta: metav1.ObjectMeta{
			Name:      "finalizer-team",
			Namespace: ns,
		},
		Spec: v1alpha1.AgentTeamSpec{
			Objective: "Test finalizer injection",
			Lead: v1alpha1.TeamLeadSpec{
				AgentRef: v1alpha1.ObjectReference{Name: "lead-agent"},
			},
		},
	}

	ctx, cancel := testContext()
	defer cancel()

	if err := k8sClient.Create(ctx, team); err != nil {
		t.Fatalf("failed to create AgentTeam: %v", err)
	}

	key := types.NamespacedName{Name: "finalizer-team", Namespace: ns}

	// The reconciler should add the team finalizer automatically.
	waitFor(t, testTimeout, func() bool {
		var fetched v1alpha1.AgentTeam
		if err := k8sClient.Get(ctx, key, &fetched); err != nil {
			return false
		}
		for _, f := range fetched.Finalizers {
			if f == "agentscope.io/team-finalizer" {
				return true
			}
		}
		return false
	})
}

func TestAgentTeamDeletion(t *testing.T) {
	skipIfNoEnvtest(t)
	ns := createNamespace(t, "team-delete")
	ctx, cancel := testContext()
	defer cancel()

	team := &v1alpha1.AgentTeam{
		ObjectMeta: metav1.ObjectMeta{
			Name:      "deletable-team",
			Namespace: ns,
		},
		Spec: v1alpha1.AgentTeamSpec{
			Objective: "To be deleted",
			Lead: v1alpha1.TeamLeadSpec{
				AgentRef: v1alpha1.ObjectReference{Name: "lead-agent"},
			},
		},
	}

	if err := k8sClient.Create(ctx, team); err != nil {
		t.Fatalf("failed to create AgentTeam: %v", err)
	}

	key := types.NamespacedName{Name: "deletable-team", Namespace: ns}

	// Wait for the reconciler to add the finalizer and transition to Running.
	waitFor(t, testTimeout, func() bool {
		var fetched v1alpha1.AgentTeam
		if err := k8sClient.Get(ctx, key, &fetched); err != nil {
			return false
		}
		return fetched.Status.Phase == v1alpha1.TeamPhaseRunning
	})

	// Delete the team. The controller's finalizer handles cleanup of
	// store-backed tasks/messages/sessions (see internal/team and
	// internal/store for the underlying cleanup logic and coverage).
	var toDelete v1alpha1.AgentTeam
	if err := k8sClient.Get(ctx, key, &toDelete); err != nil {
		t.Fatalf("failed to get team for deletion: %v", err)
	}
	if err := k8sClient.Delete(ctx, &toDelete); err != nil {
		t.Fatalf("failed to delete AgentTeam: %v", err)
	}

	// Wait for the team to be fully removed (the controller processes the
	// finalizer, cleans up child resources, then allows deletion).
	waitFor(t, testTimeout, func() bool {
		var gone v1alpha1.AgentTeam
		err := k8sClient.Get(ctx, key, &gone)
		return errors.IsNotFound(err)
	})
}

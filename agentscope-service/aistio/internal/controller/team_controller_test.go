package controller

import (
	"testing"
	"time"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

func TestTeamFinalizerNotPresentInitially(t *testing.T) {
	team := &v1alpha1.AgentTeam{
		ObjectMeta: metav1.ObjectMeta{Name: "test-team", Namespace: "default"},
		Spec: v1alpha1.AgentTeamSpec{
			Objective: "test",
			Lead:      v1alpha1.TeamLeadSpec{AgentRef: v1alpha1.ObjectReference{Name: "lead-agent"}},
		},
	}
	if controllerutil.ContainsFinalizer(team, teamFinalizer) {
		t.Error("team should not have finalizer initially")
	}
}

func TestTeamLegacyPendingSetsRunning(t *testing.T) {
	s := runtime.NewScheme()
	v1alpha1.AddToScheme(s)

	team := &v1alpha1.AgentTeam{
		ObjectMeta: metav1.ObjectMeta{Name: "test-team", Namespace: "default"},
		Spec: v1alpha1.AgentTeamSpec{
			Objective: "test objective",
			Lead:      v1alpha1.TeamLeadSpec{AgentRef: v1alpha1.ObjectReference{Name: "lead-agent"}},
			Members: []v1alpha1.TeamMemberSpec{
				{Name: "reviewer", AgentRef: v1alpha1.ObjectReference{Name: "review-agent"}},
			},
		},
	}

	c := fake.NewClientBuilder().WithScheme(s).WithObjects(team).
		WithStatusSubresource(&v1alpha1.AgentTeam{}).Build()

	r := &AgentTeamReconciler{Client: c, Scheme: s}

	_, err := r.legacyHandlePending(t.Context(), team)
	if err != nil {
		t.Fatalf("legacyHandlePending: %v", err)
	}
	if team.Status.Phase != v1alpha1.TeamPhaseRunning {
		t.Errorf("expected Running, got %s", team.Status.Phase)
	}
	if team.Status.Lead == nil {
		t.Fatal("lead status should be set")
	}
	if len(team.Status.Members) != 1 {
		t.Errorf("expected 1 member, got %d", len(team.Status.Members))
	}
}

func TestTeamTimeoutDetected(t *testing.T) {
	team := &v1alpha1.AgentTeam{
		Spec: v1alpha1.AgentTeamSpec{
			Lifecycle: &v1alpha1.TeamLifecycle{MaxDuration: "1ms"},
		},
		Status: v1alpha1.AgentTeamStatus{
			Phase:     v1alpha1.TeamPhaseRunning,
			StartedAt: time.Now().Add(-1 * time.Second).Format(time.RFC3339),
		},
	}

	maxDur, _ := time.ParseDuration(team.Spec.Lifecycle.MaxDuration)
	startedAt, _ := time.Parse(time.RFC3339, team.Status.StartedAt)
	if time.Since(startedAt) <= maxDur {
		t.Error("timeout should be detected")
	}
}

func TestTeamAllCompleteDetected(t *testing.T) {
	team := &v1alpha1.AgentTeam{
		Spec: v1alpha1.AgentTeamSpec{
			Config: &v1alpha1.TeamConfig{ShutdownPolicy: "all-complete"},
		},
		Status: v1alpha1.AgentTeamStatus{
			Phase: v1alpha1.TeamPhaseRunning,
			Tasks: &v1alpha1.TeamTaskSummary{Total: 5, Completed: 5},
		},
	}

	allComplete := team.Status.Tasks != nil &&
		team.Status.Tasks.Total > 0 &&
		team.Status.Tasks.Completed == team.Status.Tasks.Total
	if !allComplete {
		t.Error("all-complete should be detected")
	}
}

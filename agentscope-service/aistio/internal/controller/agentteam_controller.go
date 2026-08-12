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

package controller

import (
	"context"
	"fmt"
	"time"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/metrics"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/team"
)

const teamFinalizer = "agentscope.io/team-finalizer"

// AgentTeamReconciler projects AgentTeam CRDs into the store-backed Team
// repository and drives lifecycle from that authority.
type AgentTeamReconciler struct {
	client.Client
	Scheme    *runtime.Scheme
	Recorder  record.EventRecorder
	Lifecycle *team.Lifecycle
	Store     store.Store
}

// +kubebuilder:rbac:groups=agentscope.io,resources=agentteams,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=agentscope.io,resources=agentteams/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=agentscope.io,resources=agentteams/finalizers,verbs=update
// +kubebuilder:rbac:groups="",resources=events,verbs=create;patch

func (r *AgentTeamReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	logger := log.FromContext(ctx)

	var at v1alpha1.AgentTeam
	if err := r.Get(ctx, req.NamespacedName, &at); err != nil {
		if errors.IsNotFound(err) {
			return ctrl.Result{}, nil
		}
		metrics.RecordReconcileError("agentteam", "fetch_failed")
		return ctrl.Result{}, err
	}

	if !at.DeletionTimestamp.IsZero() {
		if controllerutil.ContainsFinalizer(&at, teamFinalizer) {
			r.Recorder.Eventf(&at, corev1.EventTypeNormal, "CleanupStarted",
				"cascading cleanup for team %s", at.Name)
			if storeTeam, err := r.ensureStoreTeam(ctx, &at); err == nil && r.Lifecycle != nil {
				_ = r.Lifecycle.CompleteTeam(ctx, storeTeam)
				r.Lifecycle.CleanupTeamState(ctx, storeTeam)
			}
			controllerutil.RemoveFinalizer(&at, teamFinalizer)
			if err := r.Update(ctx, &at); err != nil {
				metrics.RecordReconcileError("agentteam", "finalizer_update_failed")
				return ctrl.Result{}, err
			}
			return ctrl.Result{}, nil
		}
		return ctrl.Result{}, nil
	}

	if !controllerutil.ContainsFinalizer(&at, teamFinalizer) {
		controllerutil.AddFinalizer(&at, teamFinalizer)
		if err := r.Update(ctx, &at); err != nil {
			metrics.RecordReconcileError("agentteam", "finalizer_add_failed")
			return ctrl.Result{}, err
		}
		return ctrl.Result{}, nil
	}

	logger.Info("reconciling AgentTeam", "name", at.Name, "phase", at.Status.Phase)

	switch at.Status.Phase {
	case "", v1alpha1.TeamPhasePending:
		return r.handlePending(ctx, &at)
	case v1alpha1.TeamPhaseRunning, v1alpha1.TeamPhaseIdle:
		return r.handleRunning(ctx, &at)
	case v1alpha1.TeamPhaseCompleted, v1alpha1.TeamPhaseFailed:
		return r.handleTerminal(ctx, &at)
	}

	return ctrl.Result{}, nil
}

func (r *AgentTeamReconciler) ensureStoreTeam(ctx context.Context, at *v1alpha1.AgentTeam) (*store.Team, error) {
	if r.Store == nil {
		return nil, store.ErrNotFound
	}
	desired, err := team.FromCRD(at)
	if err != nil {
		return nil, err
	}
	cur, err := r.Store.Teams().Get(ctx, at.Namespace, at.Name)
	if err == store.ErrNotFound {
		created, err := r.Store.Teams().Create(ctx, desired)
		if err != nil {
			return nil, err
		}
		for _, m := range team.MembersFromCRD(at) {
			if _, err := r.Store.Teams().UpsertMember(ctx, m); err != nil {
				return nil, err
			}
		}
		return created, nil
	}
	if err != nil {
		return nil, err
	}
	desired.ID = cur.ID
	desired.CreatedAt = cur.CreatedAt
	desired.StartedAt = cur.StartedAt
	if cur.Phase != "" && desired.Phase == store.TeamPhasePending {
		desired.Phase = cur.Phase
	}
	return r.Store.Teams().Update(ctx, desired)
}

func (r *AgentTeamReconciler) syncCRDStatus(ctx context.Context, at *v1alpha1.AgentTeam, storeTeam *store.Team) error {
	members, err := r.Store.Teams().ListMembers(ctx, storeTeam.Namespace, storeTeam.Name)
	if err != nil {
		return err
	}
	var summary *v1alpha1.TeamTaskSummary
	if r.Store != nil {
		total, pending, inProgress, completed, _ := r.Store.TeamTasks().GetSummary(ctx, storeTeam.Namespace, storeTeam.Name)
		if total > 0 {
			summary = &v1alpha1.TeamTaskSummary{
				Total: total, Pending: pending, InProgress: inProgress, Completed: completed,
			}
		}
	}
	team.ApplyStoreStatusToCRD(at, storeTeam, members, summary)
	return r.Status().Update(ctx, at)
}

func (r *AgentTeamReconciler) handlePending(ctx context.Context, at *v1alpha1.AgentTeam) (ctrl.Result, error) {
	storeTeam, err := r.ensureStoreTeam(ctx, at)
	if err != nil {
		return ctrl.Result{RequeueAfter: 10 * time.Second}, err
	}

	if r.Lifecycle == nil {
		return ctrl.Result{RequeueAfter: 30 * time.Second}, fmt.Errorf("team lifecycle not configured")
	}

	r.Recorder.Eventf(at, corev1.EventTypeNormal, "TeamStarting",
		"spawning lead and %d member sessions", len(at.Spec.Members))

	if err := r.Lifecycle.StartTeam(ctx, storeTeam); err != nil {
		r.Recorder.Eventf(at, corev1.EventTypeWarning, "StartFailed",
			"failed to start team: %v", err)
		metrics.RecordReconcileError("agentteam", "start_failed")
		return ctrl.Result{RequeueAfter: 10 * time.Second}, err
	}

	fresh, _ := r.Store.Teams().Get(ctx, storeTeam.Namespace, storeTeam.Name)
	if err := r.syncCRDStatus(ctx, at, fresh); err != nil {
		return ctrl.Result{}, err
	}

	r.Recorder.Eventf(at, corev1.EventTypeNormal, "TeamStarted",
		"team %s started", at.Name)
	return ctrl.Result{RequeueAfter: 30 * time.Second}, nil
}

func (r *AgentTeamReconciler) handleRunning(ctx context.Context, at *v1alpha1.AgentTeam) (ctrl.Result, error) {
	// Timeout / all-complete / member health / store TTL are owned by the shared
	// TeamSweeper. This reconciler only projects CRD ↔ store status.
	storeTeam, err := r.ensureStoreTeam(ctx, at)
	if err != nil && r.Lifecycle != nil {
		return ctrl.Result{RequeueAfter: 10 * time.Second}, err
	}

	if storeTeam != nil && r.Store != nil {
		members, _ := r.Store.Teams().ListMembers(ctx, storeTeam.Namespace, storeTeam.Name)
		phaseCounts := map[string]int{}
		for _, m := range members {
			if m.MemberName == "lead" {
				continue
			}
			phaseCounts[m.Phase]++
		}
		for phase, count := range phaseCounts {
			metrics.RecordTeamMembers(at.Namespace, at.Name, phase, count)
		}
		total, pending, inProgress, completed, _ := r.Store.TeamTasks().GetSummary(ctx, storeTeam.Namespace, storeTeam.Name)
		metrics.RecordTeamTasks(at.Namespace, at.Name, "pending", int(pending))
		metrics.RecordTeamTasks(at.Namespace, at.Name, "in_progress", int(inProgress))
		metrics.RecordTeamTasks(at.Namespace, at.Name, "completed", int(completed))
		_ = total
		_ = r.syncCRDStatus(ctx, at, storeTeam)
	}

	return ctrl.Result{RequeueAfter: 30 * time.Second}, nil
}

func (r *AgentTeamReconciler) handleTerminal(ctx context.Context, at *v1alpha1.AgentTeam) (ctrl.Result, error) {
	// Store-side CleanupTeamState is owned by TeamSweeper. Here we only GC the CRD
	// once the store team is gone or past TTL (status projection).
	storeTeam, err := r.ensureStoreTeam(ctx, at)
	if err == nil && r.Lifecycle != nil && r.Lifecycle.ShouldCleanup(storeTeam) {
		return ctrl.Result{}, r.Delete(ctx, at)
	}

	var ttl string
	if at.Spec.Lifecycle != nil {
		switch at.Status.Phase {
		case v1alpha1.TeamPhaseCompleted:
			ttl = at.Spec.Lifecycle.TTLAfterCompleted
		case v1alpha1.TeamPhaseFailed:
			ttl = at.Spec.Lifecycle.TTLAfterFailed
		}
	}
	if ttl != "" {
		ttlDur, err := time.ParseDuration(ttl)
		if err == nil {
			startedAt, _ := time.Parse(time.RFC3339, at.Status.StartedAt)
			if time.Since(startedAt) > ttlDur {
				return ctrl.Result{}, r.Delete(ctx, at)
			}
			return ctrl.Result{RequeueAfter: ttlDur}, nil
		}
	}
	return ctrl.Result{}, nil
}

func (r *AgentTeamReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&v1alpha1.AgentTeam{}).
		Complete(r)
}

package controller

import (
	"context"
	"time"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/tools/record"
	"k8s.io/client-go/util/retry"
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

// AgentTeamReconciler manages team lifecycle, session creation, and member coordination.
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

	// Handle deletion with finalizer
	if !at.DeletionTimestamp.IsZero() {
		if controllerutil.ContainsFinalizer(&at, teamFinalizer) {
			r.Recorder.Eventf(&at, corev1.EventTypeNormal, "CleanupStarted",
				"cascading cleanup for team %s", at.Name)
			if r.Lifecycle != nil {
				r.Lifecycle.CompleteTeam(ctx, &at)
			}
			r.cleanupTeamTasks(ctx, &at)
			controllerutil.RemoveFinalizer(&at, teamFinalizer)
			if err := r.Update(ctx, &at); err != nil {
				metrics.RecordReconcileError("agentteam", "finalizer_update_failed")
				return ctrl.Result{}, err
			}
			return ctrl.Result{}, nil
		}
		return ctrl.Result{}, nil
	}

	// Ensure finalizer
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
	case v1alpha1.TeamPhaseRunning:
		return r.handleRunning(ctx, &at)
	case v1alpha1.TeamPhaseCompleted, v1alpha1.TeamPhaseFailed:
		return r.handleTerminal(ctx, &at)
	}

	return ctrl.Result{}, nil
}

func (r *AgentTeamReconciler) handlePending(ctx context.Context, at *v1alpha1.AgentTeam) (ctrl.Result, error) {
	if r.Lifecycle == nil {
		return r.legacyHandlePending(ctx, at)
	}

	r.Recorder.Eventf(at, corev1.EventTypeNormal, "TeamStarting",
		"spawning lead and %d member sessions", len(at.Spec.Members))

	if err := r.Lifecycle.StartTeam(ctx, at); err != nil {
		r.Recorder.Eventf(at, corev1.EventTypeWarning, "StartFailed",
			"failed to start team: %v", err)
		metrics.RecordReconcileError("agentteam", "start_failed")
		return ctrl.Result{RequeueAfter: 10 * time.Second}, err
	}

	r.Recorder.Eventf(at, corev1.EventTypeNormal, "TeamStarted",
		"team %s started with lead + %d members", at.Name, len(at.Status.Members))

	// Lifecycle.StartTeam already updates status -- re-read to avoid stale conflict
	return ctrl.Result{RequeueAfter: 30 * time.Second}, nil
}

// legacyHandlePending handles the pending phase without Lifecycle (backward compat).
func (r *AgentTeamReconciler) legacyHandlePending(ctx context.Context, at *v1alpha1.AgentTeam) (ctrl.Result, error) {
	at.Status.Phase = v1alpha1.TeamPhaseRunning
	at.Status.StartedAt = time.Now().Format(time.RFC3339)

	at.Status.Lead = &v1alpha1.TeamMemberStatus{
		Name:     "lead",
		AgentRef: at.Spec.Lead.AgentRef.Name,
		Phase:    v1alpha1.MemberPhaseWorking,
	}

	at.Status.Members = make([]v1alpha1.TeamMemberStatus, 0, len(at.Spec.Members))
	for _, m := range at.Spec.Members {
		at.Status.Members = append(at.Status.Members, v1alpha1.TeamMemberStatus{
			Name:     m.Name,
			Origin:   v1alpha1.MemberOriginStatic,
			AgentRef: m.AgentRef.Name,
			Phase:    v1alpha1.MemberPhaseJoining,
		})
	}

	at.Status.Tasks = &v1alpha1.TeamTaskSummary{}

	setConditionInList(&at.Status.Conditions, v1alpha1.Condition{
		Type:               v1alpha1.ConditionReady,
		Status:             metav1.ConditionTrue,
		LastTransitionTime: metav1.Now(),
		Reason:             "TeamStarted",
		Message:            "Team initialized and running",
	})

	return ctrl.Result{RequeueAfter: 30 * time.Second}, r.Status().Update(ctx, at)
}

func (r *AgentTeamReconciler) handleRunning(ctx context.Context, at *v1alpha1.AgentTeam) (ctrl.Result, error) {
	// Check timeout
	if r.Lifecycle != nil && r.Lifecycle.CheckTimeout(at) {
		r.Recorder.Eventf(at, corev1.EventTypeWarning, "Timeout",
			"team %s exceeded maxDuration", at.Name)
		return ctrl.Result{}, retry.RetryOnConflict(retry.DefaultRetry, func() error {
			var fresh v1alpha1.AgentTeam
			if err := r.Get(ctx, client.ObjectKeyFromObject(at), &fresh); err != nil {
				return err
			}
			fresh.Status.Phase = v1alpha1.TeamPhaseFailed
			setConditionInList(&fresh.Status.Conditions, v1alpha1.Condition{
				Type:               v1alpha1.ConditionReady,
				Status:             metav1.ConditionFalse,
				LastTransitionTime: metav1.Now(),
				Reason:             "Timeout",
				Message:            "Team exceeded maxDuration",
			})
			return r.Status().Update(ctx, &fresh)
		})
	}

	// Check all-complete
	if r.Lifecycle != nil && r.Lifecycle.CheckAllComplete(at) {
		r.Recorder.Eventf(at, corev1.EventTypeNormal, "AllComplete",
			"all tasks completed, shutting down team")
		if err := r.Lifecycle.CompleteTeam(ctx, at); err != nil {
			return ctrl.Result{}, err
		}
		return ctrl.Result{}, nil
	}

	// Fallback: inline timeout + all-complete checks when Lifecycle is nil
	if r.Lifecycle == nil {
		return r.legacyHandleRunning(ctx, at)
	}

	// Check member session health
	r.checkMemberHealth(ctx, at)

	// Aggregate member phase metrics
	phaseCounts := map[string]int{}
	for _, m := range at.Status.Members {
		phaseCounts[string(m.Phase)]++
	}
	for phase, count := range phaseCounts {
		metrics.RecordTeamMembers(at.Namespace, at.Name, phase, count)
	}
	if at.Status.Tasks != nil {
		metrics.RecordTeamTasks(at.Namespace, at.Name, "pending", int(at.Status.Tasks.Pending))
		metrics.RecordTeamTasks(at.Namespace, at.Name, "in_progress", int(at.Status.Tasks.InProgress))
		metrics.RecordTeamTasks(at.Namespace, at.Name, "completed", int(at.Status.Tasks.Completed))
	}

	return ctrl.Result{RequeueAfter: 30 * time.Second}, nil
}

// legacyHandleRunning preserves the original inline timeout/all-complete logic.
func (r *AgentTeamReconciler) legacyHandleRunning(ctx context.Context, at *v1alpha1.AgentTeam) (ctrl.Result, error) {
	if at.Spec.Lifecycle != nil && at.Spec.Lifecycle.MaxDuration != "" {
		maxDur, err := time.ParseDuration(at.Spec.Lifecycle.MaxDuration)
		if err == nil {
			startedAt, err := time.Parse(time.RFC3339, at.Status.StartedAt)
			if err == nil && time.Since(startedAt) > maxDur {
				at.Status.Phase = v1alpha1.TeamPhaseFailed
				setConditionInList(&at.Status.Conditions, v1alpha1.Condition{
					Type:               v1alpha1.ConditionReady,
					Status:             metav1.ConditionFalse,
					LastTransitionTime: metav1.Now(),
					Reason:             "Timeout",
					Message:            "Team exceeded maxDuration",
				})
				return ctrl.Result{}, r.Status().Update(ctx, at)
			}
		}
	}

	if at.Spec.Config != nil && at.Spec.Config.ShutdownPolicy == "all-complete" {
		if at.Status.Tasks != nil && at.Status.Tasks.Total > 0 &&
			at.Status.Tasks.Completed == at.Status.Tasks.Total {
			at.Status.Phase = v1alpha1.TeamPhaseCompleted
			return ctrl.Result{}, r.Status().Update(ctx, at)
		}
	}

	return ctrl.Result{RequeueAfter: 30 * time.Second}, nil
}

func (r *AgentTeamReconciler) handleTerminal(ctx context.Context, at *v1alpha1.AgentTeam) (ctrl.Result, error) {
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

// checkMemberHealth detects failed or missing member sessions.
func (r *AgentTeamReconciler) checkMemberHealth(ctx context.Context, at *v1alpha1.AgentTeam) {
	if r.Store == nil {
		return
	}
	for _, m := range at.Status.Members {
		if m.Phase == v1alpha1.MemberPhaseLost || m.Phase == v1alpha1.MemberPhaseFailed {
			continue
		}
		if m.SessionID == "" {
			continue
		}
		sess, err := r.Store.Sessions().Get(ctx, m.AgentRef, at.Namespace, m.SessionID)
		if err != nil {
			if err == store.ErrNotFound {
				r.Recorder.Eventf(at, corev1.EventTypeWarning, "MemberLost",
					"member %s session %s not found", m.Name, m.SessionID)
				r.Lifecycle.HandleMemberFailure(ctx, at, m.Name, "SessionNotFound")
			}
			continue
		}
		if sess.Phase == store.SessionPhaseTerminated {
			r.Recorder.Eventf(at, corev1.EventTypeWarning, "MemberTerminated",
				"member %s session terminated", m.Name)
			r.Lifecycle.HandleMemberFailure(ctx, at, m.Name, "SessionTerminated")
		}
	}
}

// cleanupTeamTasks removes the team's persistent task/message/session state
// from the store. Called on finalize so cleanup is immediate rather than
// relying solely on retention purges.
func (r *AgentTeamReconciler) cleanupTeamTasks(ctx context.Context, at *v1alpha1.AgentTeam) {
	logger := log.FromContext(ctx)
	logger.Info("cleaning up team resources", "team", at.Name)
	if r.Lifecycle != nil {
		r.Lifecycle.CleanupTeamState(ctx, at)
		return
	}
	// No lifecycle wired (legacy/test mode) -- nothing store-backed to clean up.
}

func (r *AgentTeamReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&v1alpha1.AgentTeam{}).
		Complete(r)
}

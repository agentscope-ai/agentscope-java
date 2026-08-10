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

	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/tools/record"
	"k8s.io/client-go/util/retry"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/endpoints"
	"github.com/spring-ai-alibaba/aistio/internal/metrics"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

const sessionPollInterval = 15 * time.Second

// SessionPollerReconciler periodically polls agents with contractLevel >= 2
// for session data and writes to the runtime Store.
type SessionPollerReconciler struct {
	client.Client
	Scheme   *runtime.Scheme
	Prober   prober.DataPlaneProber
	Store    store.Store
	Recorder record.EventRecorder
}

// +kubebuilder:rbac:groups=agentscope.io,resources=agents,verbs=get;list;watch
// +kubebuilder:rbac:groups=agentscope.io,resources=agents/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=apps,resources=deployments,verbs=get;list;watch
// +kubebuilder:rbac:groups="",resources=pods,verbs=get;list;watch
// +kubebuilder:rbac:groups="",resources=events,verbs=create;patch

func (r *SessionPollerReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	logger := log.FromContext(ctx)

	var agent v1alpha1.Agent
	if err := r.Get(ctx, req.NamespacedName, &agent); err != nil {
		if errors.IsNotFound(err) {
			return ctrl.Result{}, nil
		}
		metrics.RecordReconcileError("session-poller", "get-agent")
		return ctrl.Result{}, err
	}

	var contractLevel int32
	if agent.Status.DataPlaneInfo != nil {
		contractLevel = agent.Status.DataPlaneInfo.ContractLevel
	}

	if contractLevel < 2 {
		logger.V(1).Info("skipping session polling, contractLevel < 2",
			"agent", agent.Name, "contractLevel", contractLevel)
		return ctrl.Result{RequeueAfter: sessionPollInterval}, r.setPollerCondition(ctx, &agent,
			"SessionPollingUnsupported",
			fmt.Sprintf("contractLevel %d does not support session polling (requires >= 2)", contractLevel))
	}

	endpoint, err := endpoints.ResolveAgentHTTP(ctx, r.Client, &agent)
	if err != nil {
		logger.Info("cannot find agent endpoint for session polling", "agent", agent.Name, "error", err)
		return ctrl.Result{RequeueAfter: sessionPollInterval}, nil
	}

	probeStart := time.Now()
	var snapshots []prober.SessionSnapshot
	var probeTruncated bool
	if dp, ok := r.Prober.(interface {
		ProbeSessionsDetailed(ctx context.Context, endpoint string) (prober.SessionsProbeResult, error)
	}); ok {
		res, err := dp.ProbeSessionsDetailed(ctx, endpoint)
		metrics.RecordProbeLatency(agent.Namespace, agent.Name, "sessions", time.Since(probeStart))
		if err != nil {
			logger.Info("failed to poll sessions", "agent", agent.Name, "error", err)
			metrics.RecordDataPlaneStatus(agent.Namespace, agent.Name, false, contractLevel)
			return ctrl.Result{RequeueAfter: sessionPollInterval}, nil
		}
		snapshots = res.Sessions
		probeTruncated = res.LikelyTruncated()
	} else {
		var err error
		snapshots, err = r.Prober.ProbeSessions(ctx, endpoint)
		metrics.RecordProbeLatency(agent.Namespace, agent.Name, "sessions", time.Since(probeStart))
		if err != nil {
			logger.Info("failed to poll sessions", "agent", agent.Name, "error", err)
			metrics.RecordDataPlaneStatus(agent.Namespace, agent.Name, false, contractLevel)
			return ctrl.Result{RequeueAfter: sessionPollInterval}, nil
		}
		probeTruncated = prober.SessionsProbeLikelyTruncated(len(snapshots))
	}
	metrics.RecordDataPlaneStatus(agent.Namespace, agent.Name, true, contractLevel)

	keepIDs := make([]string, 0, len(snapshots))
	for i := range snapshots {
		keepIDs = append(keepIDs, snapshots[i].ID)
		if err := r.syncSession(ctx, &agent, endpoint, &snapshots[i]); err != nil {
			logger.Error(err, "failed to sync session", "sessionID", snapshots[i].ID)
		}
	}

	if r.Store != nil {
		if probeTruncated {
			logger.Info("skipping ArchiveMissing: sessions probe appears truncated",
				"agent", agent.Name, "count", len(snapshots), "maxPage", prober.MaxSessionsProbePage)
		} else if _, err := r.Store.Sessions().ArchiveMissing(ctx, agent.Name, agent.Namespace, keepIDs, 60*time.Second); err != nil {
			logger.Error(err, "failed to archive sessions missing from data plane")
		}
	}

	var activeSessions int32
	if r.Store != nil {
		activeSessions, _ = r.Store.Sessions().CountActive(ctx, agent.Name, agent.Namespace)
		_ = r.Store.Metrics().RecordAgentMetric(ctx, &store.AgentMetric{
			AgentName:      agent.Name,
			Namespace:      agent.Namespace,
			ActiveSessions: activeSessions,
		})
	}
	metrics.RecordSessionCount(agent.Namespace, agent.Name, activeSessions)
	if err := r.updateAgentSessionCount(ctx, &agent, activeSessions); err != nil {
		logger.Error(err, "failed to update agent session count")
	}

	return ctrl.Result{RequeueAfter: sessionPollInterval}, nil
}

func (r *SessionPollerReconciler) syncSession(ctx context.Context, agent *v1alpha1.Agent, endpoint string, snap *prober.SessionSnapshot) error {
	o := ObservedSession{
		ID:                    snap.ID,
		Phase:                 snap.Phase,
		MessageCount:          snap.MessageCount,
		ContextPressure:       snap.ContextPressure,
		StartedAt:             snap.StartedAt,
		LastActiveAt:          snap.LastActiveAt,
		Framework:             snap.Framework,
		FrameworkVersion:      snap.FrameworkVersion,
		ContextHash:           snap.ContextHash,
		IsCompacted:           snap.IsCompacted,
		EffectiveMessageCount: snap.EffectiveMessageCount,
	}
	if o.Framework == "" {
		o.Framework = agent.Spec.Runtime
	}
	if snap.TokenUsage != nil {
		o.PromptTokens = snap.TokenUsage.PromptTokens
		o.CompletionTokens = snap.TokenUsage.CompletionTokens
	}
	saved, err := upsertObservedSession(ctx, r.Store, agent, o)
	if err != nil {
		return err
	}

	// Level 4 pull fallback: when the data plane reports a context hash we
	// have not stored yet and no ASDP ContextReport is expected (pure HTTP
	// contract data plane), fetch the effective context on demand.
	r.maybePullContext(ctx, agent, endpoint, saved, snap)
	return nil
}

// maybePullContext fetches the Level-4 effective context from the data plane
// when the reported context_hash differs from the latest stored snapshot.
// Gated on the `context-query` capability.
func (r *SessionPollerReconciler) maybePullContext(ctx context.Context, agent *v1alpha1.Agent, endpoint string, sess *store.Session, snap *prober.SessionSnapshot) {
	logger := log.FromContext(ctx)
	if snap.ContextHash == "" || !agentHasCapability(agent, v1alpha1.CapabilityContextQuery) {
		return
	}
	latest, err := r.Store.ContextSnapshots().Latest(ctx, sess.ID)
	if err == nil && latest.ContextHash == snap.ContextHash {
		return // already have this context
	}
	if err != nil && err != store.ErrNotFound {
		logger.Error(err, "failed to read latest context snapshot", "sessionID", snap.ID)
		return
	}

	probed, err := r.Prober.FetchContext(ctx, endpoint, snap.ID)
	if err != nil {
		logger.V(1).Info("failed to fetch session context", "sessionID", snap.ID, "error", err)
		return
	}
	if _, err := writeProbedContext(ctx, r.Store, sess, probed); err != nil {
		logger.Error(err, "failed to store pulled context", "sessionID", snap.ID)
	}
}

// writeProbedContext converts a probed Level-4 context snapshot into a store
// row (deduplicated by context_hash via PutIfChanged).
func writeProbedContext(ctx context.Context, st store.Store, sess *store.Session, probed *prober.ContextSnapshot) (bool, error) {
	if probed == nil {
		return false, nil
	}
	row, err := probed.ToStoreContext(sess.ID, sess.Framework)
	if err != nil {
		return false, err
	}
	return st.ContextSnapshots().PutIfChanged(ctx, row)
}

func (r *SessionPollerReconciler) updateAgentSessionCount(ctx context.Context, agent *v1alpha1.Agent, count int32) error {
	return retry.RetryOnConflict(retry.DefaultRetry, func() error {
		var fresh v1alpha1.Agent
		if err := r.Get(ctx, client.ObjectKeyFromObject(agent), &fresh); err != nil {
			return err
		}
		fresh.Status.ActiveSessions = count
		return r.Status().Update(ctx, &fresh)
	})
}

func (r *SessionPollerReconciler) setPollerCondition(ctx context.Context, agent *v1alpha1.Agent, reason, message string) error {
	return retry.RetryOnConflict(retry.DefaultRetry, func() error {
		var fresh v1alpha1.Agent
		if err := r.Get(ctx, client.ObjectKeyFromObject(agent), &fresh); err != nil {
			return err
		}
		setConditionInList(&fresh.Status.Conditions, v1alpha1.Condition{
			Type:               "SessionPolling",
			Status:             metav1.ConditionFalse,
			LastTransitionTime: metav1.Now(),
			Reason:             reason,
			Message:            message,
		})
		return r.Status().Update(ctx, &fresh)
	})
}

func (r *SessionPollerReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		Named("session-poller").
		For(&v1alpha1.Agent{}, builder.WithPredicates(agentWorkloadRefPredicate(false))).
		Complete(r)
}

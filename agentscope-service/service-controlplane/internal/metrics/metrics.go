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

// Package metrics defines control-plane Prometheus metrics. They are registered
// against the controller-runtime metrics registry, so they are exposed on the
// existing manager metrics endpoint alongside the built-in reconcile metrics
// (controller_runtime_reconcile_total / reconcile_time_seconds).
package metrics

import (
	"fmt"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	ctrlmetrics "sigs.k8s.io/controller-runtime/pkg/metrics"
)

var (
	// AgentInfo is a constant-1 gauge carrying agent metadata as labels.
	AgentInfo = prometheus.NewGaugeVec(prometheus.GaugeOpts{
		Name: "agentscope_agent_info",
		Help: "Static information about a managed agent (constant 1).",
	}, []string{"namespace", "name", "type", "runtime", "management_mode"})

	// AgentReplicas reports replica counts per agent and kind (desired/ready/available).
	AgentReplicas = prometheus.NewGaugeVec(prometheus.GaugeOpts{
		Name: "agentscope_agent_replicas",
		Help: "Replica counts for a managed agent.",
	}, []string{"namespace", "name", "kind"})

	// DataPlaneConnected indicates whether the data plane is connected (1=connected, 0=disconnected).
	DataPlaneConnected = prometheus.NewGaugeVec(prometheus.GaugeOpts{
		Name: "agentscope_dataplane_connected",
		Help: "Whether the data plane is connected (1=connected, 0=disconnected).",
	}, []string{"namespace", "name", "contract_level"})

	// SessionsActive tracks the number of active sessions per agent.
	SessionsActive = prometheus.NewGaugeVec(prometheus.GaugeOpts{
		Name: "agentscope_sessions_active",
		Help: "Number of active sessions per agent.",
	}, []string{"namespace", "agent"})

	// SessionOperations counts session operations (compress/terminate).
	SessionOperations = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "agentscope_session_operations_total",
		Help: "Total session operations (compress/terminate).",
	}, []string{"namespace", "agent", "operation", "result"})

	// ProbeLatency tracks data plane probe latency.
	ProbeLatency = prometheus.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "agentscope_probe_duration_seconds",
		Help:    "Data plane probe latency.",
		Buckets: prometheus.DefBuckets,
	}, []string{"namespace", "agent", "probe_type"})

	// ReconcileErrors counts reconcile errors by controller and reason.
	ReconcileErrors = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "agentscope_reconcile_errors_total",
		Help: "Reconcile errors by controller and reason.",
	}, []string{"controller", "reason"})

	// GRPCConnections tracks the number of active gRPC data plane connections.
	GRPCConnections = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "agentscope_grpc_connections_active",
		Help: "Number of active gRPC data plane connections.",
	})

	// GRPCConfigPushTotal counts config pushes sent via gRPC.
	GRPCConfigPushTotal = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "agentscope_grpc_config_push_total",
		Help: "Total config pushes sent via gRPC.",
	}, []string{"namespace", "agent", "config_type", "result"})

	// GRPCConfigNackTotal counts config NACKs received.
	GRPCConfigNackTotal = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "agentscope_grpc_config_nack_total",
		Help: "Total config NACKs received.",
	}, []string{"namespace", "agent", "config_type"})

	// GRPCStreamErrors counts gRPC stream errors.
	GRPCStreamErrors = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "agentscope_grpc_stream_errors_total",
		Help: "Total gRPC stream errors.",
	}, []string{"namespace", "direction"})

	AgentTaskTransitions = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "agentscope_agent_task_transitions_total",
		Help: "AgentTask transitions by namespace, backend, and state.",
	}, []string{"namespace", "backend", "state"})

	CommentRoutes = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "agentscope_comment_routes_total",
		Help: "Comment routing outcomes by namespace, target type, and outcome.",
	}, []string{"namespace", "target_type", "outcome"})

	AgentTaskInputAge = prometheus.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "agentscope_agent_task_input_age_seconds",
		Help:    "Age of AgentTask inputs when they enter a delivery state.",
		Buckets: prometheus.ExponentialBuckets(1, 2, 14),
	}, []string{"namespace", "state"})

	ExecutionAttemptTransitions = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "agentscope_execution_attempt_transitions_total",
		Help: "Execution attempt transitions by backend, state, and failure code.",
	}, []string{"namespace", "backend", "state", "failure_code"})

	ExecutionAttemptDuration = prometheus.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "agentscope_execution_attempt_duration_seconds",
		Help:    "Execution attempt duration from start to terminal state.",
		Buckets: prometheus.ExponentialBuckets(1, 2, 12),
	}, []string{"namespace", "backend", "state"})

	RuntimeClaims = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "agentscope_runtime_claims_total",
		Help: "Runtime Host claim attempts by pool and result.",
	}, []string{"namespace", "pool", "result"})
)

func init() {
	ctrlmetrics.Registry.MustRegister(
		AgentInfo,
		AgentReplicas,
		DataPlaneConnected,
		SessionsActive,
		SessionOperations,
		ProbeLatency,
		ReconcileErrors,
		GRPCConnections,
		GRPCConfigPushTotal,
		GRPCConfigNackTotal,
		GRPCStreamErrors,
		AgentTaskTransitions,
		CommentRoutes,
		AgentTaskInputAge,
		ExecutionAttemptTransitions,
		ExecutionAttemptDuration,
		RuntimeClaims,
	)
}

// RecordAgent records info + replica gauges for an agent.
func RecordAgent(namespace, name, agentType, runtime, managementMode string, desired, ready, available int32) {
	AgentInfo.WithLabelValues(namespace, name, agentType, runtime, managementMode).Set(1)
	AgentReplicas.WithLabelValues(namespace, name, "desired").Set(float64(desired))
	AgentReplicas.WithLabelValues(namespace, name, "ready").Set(float64(ready))
	AgentReplicas.WithLabelValues(namespace, name, "available").Set(float64(available))
}

// ForgetAgent clears metrics for a deleted agent.
func ForgetAgent(namespace, name string) {
	AgentReplicas.DeleteLabelValues(namespace, name, "desired")
	AgentReplicas.DeleteLabelValues(namespace, name, "ready")
	AgentReplicas.DeleteLabelValues(namespace, name, "available")
	AgentInfo.DeletePartialMatch(prometheus.Labels{"namespace": namespace, "name": name})
}

// RecordDataPlaneStatus records whether the data plane is connected for an agent.
func RecordDataPlaneStatus(namespace, name string, connected bool, contractLevel int32) {
	val := float64(0)
	if connected {
		val = 1
	}
	DataPlaneConnected.WithLabelValues(namespace, name, fmt.Sprintf("%d", contractLevel)).Set(val)
}

// RecordSessionCount sets the active session count for an agent.
func RecordSessionCount(namespace, agent string, count int32) {
	SessionsActive.WithLabelValues(namespace, agent).Set(float64(count))
}

// RecordSessionOperation increments the session operation counter.
func RecordSessionOperation(namespace, agent, operation, result string) {
	SessionOperations.WithLabelValues(namespace, agent, operation, result).Inc()
}

// RecordProbeLatency observes a probe duration.
func RecordProbeLatency(namespace, agent, probeType string, duration time.Duration) {
	ProbeLatency.WithLabelValues(namespace, agent, probeType).Observe(duration.Seconds())
}

// RecordReconcileError increments the reconcile error counter.
func RecordReconcileError(controller, reason string) {
	ReconcileErrors.WithLabelValues(controller, reason).Inc()
}

// RecordGRPCConnection adjusts the active gRPC connection gauge by delta (+1 or -1).
func RecordGRPCConnection(delta int) {
	GRPCConnections.Add(float64(delta))
}

// RecordConfigPush increments the gRPC config push counter.
func RecordConfigPush(namespace, agent, configType, result string) {
	GRPCConfigPushTotal.WithLabelValues(namespace, agent, configType, result).Inc()
}

// RecordConfigNack increments the gRPC config NACK counter.
func RecordConfigNack(namespace, agent, configType string) {
	GRPCConfigNackTotal.WithLabelValues(namespace, agent, configType).Inc()
}

// RecordStreamError increments the gRPC stream error counter.
func RecordStreamError(namespace, direction string) {
	GRPCStreamErrors.WithLabelValues(namespace, direction).Inc()
}

func RecordAgentTaskTransition(namespace, backend, state string) {
	AgentTaskTransitions.WithLabelValues(namespace, backend, state).Inc()
}

func RecordCommentRoute(namespace, targetType, outcome string) {
	CommentRoutes.WithLabelValues(namespace, targetType, outcome).Inc()
}

func ObserveAgentTaskInputAge(namespace, state string, age time.Duration) {
	AgentTaskInputAge.WithLabelValues(namespace, state).Observe(age.Seconds())
}

func RecordExecutionAttemptTransition(namespace, backend, state, failureCode string, duration time.Duration) {
	ExecutionAttemptTransitions.WithLabelValues(namespace, backend, state, failureCode).Inc()
	if duration > 0 && (state == "succeeded" || state == "failed" || state == "cancelled") {
		ExecutionAttemptDuration.WithLabelValues(namespace, backend, state).Observe(duration.Seconds())
	}
}

func RecordRuntimeClaim(namespace, pool, result string) {
	RuntimeClaims.WithLabelValues(namespace, pool, result).Inc()
}

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

	// TeamsActive tracks the number of active teams (phase=Running).
	TeamsActive = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "agentscope_teams_active",
		Help: "Number of active teams (phase=Running).",
	})

	// TeamMembersByPhase reports team members by phase.
	TeamMembersByPhase = prometheus.NewGaugeVec(prometheus.GaugeOpts{
		Name: "agentscope_team_members_by_phase",
		Help: "Team members by phase.",
	}, []string{"namespace", "team", "phase"})

	// TeamTasksByState reports team tasks by state.
	TeamTasksByState = prometheus.NewGaugeVec(prometheus.GaugeOpts{
		Name: "agentscope_team_tasks_by_state",
		Help: "Team tasks by state.",
	}, []string{"namespace", "team", "state"})

	// TeamRecoveryTotal counts member recovery attempts.
	TeamRecoveryTotal = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "agentscope_team_recovery_total",
		Help: "Total member recovery attempts.",
	}, []string{"namespace", "team", "result"})

	// TeamMessagesTotal counts team messages.
	TeamMessagesTotal = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "agentscope_team_messages_total",
		Help: "Total team messages.",
	}, []string{"namespace", "team", "status"})
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
		TeamsActive,
		TeamMembersByPhase,
		TeamTasksByState,
		TeamRecoveryTotal,
		TeamMessagesTotal,
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

// RecordTeamsActive sets the number of active teams.
func RecordTeamsActive(count int) {
	TeamsActive.Set(float64(count))
}

// RecordTeamMembers sets the member count for a team by phase.
func RecordTeamMembers(namespace, team, phase string, count int) {
	TeamMembersByPhase.WithLabelValues(namespace, team, phase).Set(float64(count))
}

// RecordTeamTasks sets the task count for a team by state.
func RecordTeamTasks(namespace, team, state string, count int) {
	TeamTasksByState.WithLabelValues(namespace, team, state).Set(float64(count))
}

// RecordTeamRecovery increments the team recovery counter.
func RecordTeamRecovery(namespace, team, result string) {
	TeamRecoveryTotal.WithLabelValues(namespace, team, result).Inc()
}

// RecordTeamMessage increments the team message counter.
func RecordTeamMessage(namespace, team, status string) {
	TeamMessagesTotal.WithLabelValues(namespace, team, status).Inc()
}

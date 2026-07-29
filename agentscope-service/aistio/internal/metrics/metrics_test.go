package metrics

import (
	"testing"

	"github.com/prometheus/client_golang/prometheus/testutil"
)

func TestRecordAgent(t *testing.T) {
	// Reset gauges before test to avoid cross-test pollution
	AgentInfo.Reset()
	AgentReplicas.Reset()

	RecordAgent("ns", "agent1", "Declarative", "agentscope-java", "CP-Managed", 3, 2, 2)

	// Check info gauge is set to 1
	val := testutil.ToFloat64(AgentInfo.WithLabelValues("ns", "agent1", "Declarative", "agentscope-java", "CP-Managed"))
	if val != 1.0 {
		t.Errorf("expected AgentInfo 1.0, got %f", val)
	}

	// Check replica gauges
	val = testutil.ToFloat64(AgentReplicas.WithLabelValues("ns", "agent1", "desired"))
	if val != 3.0 {
		t.Errorf("expected desired replicas 3.0, got %f", val)
	}

	val = testutil.ToFloat64(AgentReplicas.WithLabelValues("ns", "agent1", "ready"))
	if val != 2.0 {
		t.Errorf("expected ready replicas 2.0, got %f", val)
	}

	val = testutil.ToFloat64(AgentReplicas.WithLabelValues("ns", "agent1", "available"))
	if val != 2.0 {
		t.Errorf("expected available replicas 2.0, got %f", val)
	}
}

func TestRecordSessionCount(t *testing.T) {
	SessionsActive.Reset()

	RecordSessionCount("ns", "agent1", 5)

	val := testutil.ToFloat64(SessionsActive.WithLabelValues("ns", "agent1"))
	if val != 5.0 {
		t.Errorf("expected 5.0, got %f", val)
	}

	// Update count
	RecordSessionCount("ns", "agent1", 3)
	val = testutil.ToFloat64(SessionsActive.WithLabelValues("ns", "agent1"))
	if val != 3.0 {
		t.Errorf("expected 3.0 after update, got %f", val)
	}
}

func TestRecordSessionOperation(t *testing.T) {
	SessionOperations.Reset()

	RecordSessionOperation("ns", "agent1", "compress", "success")
	RecordSessionOperation("ns", "agent1", "compress", "success")
	RecordSessionOperation("ns", "agent1", "terminate", "error")

	val := testutil.ToFloat64(SessionOperations.WithLabelValues("ns", "agent1", "compress", "success"))
	if val != 2.0 {
		t.Errorf("expected 2.0 compress successes, got %f", val)
	}

	val = testutil.ToFloat64(SessionOperations.WithLabelValues("ns", "agent1", "terminate", "error"))
	if val != 1.0 {
		t.Errorf("expected 1.0 terminate errors, got %f", val)
	}
}

func TestRecordDataPlaneStatus(t *testing.T) {
	DataPlaneConnected.Reset()

	RecordDataPlaneStatus("ns", "agent1", true, 3)
	val := testutil.ToFloat64(DataPlaneConnected.WithLabelValues("ns", "agent1", "3"))
	if val != 1.0 {
		t.Errorf("expected 1.0 (connected), got %f", val)
	}

	RecordDataPlaneStatus("ns", "agent1", false, 3)
	val = testutil.ToFloat64(DataPlaneConnected.WithLabelValues("ns", "agent1", "3"))
	if val != 0.0 {
		t.Errorf("expected 0.0 (disconnected), got %f", val)
	}
}

func TestForgetAgent(t *testing.T) {
	AgentInfo.Reset()
	AgentReplicas.Reset()

	RecordAgent("ns", "agent1", "Declarative", "agentscope-java", "CP-Managed", 3, 2, 2)

	ForgetAgent("ns", "agent1")

	// After forget, the metric should no longer be present (ToFloat64 returns 0 for missing)
	val := testutil.ToFloat64(AgentReplicas.WithLabelValues("ns", "agent1", "desired"))
	if val != 0.0 {
		t.Errorf("expected 0 after ForgetAgent, got %f", val)
	}
}

func TestRecordReconcileError(t *testing.T) {
	ReconcileErrors.Reset()

	RecordReconcileError("agent", "UpdateFailed")
	RecordReconcileError("agent", "UpdateFailed")
	RecordReconcileError("session-poller", "ProbeFailed")

	val := testutil.ToFloat64(ReconcileErrors.WithLabelValues("agent", "UpdateFailed"))
	if val != 2.0 {
		t.Errorf("expected 2.0, got %f", val)
	}

	val = testutil.ToFloat64(ReconcileErrors.WithLabelValues("session-poller", "ProbeFailed"))
	if val != 1.0 {
		t.Errorf("expected 1.0, got %f", val)
	}
}

package sessionops

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func boolPtr(v bool) *bool { return &v }

func newTestStore(t *testing.T) store.Store {
	t.Helper()
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatalf("memory.Open: %v", err)
	}
	return st
}

func TestCheckGate_BusyUnknownRequiresForce(t *testing.T) {
	sess := &store.Session{Phase: store.SessionPhaseActive, Busy: nil}

	forced, err := checkGate(sess, CommandCompress, false)
	if err == nil || err.Code != CodeBusy || err.Hint != HintForceConfirm {
		t.Fatalf("expected busy/force_confirm without force, got forced=%v err=%v", forced, err)
	}

	forced, err = checkGate(sess, CommandCompress, true)
	if err != nil || !forced {
		t.Fatalf("expected force allow with Forced, got forced=%v err=%v", forced, err)
	}
}

func TestCheckGate_BusyTrueBlocksCompress(t *testing.T) {
	sess := &store.Session{Phase: store.SessionPhaseActive, Busy: boolPtr(true)}
	_, err := checkGate(sess, CommandCompress, true)
	if err == nil || err.Code != CodeBusy || err.Hint != HintWaitIdle {
		t.Fatalf("expected busy/wait_idle even with force, got %v", err)
	}
}

func TestCheckGate_AbortAllowedWhenBusy(t *testing.T) {
	sess := &store.Session{Phase: store.SessionPhaseActive, Busy: boolPtr(true)}
	forced, err := checkGate(sess, CommandAbort, false)
	if err != nil || forced {
		t.Fatalf("abort should be allowed when busy, got forced=%v err=%v", forced, err)
	}
}

func TestCheckGate_TerminateRejectedWhenTerminated(t *testing.T) {
	sess := &store.Session{Phase: store.SessionPhaseTerminated, Busy: boolPtr(false)}
	_, err := checkGate(sess, CommandTerminate, false)
	if err == nil || err.Code != CodeNotFound {
		t.Fatalf("expected not_found for terminated, got %v", err)
	}
}

func TestCheckCapability_Missing(t *testing.T) {
	entry := &dataplane.Entry{
		Capabilities: []string{v1alpha1.CapabilitySessionReporting},
	}
	err := checkCapability(entry, CommandCompress)
	if err == nil || err.Code != CodeUnsupported || err.Status != http.StatusNotImplemented {
		t.Fatalf("expected unsupported, got %v", err)
	}
}

func TestCheckCapability_AbortRequiresSessionAbort(t *testing.T) {
	entry := &dataplane.Entry{
		Capabilities: []string{v1alpha1.CapabilitySessionCommand},
	}
	err := checkCapability(entry, CommandAbort)
	if err == nil || err.Code != CodeUnsupported {
		t.Fatalf("abort should require session-abort, got %v", err)
	}
	entry.Capabilities = append(entry.Capabilities, v1alpha1.CapabilitySessionAbort)
	if err := checkCapability(entry, CommandAbort); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
}

func TestCheckInstanceReachable_Stale(t *testing.T) {
	reg := dataplane.NewRegistry()
	reg.Upsert(dataplane.Entry{
		AgentName:  "agent-a",
		Namespace:  "default",
		InstanceID: "inst-1",
		BaseURL:    "http://127.0.0.1:9",
	})
	// Mark stale by flipping Healthy after Upsert (Upsert sets Healthy=true).
	reg.MarkStale(reg.Get("inst-1").LastSeenAt.Add(dataplane.StaleAfter + 1))

	sess := &store.Session{InstanceRef: "inst-1"}
	_, err := checkInstanceReachable(reg, sess)
	if err == nil || err.Code != CodeUnreachable || err.Status != http.StatusServiceUnavailable {
		t.Fatalf("expected unreachable for stale instance, got %v", err)
	}
}

func TestCheckInstanceReachable_NoSiblingFallback(t *testing.T) {
	reg := dataplane.NewRegistry()
	reg.Upsert(dataplane.Entry{
		AgentName:  "agent-a",
		Namespace:  "default",
		InstanceID: "sibling",
		BaseURL:    "http://127.0.0.1:9",
	})
	sess := &store.Session{InstanceRef: "missing", AgentName: "agent-a", Namespace: "default"}
	_, err := checkInstanceReachable(reg, sess)
	if err == nil || err.Code != CodeUnreachable {
		t.Fatalf("must not fall back to sibling, got %v", err)
	}
}

func TestRouter_Execute_HappyPathAndAudit(t *testing.T) {
	st := newTestStore(t)
	reg := dataplane.NewRegistry()

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/agentscope/sessions/sess-1/compress":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"accepted":  true,
				"commandId": "cmd-from-dp",
				"phase":     "compressing",
				"result":    map[string]any{},
			})
		case r.Method == http.MethodGet && r.URL.Path == "/agentscope/sessions/sess-1/state":
			busy := false
			_ = json.NewEncoder(w).Encode(prober.SessionState{
				SessionID: "sess-1",
				Phase:     store.SessionPhaseCompressing,
				Busy:      &busy,
			})
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	reg.Upsert(dataplane.Entry{
		AgentName:     "agent-a",
		Namespace:     "default",
		InstanceID:    "inst-1",
		BaseURL:       srv.URL,
		Capabilities:  []string{v1alpha1.CapabilitySessionCommand},
		ContractLevel: 3,
	})

	sess, err := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID:   "sess-1",
		AgentName:   "agent-a",
		Namespace:   "default",
		Framework:   "test",
		Phase:       store.SessionPhaseIdle,
		Busy:        boolPtr(false),
		InstanceRef: "inst-1",
	})
	if err != nil {
		t.Fatal(err)
	}

	r := NewRouter(reg, st, prober.NewHTTPProber(), nil)
	result, err := r.Execute(context.Background(), sess, Request{
		Command:   CommandCompress,
		Operator:  "tester",
		CommandID: "cmd-test-1",
	})
	if err != nil {
		t.Fatalf("Execute: %v", err)
	}
	if !result.Accepted || result.CommandID == "" {
		t.Fatalf("unexpected result: %+v", result)
	}

	cached, err := r.Execute(context.Background(), sess, Request{
		Command:   CommandCompress,
		Operator:  "tester",
		CommandID: "cmd-test-1",
	})
	if err != nil {
		t.Fatalf("idempotent Execute: %v", err)
	}
	if !cached.Cached {
		t.Fatalf("expected cached result")
	}

	list, err := st.Commands().List(context.Background(), store.SessionCommandFilter{SessionFK: sess.ID})
	if err != nil || len(list) == 0 {
		t.Fatalf("expected audit rows, err=%v len=%d", err, len(list))
	}
}

func TestRouter_Execute_MissingCapability(t *testing.T) {
	st := newTestStore(t)
	reg := dataplane.NewRegistry()
	reg.Upsert(dataplane.Entry{
		AgentName:    "agent-a",
		Namespace:    "default",
		InstanceID:   "inst-1",
		BaseURL:      "http://127.0.0.1:9",
		Capabilities: []string{v1alpha1.CapabilitySessionReporting},
	})
	sess, _ := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "sess-1", AgentName: "agent-a", Namespace: "default",
		Framework: "test", Phase: store.SessionPhaseIdle, Busy: boolPtr(false), InstanceRef: "inst-1",
	})

	r := NewRouter(reg, st, prober.NewHTTPProber(), nil)
	_, err := r.Execute(context.Background(), sess, Request{Command: CommandCompress})
	opErr, ok := AsError(err)
	if !ok || opErr.Code != CodeUnsupported {
		t.Fatalf("expected unsupported, got %v", err)
	}
}

func TestRouter_Execute_BusyUnknownWithoutForce(t *testing.T) {
	st := newTestStore(t)
	reg := dataplane.NewRegistry()
	reg.Upsert(dataplane.Entry{
		AgentName:    "agent-a",
		Namespace:    "default",
		InstanceID:   "inst-1",
		BaseURL:      "http://127.0.0.1:9",
		Capabilities: []string{v1alpha1.CapabilitySessionCommand},
	})
	sess, _ := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "sess-1", AgentName: "agent-a", Namespace: "default",
		Framework: "test", Phase: store.SessionPhaseActive, Busy: nil, InstanceRef: "inst-1",
	})

	r := NewRouter(reg, st, nil, nil)
	_, err := r.Execute(context.Background(), sess, Request{Command: CommandCompress})
	opErr, ok := AsError(err)
	if !ok || opErr.Code != CodeBusy || opErr.Hint != HintForceConfirm {
		t.Fatalf("expected busy/force_confirm, got %v", err)
	}
}

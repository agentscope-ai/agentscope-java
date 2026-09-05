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

package asdp_test

import (
	"context"
	"fmt"
	"net"
	"sync"
	"testing"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	"github.com/spring-ai-alibaba/aistio/internal/asdp"
)

// freePort asks the OS for an available TCP port.
func freePort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "localhost:0")
	if err != nil {
		t.Fatalf("failed to find free port: %v", err)
	}
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()
	return port
}

// testEventSink captures upstream events for assertions.
type testEventSink struct {
	mu                  sync.Mutex
	sessionReports      []capturedSessionReport
	attemptReports      []capturedExecutionAttemptReport
	conversationReports []*asdp.ConversationTurnReport
	eventReports        []*asdp.EventReport
	contextReports      []*asdp.ContextReport
	inventoryReports    []*asdp.InventoryReport
}

func (s *testEventSink) HandleConnect(tenant, namespace, agentID, bindingID, agentKey, instanceKey string, generation int64, runtimeName, sdkVersion string, capabilities []string) {
}

func (s *testEventSink) HandleDisconnect(tenant, namespace, agentID, bindingID, instanceKey string, generation int64) {
}

type capturedSessionReport struct {
	Tenant     string
	Namespace  string
	AgentID    string
	AgentName  string
	InstanceID string
	Report     *asdp.SessionReport
}

type capturedExecutionAttemptReport struct {
	Tenant    string
	Namespace string
	AgentName string
	Report    *asdp.ExecutionAttemptReport
}

func (s *testEventSink) HandleSessionReport(identity asdp.ReportIdentity, report *asdp.SessionReport) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sessionReports = append(s.sessionReports, capturedSessionReport{
		Tenant:     identity.Tenant,
		Namespace:  identity.Namespace,
		AgentID:    identity.AgentID,
		AgentName:  identity.AgentKey,
		InstanceID: identity.InstanceKey,
		Report:     report,
	})
}

func (s *testEventSink) HandleExecutionAttemptReport(tenant, namespace, agentID, bindingID, instanceKey string, instanceGeneration int64, report *asdp.ExecutionAttemptReport) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.attemptReports = append(s.attemptReports, capturedExecutionAttemptReport{
		Tenant:    tenant,
		Namespace: namespace,
		AgentName: agentID,
		Report:    report,
	})
}

func (s *testEventSink) HandleConversationTurnReport(identity asdp.ReportIdentity, report *asdp.ConversationTurnReport) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.conversationReports = append(s.conversationReports, report)
}

func (s *testEventSink) HandleEventReport(identity asdp.ReportIdentity, report *asdp.EventReport) *asdp.EventReportAck {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.eventReports = append(s.eventReports, report)
	ack := &asdp.EventReportAck{ReportId: report.GetReportId()}
	for _, event := range report.GetEvents() {
		ack.Committed = append(ack.Committed, &asdp.SessionEventCursor{SessionId: event.GetSessionId(), CommittedSeq: event.GetSeq()})
	}
	return ack
}

func (s *testEventSink) HandleContextReport(identity asdp.ReportIdentity, report *asdp.ContextReport) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.contextReports = append(s.contextReports, report)
}

func (s *testEventSink) HandleInventoryReport(identity asdp.ReportIdentity, report *asdp.InventoryReport) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.inventoryReports = append(s.inventoryReports, report)
}

func (s *testEventSink) getSessionReports() []capturedSessionReport {
	s.mu.Lock()
	defer s.mu.Unlock()
	cp := make([]capturedSessionReport, len(s.sessionReports))
	copy(cp, s.sessionReports)
	return cp
}

// startTestServer creates an ASDP server on a free port and returns a connected
// gRPC client plus a cleanup function. The server is ready for RPCs when this returns.
func startTestServer(t *testing.T, sink asdp.EventSink) (*asdp.Server, asdp.AgentDataPlaneServiceClient, func()) {
	t.Helper()

	port := freePort(t)
	addr := fmt.Sprintf("localhost:%d", port)

	srv, err := asdp.NewServer(asdp.ServerConfig{Addr: addr})
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	if sink != nil {
		srv.SetEventSink(sink)
	}

	errCh := make(chan error, 1)
	go func() {
		errCh <- srv.Start()
	}()

	// Wait for the server to accept connections (poll up to 2s).
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		c, err := net.DialTimeout("tcp", addr, 100*time.Millisecond)
		if err == nil {
			c.Close()
			break
		}
		time.Sleep(20 * time.Millisecond)
	}

	conn, err := grpc.NewClient(addr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		srv.Stop()
		t.Fatalf("failed to connect to test server: %v", err)
	}

	client := asdp.NewAgentDataPlaneServiceClient(conn)
	cleanup := func() {
		conn.Close()
		srv.Stop()
	}
	return srv, client, cleanup
}

// doHandshake opens a Connect stream and performs the handshake, returning the
// stream and the ConnectResponse. The caller owns closing the stream.
func doHandshake(t *testing.T, client asdp.AgentDataPlaneServiceClient, meta *asdp.UpstreamMeta, req *asdp.ConnectRequest) (asdp.AgentDataPlaneService_ConnectClient, *asdp.ConnectResponse) {
	t.Helper()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)

	stream, err := client.Connect(ctx)
	if err != nil {
		t.Fatalf("Connect RPC failed: %v", err)
	}

	if err := stream.Send(&asdp.Upstream{
		Meta:    meta,
		Payload: &asdp.Upstream_Connect{Connect: req},
	}); err != nil {
		t.Fatalf("failed to send ConnectRequest: %v", err)
	}

	resp, err := stream.Recv()
	if err != nil {
		t.Fatalf("failed to receive ConnectResponse: %v", err)
	}

	ack := resp.GetConnectAck()
	if ack == nil {
		t.Fatal("expected ConnectResponse payload, got nil")
	}
	return stream, ack
}

func validMeta() *asdp.UpstreamMeta {
	return &asdp.UpstreamMeta{
		Tenant:      "admin",
		AgentId:     "11111111-1111-1111-1111-111111111111",
		AgentKey:    "test-agent",
		BindingId:   "22222222-2222-2222-2222-222222222222",
		InstanceKey: "inst-001",
		Generation:  1,
		Namespace:   "default",
		Timestamp:   time.Now().Unix(),
	}
}

func validConnectReq() *asdp.ConnectRequest {
	return &asdp.ConnectRequest{
		Runtime:      "go",
		SdkVersion:   "0.1.0",
		Capabilities: []string{"config_push"},
	}
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

func TestHandshakeAccepted(t *testing.T) {
	srv, client, cleanup := startTestServer(t, nil)
	defer cleanup()

	_, ack := doHandshake(t, client, validMeta(), validConnectReq())

	if !ack.Accepted {
		t.Fatalf("expected handshake accepted, got rejected: %s", ack.RejectReason)
	}
	if ack.ControlPlaneVersion == "" {
		t.Error("expected non-empty ControlPlaneVersion")
	}

	// The connect handler registers the connection during handshake.
	// Allow a brief moment for RegisterConnection to complete.
	time.Sleep(50 * time.Millisecond)
	if got := srv.ConnectionCount(); got < 1 {
		t.Errorf("expected at least 1 connection, got %d", got)
	}
}

func TestHandshakeRejectedMissingMeta(t *testing.T) {
	_, client, cleanup := startTestServer(t, nil)
	defer cleanup()

	// Send ConnectRequest without meta — server should reject.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	stream, err := client.Connect(ctx)
	if err != nil {
		t.Fatalf("Connect RPC failed: %v", err)
	}

	if err := stream.Send(&asdp.Upstream{
		Meta:    nil,
		Payload: &asdp.Upstream_Connect{Connect: validConnectReq()},
	}); err != nil {
		t.Fatalf("send failed: %v", err)
	}

	resp, err := stream.Recv()
	if err != nil {
		t.Fatalf("recv failed: %v", err)
	}

	ack := resp.GetConnectAck()
	if ack == nil {
		t.Fatal("expected ConnectResponse payload")
	}
	if ack.Accepted {
		t.Error("expected handshake to be rejected when meta is nil")
	}
}

func TestHandshakeRejectedEmptyFields(t *testing.T) {
	_, client, cleanup := startTestServer(t, nil)
	defer cleanup()

	// Send meta with empty required fields — connect handler rejects.
	_, ack := doHandshake(t, client, &asdp.UpstreamMeta{
		AgentKey:    "",
		InstanceKey: "",
		Namespace:   "",
	}, validConnectReq())

	if ack.Accepted {
		t.Error("expected handshake to be rejected when meta fields are empty")
	}
	if ack.RejectReason == "" {
		t.Error("expected a reject reason")
	}
}

func TestConnectionIdentityIncludesTenant(t *testing.T) {
	srv, client, cleanup := startTestServer(t, nil)
	defer cleanup()

	metaA := validMeta()
	metaA.Tenant = "tenant-a"
	streamA, ack := doHandshake(t, client, metaA, validConnectReq())
	if !ack.Accepted {
		t.Fatalf("tenant-a handshake rejected: %s", ack.RejectReason)
	}
	metaB := validMeta()
	metaB.Tenant = "tenant-b"
	streamB, ack := doHandshake(t, client, metaB, validConnectReq())
	if !ack.Accepted {
		t.Fatalf("tenant-b handshake rejected: %s", ack.RejectReason)
	}
	t.Cleanup(func() {
		_ = streamA.CloseSend()
		_ = streamB.CloseSend()
	})

	deadline := time.Now().Add(time.Second)
	for srv.ConnectionCount() != 2 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if srv.ConnectionCount() != 2 {
		t.Fatalf("same namespace/instance in two tenants collapsed to %d connection(s)", srv.ConnectionCount())
	}
	connA, okA := srv.GetConnectionForTenant("tenant-a", metaA.Namespace, metaA.InstanceKey)
	connB, okB := srv.GetConnectionForTenant("tenant-b", metaB.Namespace, metaB.InstanceKey)
	if !okA || !okB || connA == connB || connA.Tenant != "tenant-a" || connB.Tenant != "tenant-b" {
		t.Fatalf("tenant connection lookup failed: a=%+v/%v b=%+v/%v", connA, okA, connB, okB)
	}
	if _, ok := srv.GetConnection(metaA.Namespace, metaA.InstanceKey); ok {
		t.Fatal("tenant-ambiguous connection lookup must fail closed")
	}
	srv.UpdateInventory("tenant-a", metaA.Namespace, metaA.AgentId, metaA.InstanceKey, &asdp.InventoryReport{
		Subagents: []*asdp.SubagentInfo{{Name: "worker-a"}},
	})
	srv.UpdateInventory("tenant-b", metaB.Namespace, metaB.AgentId, metaB.InstanceKey, &asdp.InventoryReport{
		Subagents: []*asdp.SubagentInfo{{Name: "worker-b"}},
	})
	invA := srv.GetInventoriesForAgent("tenant-a", metaA.Namespace, metaA.AgentKey)
	invB := srv.GetInventoriesForAgent("tenant-b", metaB.Namespace, metaB.AgentKey)
	if len(invA) != 1 || invA[0].Report.GetSubagents()[0].GetName() != "worker-a" {
		t.Fatalf("tenant-a inventory = %+v", invA)
	}
	if len(invB) != 1 || invB[0].Report.GetSubagents()[0].GetName() != "worker-b" {
		t.Fatalf("tenant-b inventory = %+v", invB)
	}
}

func TestConnectionIdentityIncludesAgentWhenInstanceKeyIsShared(t *testing.T) {
	srv, client, cleanup := startTestServer(t, nil)
	defer cleanup()

	metaA := validMeta()
	metaA.AgentKey = "agent-a"
	streamA, ack := doHandshake(t, client, metaA, validConnectReq())
	if !ack.Accepted {
		t.Fatalf("agent-a handshake rejected: %s", ack.RejectReason)
	}
	metaB := validMeta()
	metaB.AgentId = "33333333-3333-3333-3333-333333333333"
	metaB.BindingId = "44444444-4444-4444-4444-444444444444"
	metaB.AgentKey = "agent-b"
	streamB, ack := doHandshake(t, client, metaB, validConnectReq())
	if !ack.Accepted {
		t.Fatalf("agent-b handshake rejected: %s", ack.RejectReason)
	}
	t.Cleanup(func() {
		_ = streamA.CloseSend()
		_ = streamB.CloseSend()
	})

	deadline := time.Now().Add(time.Second)
	for srv.ConnectionCount() != 2 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if srv.ConnectionCount() != 2 {
		t.Fatalf("same instance key for two Agents collapsed to %d connection(s)", srv.ConnectionCount())
	}
	if _, ok := srv.GetConnectionForTenant(metaA.Tenant, metaA.Namespace, metaA.InstanceKey); ok {
		t.Fatal("agent-ambiguous connection lookup must fail closed")
	}
	connA, okA := srv.GetConnectionForAgentInstance(metaA.Tenant, metaA.Namespace, metaA.AgentId, metaA.InstanceKey)
	connB, okB := srv.GetConnectionForAgentInstance(metaB.Tenant, metaB.Namespace, metaB.AgentId, metaB.InstanceKey)
	if !okA || !okB || connA == connB || connA.AgentID != metaA.AgentId || connB.AgentID != metaB.AgentId {
		t.Fatalf("agent connection lookup failed: a=%+v/%v b=%+v/%v", connA, okA, connB, okB)
	}

	payloadA := []byte(`{"attemptId":"attempt-a","agentTaskId":"task-a","runtimeBinding":{"sessionId":"session-a"}}`)
	if err := srv.Distributor().SendExecutionAttemptCommand(metaA.Tenant, metaA.Namespace, metaA.AgentId,
		metaA.InstanceKey, "session-a", "dispatch", payloadA); err != nil {
		t.Fatalf("send agent-a attempt: %v", err)
	}
	msgA, err := streamA.Recv()
	if err != nil || msgA.GetExecutionAttempt().GetAgentTaskId() != "task-a" {
		t.Fatalf("agent-a received %+v, err=%v", msgA, err)
	}

	payloadB := []byte(`{"attemptId":"attempt-b","agentTaskId":"task-b","runtimeBinding":{"sessionId":"session-b"}}`)
	if err := srv.Distributor().SendExecutionAttemptCommand(metaB.Tenant, metaB.Namespace, metaB.AgentId,
		metaB.InstanceKey, "session-b", "dispatch", payloadB); err != nil {
		t.Fatalf("send agent-b attempt: %v", err)
	}
	msgB, err := streamB.Recv()
	if err != nil || msgB.GetExecutionAttempt().GetAgentTaskId() != "task-b" {
		t.Fatalf("agent-b received %+v, err=%v", msgB, err)
	}
}

func TestConfigSnapshotsAreTenantIsolated(t *testing.T) {
	store := asdp.NewSnapshotStore()
	a, changed, err := store.UpdateSnapshot("tenant-a", "shared", "same-agent", asdp.ConfigType_CONFIG_TYPE_AGENT, map[string]string{"model": "a"})
	if err != nil || !changed {
		t.Fatalf("tenant-a snapshot: changed=%v err=%v", changed, err)
	}
	b, changed, err := store.UpdateSnapshot("tenant-b", "shared", "same-agent", asdp.ConfigType_CONFIG_TYPE_AGENT, map[string]string{"model": "b"})
	if err != nil || !changed {
		t.Fatalf("tenant-b snapshot: changed=%v err=%v", changed, err)
	}
	if a.Version != "v1" || b.Version != "v1" || string(a.Resources) == string(b.Resources) {
		t.Fatalf("snapshots collided: a=%+v b=%+v", a, b)
	}
	if got := store.GetAllSnapshots("tenant-a", "shared", "same-agent"); len(got) != 1 || string(got[0].Resources) != string(a.Resources) {
		t.Fatalf("tenant-a snapshots = %+v", got)
	}
}

func TestConfigPushAndAck(t *testing.T) {
	srv, client, cleanup := startTestServer(t, nil)
	defer cleanup()

	meta := validMeta()
	stream, ack := doHandshake(t, client, meta, validConnectReq())
	if !ack.Accepted {
		t.Fatalf("handshake rejected: %s", ack.RejectReason)
	}

	// Drain any initial full-sync pushes (the server pushes existing snapshots
	// after handshake, but there are none yet so this may be empty).
	// Push a config through the distributor.
	err := srv.Distributor().PushConfig(meta.Tenant, meta.Namespace, meta.AgentKey,
		asdp.ConfigType_CONFIG_TYPE_AGENT, map[string]string{"key": "value"})
	if err != nil {
		t.Fatalf("PushConfig failed: %v", err)
	}

	// Client should receive the ConfigPush.
	resp, err := stream.Recv()
	if err != nil {
		t.Fatalf("failed to receive ConfigPush: %v", err)
	}

	push := resp.GetConfigPush()
	if push == nil {
		t.Fatal("expected ConfigPush payload")
	}
	if push.ConfigType != asdp.ConfigType_CONFIG_TYPE_AGENT {
		t.Errorf("expected CONFIG_TYPE_AGENT, got %v", push.ConfigType)
	}
	if push.Version == "" {
		t.Error("expected non-empty version")
	}

	// Send ACK back.
	if err := stream.Send(&asdp.Upstream{
		Meta: meta,
		Payload: &asdp.Upstream_ConfigAck{ConfigAck: &asdp.ConfigAck{
			ConfigType: push.ConfigType,
			Version:    push.Version,
			Nonce:      push.Nonce,
			Accepted:   true,
		}},
	}); err != nil {
		t.Fatalf("failed to send ConfigAck: %v", err)
	}

	// No crash or error expected — ACK is processed server-side (logged).
}

func TestConfigPushAndNack(t *testing.T) {
	srv, client, cleanup := startTestServer(t, nil)
	defer cleanup()

	meta := validMeta()
	stream, ack := doHandshake(t, client, meta, validConnectReq())
	if !ack.Accepted {
		t.Fatalf("handshake rejected: %s", ack.RejectReason)
	}

	err := srv.Distributor().PushConfig(meta.Tenant, meta.Namespace, meta.AgentKey,
		asdp.ConfigType_CONFIG_TYPE_TOOL, map[string]string{"tool": "search"})
	if err != nil {
		t.Fatalf("PushConfig failed: %v", err)
	}

	resp, err := stream.Recv()
	if err != nil {
		t.Fatalf("failed to receive ConfigPush: %v", err)
	}
	push := resp.GetConfigPush()
	if push == nil {
		t.Fatal("expected ConfigPush payload")
	}

	// Send NACK.
	if err := stream.Send(&asdp.Upstream{
		Meta: meta,
		Payload: &asdp.Upstream_ConfigAck{ConfigAck: &asdp.ConfigAck{
			ConfigType:   push.ConfigType,
			Version:      push.Version,
			Nonce:        push.Nonce,
			Accepted:     false,
			RejectReason: "bad config",
		}},
	}); err != nil {
		t.Fatalf("failed to send NACK: %v", err)
	}

	// NACK is logged server-side; verify no crash by continuing the stream.
	// Send a heartbeat to prove the stream is still alive.
	if err := stream.Send(&asdp.Upstream{
		Meta:    meta,
		Payload: &asdp.Upstream_Heartbeat{Heartbeat: &asdp.Heartbeat{Timestamp: time.Now().Unix()}},
	}); err != nil {
		t.Fatalf("stream broken after NACK: %v", err)
	}
}

func TestSessionReport(t *testing.T) {
	sink := &testEventSink{}
	_, client, cleanup := startTestServer(t, sink)
	defer cleanup()

	meta := validMeta()
	stream, ack := doHandshake(t, client, meta, validConnectReq())
	if !ack.Accepted {
		t.Fatalf("handshake rejected: %s", ack.RejectReason)
	}

	report := &asdp.SessionReport{
		Sessions: []*asdp.SessionSnapshot{
			{
				SessionId:    "sess-1",
				Phase:        "active",
				MessageCount: 42,
				PromptTokens: 1000,
			},
			{
				SessionId:    "sess-2",
				Phase:        "idle",
				MessageCount: 5,
			},
		},
	}

	if err := stream.Send(&asdp.Upstream{
		Meta:    meta,
		Payload: &asdp.Upstream_SessionReport{SessionReport: report},
	}); err != nil {
		t.Fatalf("failed to send SessionReport: %v", err)
	}

	// Allow time for the server to process the message.
	time.Sleep(200 * time.Millisecond)

	reports := sink.getSessionReports()
	if len(reports) == 0 {
		t.Fatal("expected at least 1 session report in event sink")
	}

	got := reports[0]
	if got.Namespace != meta.Namespace {
		t.Errorf("namespace: want %q, got %q", meta.Namespace, got.Namespace)
	}
	if got.AgentID != meta.AgentId || got.AgentName != meta.AgentKey {
		t.Errorf("agent identity: want %q/%q, got %q/%q", meta.AgentId, meta.AgentKey, got.AgentID, got.AgentName)
	}
	if got.InstanceID != meta.InstanceKey {
		t.Errorf("instanceKey: want %q, got %q", meta.InstanceKey, got.InstanceID)
	}
	if len(got.Report.Sessions) != 2 {
		t.Errorf("expected 2 session snapshots, got %d", len(got.Report.Sessions))
	}
}

func TestHeartbeat(t *testing.T) {
	_, client, cleanup := startTestServer(t, nil)
	defer cleanup()

	meta := validMeta()
	stream, ack := doHandshake(t, client, meta, validConnectReq())
	if !ack.Accepted {
		t.Fatalf("handshake rejected: %s", ack.RejectReason)
	}

	ts := time.Now().Unix()
	if err := stream.Send(&asdp.Upstream{
		Meta:    meta,
		Payload: &asdp.Upstream_Heartbeat{Heartbeat: &asdp.Heartbeat{Timestamp: ts}},
	}); err != nil {
		t.Fatalf("failed to send Heartbeat: %v", err)
	}

	resp, err := stream.Recv()
	if err != nil {
		t.Fatalf("failed to receive Heartbeat response: %v", err)
	}

	hb := resp.GetHeartbeat()
	if hb == nil {
		t.Fatal("expected Heartbeat payload in response")
	}
	if hb.Timestamp != ts {
		t.Errorf("heartbeat timestamp: want %d, got %d", ts, hb.Timestamp)
	}
}

func TestDisconnectCleansUp(t *testing.T) {
	srv, client, cleanup := startTestServer(t, nil)
	defer cleanup()

	meta := validMeta()
	stream, ack := doHandshake(t, client, meta, validConnectReq())
	if !ack.Accepted {
		t.Fatalf("handshake rejected: %s", ack.RejectReason)
	}

	// Allow registration to complete.
	time.Sleep(50 * time.Millisecond)
	if srv.ConnectionCount() < 1 {
		t.Fatal("expected at least 1 connection after handshake")
	}

	// Close the client side of the stream.
	if err := stream.CloseSend(); err != nil {
		t.Fatalf("CloseSend failed: %v", err)
	}

	// Wait for the server to process the disconnect.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if srv.ConnectionCount() == 0 {
			return // success
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Errorf("expected 0 connections after disconnect, got %d", srv.ConnectionCount())
}

// TestEventContextInventoryReports covers the Level-2/Level-4/inventory
// upstream messages: they must be dispatched to the EventSink, and the
// inventory must land in the server connection registry.
func TestEventContextInventoryReports(t *testing.T) {
	sink := &testEventSink{}
	srv, client, cleanup := startTestServer(t, sink)
	defer cleanup()

	meta := validMeta()
	stream, ack := doHandshake(t, client, meta, validConnectReq())
	if !ack.Accepted {
		t.Fatalf("handshake rejected: %s", ack.RejectReason)
	}

	// Level 2: event batch.
	if err := stream.Send(&asdp.Upstream{
		Meta: meta,
		Payload: &asdp.Upstream_EventReport{EventReport: &asdp.EventReport{
			ReportId: "report-1",
			Events: []*asdp.SessionEventMsg{
				{SessionId: "sess-1", Seq: 1, EventType: "message", Role: "user", Content: "hi", OccurredAt: time.Now().UnixMilli()},
			},
		}},
	}); err != nil {
		t.Fatalf("send EventReport: %v", err)
	}
	for {
		downstream, err := stream.Recv()
		if err != nil {
			t.Fatalf("receive EventReportAck: %v", err)
		}
		if eventAck := downstream.GetEventAck(); eventAck != nil {
			if eventAck.ReportId != "report-1" || len(eventAck.Committed) != 1 ||
				eventAck.Committed[0].SessionId != "sess-1" || eventAck.Committed[0].CommittedSeq != 1 {
				t.Fatalf("unexpected EventReportAck: %+v", eventAck)
			}
			break
		}
	}

	// Level 4: context report.
	if err := stream.Send(&asdp.Upstream{
		Meta: meta,
		Payload: &asdp.Upstream_ContextReport{ContextReport: &asdp.ContextReport{
			SessionId:   "sess-1",
			ContextHash: "hash-1",
			Messages:    []byte(`[{"role":"user","content":"hi"}]`),
			Framework:   "claude-agent-sdk",
		}},
	}); err != nil {
		t.Fatalf("send ContextReport: %v", err)
	}

	// Inventory report.
	if err := stream.Send(&asdp.Upstream{
		Meta: meta,
		Payload: &asdp.Upstream_Inventory{Inventory: &asdp.InventoryReport{
			Subagents:  []*asdp.SubagentInfo{{Name: "researcher", InvokeCount: 2}},
			Workspaces: []*asdp.WorkspaceInfo{{Path: "/tmp/ws", Mode: "shared"}},
			Health:     &asdp.InstanceHealth{Healthy: true, ActiveSessions: 1},
		}},
	}); err != nil {
		t.Fatalf("send InventoryReport: %v", err)
	}

	// Wait for async dispatch.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		sink.mu.Lock()
		gotAll := len(sink.eventReports) == 1 && len(sink.contextReports) == 1 && len(sink.inventoryReports) == 1
		sink.mu.Unlock()
		if gotAll {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}

	sink.mu.Lock()
	defer sink.mu.Unlock()
	if len(sink.eventReports) != 1 || len(sink.eventReports[0].Events) != 1 {
		t.Fatalf("eventReports = %+v", sink.eventReports)
	}
	if sink.eventReports[0].Events[0].Content != "hi" {
		t.Errorf("event content = %q", sink.eventReports[0].Events[0].Content)
	}
	if len(sink.contextReports) != 1 || sink.contextReports[0].ContextHash != "hash-1" {
		t.Fatalf("contextReports = %+v", sink.contextReports)
	}
	if len(sink.inventoryReports) != 1 || sink.inventoryReports[0].Subagents[0].Name != "researcher" {
		t.Fatalf("inventoryReports = %+v", sink.inventoryReports)
	}

	// The inventory registry must hold the latest report for this instance.
	inv, ok := srv.GetInventory(meta.Namespace, meta.InstanceKey)
	if !ok {
		t.Fatal("inventory not registered")
	}
	if inv.AgentName != meta.AgentKey || len(inv.Report.Subagents) != 1 {
		t.Errorf("registry inventory = %+v", inv)
	}
	if inv.Tenant != meta.Tenant {
		t.Errorf("inventory tenant = %q, want %q", inv.Tenant, meta.Tenant)
	}
	invs := srv.GetInventoriesForAgent(meta.Tenant, meta.Namespace, meta.AgentKey)
	if len(invs) != 1 {
		t.Errorf("GetInventoriesForAgent returned %d entries", len(invs))
	}
}

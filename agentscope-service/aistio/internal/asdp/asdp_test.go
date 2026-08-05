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
	mu               sync.Mutex
	sessionReports   []capturedSessionReport
	teamEventReports []capturedTeamEventReport
	eventReports     []*asdp.EventReport
	contextReports   []*asdp.ContextReport
	inventoryReports []*asdp.InventoryReport
}

type capturedSessionReport struct {
	Namespace  string
	AgentName  string
	InstanceID string
	Report     *asdp.SessionReport
}

type capturedTeamEventReport struct {
	Namespace string
	AgentName string
	Report    *asdp.TeamEventReport
}

func (s *testEventSink) HandleSessionReport(namespace, agentName, instanceID string, report *asdp.SessionReport) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sessionReports = append(s.sessionReports, capturedSessionReport{
		Namespace:  namespace,
		AgentName:  agentName,
		InstanceID: instanceID,
		Report:     report,
	})
}

func (s *testEventSink) HandleTeamEventReport(namespace, agentName string, report *asdp.TeamEventReport) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.teamEventReports = append(s.teamEventReports, capturedTeamEventReport{
		Namespace: namespace,
		AgentName: agentName,
		Report:    report,
	})
}

func (s *testEventSink) HandleEventReport(namespace, agentName, instanceID string, report *asdp.EventReport) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.eventReports = append(s.eventReports, report)
}

func (s *testEventSink) HandleContextReport(namespace, agentName, instanceID string, report *asdp.ContextReport) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.contextReports = append(s.contextReports, report)
}

func (s *testEventSink) HandleInventoryReport(namespace, agentName, instanceID string, report *asdp.InventoryReport) {
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
		AgentName:  "test-agent",
		InstanceId: "inst-001",
		Namespace:  "default",
		Timestamp:  time.Now().Unix(),
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
		AgentName:  "",
		InstanceId: "",
		Namespace:  "",
	}, validConnectReq())

	if ack.Accepted {
		t.Error("expected handshake to be rejected when meta fields are empty")
	}
	if ack.RejectReason == "" {
		t.Error("expected a reject reason")
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
	err := srv.Distributor().PushConfig(meta.Namespace, meta.AgentName,
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

	err := srv.Distributor().PushConfig(meta.Namespace, meta.AgentName,
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
	if got.AgentName != meta.AgentName {
		t.Errorf("agentName: want %q, got %q", meta.AgentName, got.AgentName)
	}
	if got.InstanceID != meta.InstanceId {
		t.Errorf("instanceID: want %q, got %q", meta.InstanceId, got.InstanceID)
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
			Events: []*asdp.SessionEventMsg{
				{SessionId: "sess-1", Seq: 1, EventType: "message", Role: "user", Content: "hi", OccurredAt: time.Now().UnixMilli()},
			},
		}},
	}); err != nil {
		t.Fatalf("send EventReport: %v", err)
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
	inv, ok := srv.GetInventory(meta.Namespace, meta.InstanceId)
	if !ok {
		t.Fatal("inventory not registered")
	}
	if inv.AgentName != meta.AgentName || len(inv.Report.Subagents) != 1 {
		t.Errorf("registry inventory = %+v", inv)
	}
	invs := srv.GetInventoriesForAgent(meta.Namespace, meta.AgentName)
	if len(invs) != 1 {
		t.Errorf("GetInventoriesForAgent returned %d entries", len(invs))
	}
}

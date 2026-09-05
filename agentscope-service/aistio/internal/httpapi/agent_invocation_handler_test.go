// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/features"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func setupConversationAgent(t *testing.T) (store.Store, *controlmodel.Agent, *controlmodel.AgentBinding, *controlmodel.AgentInstance) {
	t.Helper()
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "t", Namespace: "n",
		AgentKey: "playground-agent", DisplayName: "Playground Agent", Status: controlmodel.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	binding, err := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{AgentID: agent.ID,
		Tenant: "t", Namespace: "n", Kind: controlmodel.DataPlaneExternalApplication,
		Configuration: json.RawMessage(`{"instanceSelector":{}}`), Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	instance, err := st.RuntimeRegistry().UpsertAgentInstance(ctx, &controlmodel.AgentInstance{Tenant: "t", Namespace: "n",
		AgentID: agent.ID, BindingID: binding.ID, BackendKind: binding.Kind, InstanceKey: "external-1",
		Health: controlmodel.RuntimeHealthHealthy, Capacity: 2, Capabilities: json.RawMessage(`["conversation-inbound"]`)})
	if err != nil {
		t.Fatal(err)
	}
	runtimeBinding, err := binding.RuntimeBinding()
	if err != nil {
		t.Fatal(err)
	}
	_, err = st.Orchestration().PutRuntimePolicy(ctx, &controlmodel.AgentRuntimePolicy{Tenant: "t", Namespace: "n",
		AgentRef: agent.ID.String(), SelectionMode: "ordered", FallbackMode: "disabled",
		Candidates: []controlmodel.RuntimeBindingCandidate{{Binding: runtimeBinding}}})
	if err != nil {
		t.Fatal(err)
	}
	return st, agent, binding, instance
}

func TestInvocationCapabilitiesAndDirectConversation(t *testing.T) {
	st, agent, binding, instance := setupConversationAgent(t)
	commands := &endpointCommandCapture{}
	server := NewServer(ServerOptions{Store: st, AuthToken: "console", ASDPCommands: commands})

	capReq := httptest.NewRequest(http.MethodGet, "/api/v1/agents/"+agent.ID.String()+
		"/invocation-capabilities?tenant=t&namespace=n", nil)
	capReq.Header.Set("Authorization", "Bearer console")
	capOut := httptest.NewRecorder()
	server.router.ServeHTTP(capOut, capReq)
	if capOut.Code != http.StatusOK {
		t.Fatalf("capabilities: %d %s", capOut.Code, capOut.Body)
	}
	var caps struct {
		Capabilities agentInvocationCapabilities `json:"capabilities"`
	}
	if err := json.Unmarshal(capOut.Body.Bytes(), &caps); err != nil {
		t.Fatal(err)
	}
	if caps.Capabilities.Job.State != "available" || caps.Capabilities.Conversation.State != "available" {
		t.Fatalf("unexpected invocation capabilities: %+v", caps.Capabilities)
	}

	body := `{"tenant":"t","namespace":"n","targetType":"agent","targetRef":"` + agent.ID.String() + `","mode":"conversation","message":"hello"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/playground/invocations", bytes.NewBufferString(body))
	req.Header.Set("Authorization", "Bearer console")
	req.Header.Set("Content-Type", "application/json")
	out := httptest.NewRecorder()
	server.router.ServeHTTP(out, req)
	if out.Code != http.StatusAccepted {
		t.Fatalf("invoke: %d %s", out.Code, out.Body)
	}
	var result struct {
		SessionID      string    `json:"sessionId"`
		SessionRef     uuid.UUID `json:"sessionRef"`
		EventsURL      string    `json:"eventsUrl"`
		EventStreamURL string    `json:"eventStreamUrl"`
		BindingID      uuid.UUID `json:"bindingId"`
	}
	if err := json.Unmarshal(out.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result.SessionID == "" || result.SessionRef == uuid.Nil ||
		result.EventsURL != "/api/v1/sessions/"+result.SessionRef.String()+"/events" ||
		result.EventStreamURL != "/api/v1/sessions/"+result.SessionRef.String()+"/events/stream" ||
		result.BindingID != binding.ID || len(commands.turns) != 1 {
		t.Fatalf("conversation was not dispatched: result=%+v commands=%d", result, len(commands.turns))
	}
	command := commands.turns[0]
	if command.GetAgentId() != agent.ID.String() || command.GetInstanceId() != instance.ID.String() || command.GetGeneration() != instance.Generation {
		t.Fatalf("runtime identity was not frozen: %+v", command)
	}
	sessions, err := st.Sessions().List(context.Background(), store.SessionFilter{Tenant: "t", Namespace: "n",
		AgentID: agent.ID, SessionID: result.SessionID, Limit: 1})
	if err != nil || len(sessions) != 1 || sessions[0].OriginType != "playground" {
		t.Fatalf("playground session was not projected: sessions=%+v err=%v", sessions, err)
	}
	originalOriginRef := sessions[0].OriginRef

	// The External Agent reports the same session through runtime inventory
	// before the user sends the next turn. That observation must not replace
	// the control-plane provenance used to authorize Playground continuation.
	_, err = st.Sessions().Upsert(context.Background(), &store.Session{
		Tenant:             "t",
		Namespace:          "n",
		AgentID:            agent.ID,
		BindingID:          binding.ID,
		AgentInstanceID:    instance.ID,
		InstanceGeneration: instance.Generation,
		AgentName:          agent.AgentKey,
		SessionID:          result.SessionID,
		InstanceRef:        instance.InstanceKey,
		OriginType:         "runtime",
		OriginRef:          "runtime-observation",
		Phase:              store.SessionPhaseIdle,
	})
	if err != nil {
		t.Fatal(err)
	}
	sessions, err = st.Sessions().List(context.Background(), store.SessionFilter{Tenant: "t", Namespace: "n",
		AgentID: agent.ID, SessionID: result.SessionID, Limit: 1})
	if err != nil || len(sessions) != 1 || sessions[0].OriginType != "playground" || sessions[0].OriginRef != originalOriginRef {
		t.Fatalf("runtime observation replaced control-plane provenance: sessions=%+v err=%v", sessions, err)
	}

	continueBody := `{"tenant":"t","namespace":"n","agentId":"` + agent.ID.String() + `","message":"second"}`
	continueReq := httptest.NewRequest(http.MethodPost, "/api/v1/playground/sessions/"+result.SessionID+"/turns",
		bytes.NewBufferString(continueBody))
	continueReq.Header.Set("Authorization", "Bearer console")
	continueReq.Header.Set("Content-Type", "application/json")
	continueOut := httptest.NewRecorder()
	server.router.ServeHTTP(continueOut, continueReq)
	if continueOut.Code != http.StatusAccepted || len(commands.turns) != 2 {
		t.Fatalf("continue: %d %s commands=%d", continueOut.Code, continueOut.Body, len(commands.turns))
	}
}

func TestIsPlaygroundSessionRecognizesLegacyOverwrittenOrigin(t *testing.T) {
	session := &store.Session{
		OriginType:  "runtime",
		TaskContext: json.RawMessage(`{"originType":"playground","originRef":"invocation-1"}`),
	}
	if !isPlaygroundSession(session) {
		t.Fatal("durable Playground provenance should allow an existing overwritten session to continue")
	}
	if isPlaygroundSession(&store.Session{OriginType: "runtime", TaskContext: json.RawMessage(`{"originType":"endpoint"}`)}) {
		t.Fatal("non-Playground provenance must not be accepted")
	}
}

func TestHostProviderResumeUsesAdvertisedAdapterCapability(t *testing.T) {
	capabilities := json.RawMessage(`{"providerCapabilities":{"codex":{"resume":true},"openclaw":{"resume":false}}}`)
	if !hostProviderSupportsResume(capabilities, "codex") {
		t.Fatal("Codex resume capability was not recognized")
	}
	if hostProviderSupportsResume(capabilities, "openclaw") ||
		hostProviderSupportsResume(json.RawMessage(`{"providers":{"codex":"1"}}`), "codex") {
		t.Fatal("provider resume must not be inferred from an opaque session ID or version")
	}
}

func setupHostedConversationAgent(t *testing.T) (store.Store, *controlmodel.Agent,
	*controlmodel.AgentBinding, *controlmodel.RuntimeHost) {
	t.Helper()
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "t", Namespace: "n",
		AgentKey: "hosted-chat", DisplayName: "Hosted Chat", Status: controlmodel.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	profile, err := st.RuntimeRegistry().UpsertRuntimeProfile(ctx, &controlmodel.RuntimeProfile{
		Tenant: "t", Namespace: "n", Name: "codex", Provider: "codex",
		Requirements: json.RawMessage(`{"sandbox":{"network":false}}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	pool, err := st.RuntimeRegistry().UpsertRuntimePool(ctx, &controlmodel.RuntimePool{
		Tenant: "t", Namespace: "n", Name: "coding", HostSelector: json.RawMessage(`{"region":"cn"}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	configuration, _ := json.Marshal(controlmodel.HostedBindingConfiguration{
		RuntimeProfileID: profile.ID, RuntimePoolID: pool.ID,
	})
	binding, err := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{
		AgentID: agent.ID, Tenant: "t", Namespace: "n", Kind: controlmodel.DataPlaneHostedRuntime,
		Configuration: configuration, Enabled: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	runtimeBinding, _ := binding.RuntimeBinding()
	_, err = st.Orchestration().PutRuntimePolicy(ctx, &controlmodel.AgentRuntimePolicy{
		Tenant: "t", Namespace: "n", AgentRef: agent.ID.String(), SelectionMode: "ordered",
		Candidates: []controlmodel.RuntimeBindingCandidate{{Binding: runtimeBinding}},
	})
	if err != nil {
		t.Fatal(err)
	}
	host, err := st.RuntimeRegistry().UpsertRuntimeHost(ctx, &controlmodel.RuntimeHost{
		Tenant: "t", Namespace: "n", HostKey: "hosted-chat-host", PoolName: pool.Name,
		State: controlmodel.RuntimeHostOnline, Capacity: 2, Labels: json.RawMessage(`{"region":"cn"}`),
		Capabilities: json.RawMessage(`{"providers":{"codex":"test"},"providerCapabilities":{"codex":{"resume":true}},"sandbox":{"network":false}}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	return st, agent, binding, host
}

func TestHostedPlaygroundConversationPersistsEventsAndResumesProviderSession(t *testing.T) {
	st, agent, binding, host := setupHostedConversationAgent(t)
	server := NewServer(ServerOptions{Store: st, AuthToken: "console", Features: features.Gates{RuntimeHost: true}})

	capReq := httptest.NewRequest(http.MethodGet, "/api/v1/agents/"+agent.ID.String()+
		"/invocation-capabilities?tenant=t&namespace=n", nil)
	capReq.Header.Set("Authorization", "Bearer console")
	capOut := httptest.NewRecorder()
	server.router.ServeHTTP(capOut, capReq)
	var caps struct {
		Capabilities agentInvocationCapabilities `json:"capabilities"`
	}
	if capOut.Code != http.StatusOK || json.Unmarshal(capOut.Body.Bytes(), &caps) != nil ||
		caps.Capabilities.Conversation.State != "available" ||
		caps.Capabilities.Features["resume"].State != "available" {
		t.Fatalf("hosted conversation capability: %d %s %+v", capOut.Code, capOut.Body, caps)
	}

	body := `{"tenant":"t","namespace":"n","targetType":"agent","targetRef":"` +
		agent.ID.String() + `","mode":"conversation","message":"first"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/playground/invocations", bytes.NewBufferString(body))
	req.Header.Set("Authorization", "Bearer console")
	req.Header.Set("Content-Type", "application/json")
	out := httptest.NewRecorder()
	server.router.ServeHTTP(out, req)
	if out.Code != http.StatusAccepted {
		t.Fatalf("invoke hosted conversation: %d %s", out.Code, out.Body)
	}
	var response struct {
		SessionID  string    `json:"sessionId"`
		SessionRef uuid.UUID `json:"sessionRef"`
	}
	if err := json.Unmarshal(out.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	attempts, err := st.ExecutionAttempts().List(context.Background(), store.ExecutionAttemptFilter{
		Tenant: "t", Namespace: "n", AgentID: agent.ID, SessionID: response.SessionID,
	})
	if err != nil || len(attempts) != 1 || attempts[0].BindingID != binding.ID ||
		attempts[0].SessionID != response.SessionID || attempts[0].TurnID == "" {
		t.Fatalf("hosted attempt did not retain conversation identity: attempts=%+v err=%v", attempts, err)
	}
	first := attempts[0]
	events, err := st.Events().List(context.Background(), response.SessionRef)
	if err != nil || len(events) < 2 || events[0].EventType != "user.message" ||
		events[0].Role != "user" || events[0].Content != "first" {
		t.Fatalf("durable initial conversation events=%+v err=%v", events, err)
	}
	if err = server.projectHostedProviderEvent(context.Background(), first, "codex", "item.completed",
		"provider-session-1", 1, json.RawMessage(`{"item":{"type":"agent_message","text":"streamed"}}`)); err != nil {
		t.Fatal(err)
	}
	// Runtime Host delivery is at-least-once; source identity must suppress a
	// duplicate event after reconnect/replay.
	if err = server.projectHostedProviderEvent(context.Background(), first, "codex", "item.completed",
		"provider-session-1", 1, json.RawMessage(`{"item":{"type":"agent_message","text":"streamed"}}`)); err != nil {
		t.Fatal(err)
	}
	events, _ = st.Events().List(context.Background(), response.SessionRef)
	providerEvents := 0
	for _, event := range events {
		if event.EventType == "provider.item.completed" {
			providerEvents++
		}
	}
	if providerEvents != 1 {
		t.Fatalf("provider event replay was not deduplicated: %+v", events)
	}
	// Claim and complete the first turn exactly as a Runtime Host would.
	claimed, err := server.taskPlane.Claim(context.Background(), store.ExecutionClaim{Tenant: "t", Namespace: "n",
		RuntimePoolName: first.RuntimePoolName, HostID: host.ID, HostGeneration: host.LeaseGeneration,
		LeaseOwner: "host/1", LeaseToken: "lease-1", LeaseTTL: time.Minute})
	if err != nil {
		t.Fatal(err)
	}
	preparing, err := server.taskPlane.MarkPreparing(context.Background(), claimed.ID, claimed.LeaseToken, claimed.FencingToken)
	if err != nil {
		t.Fatal(err)
	}
	running, err := server.taskPlane.MarkRunning(context.Background(), preparing.ID, preparing.LeaseToken,
		preparing.FencingToken, "provider-session-1", "workspace")
	if err != nil {
		t.Fatal(err)
	}
	completed, err := server.taskPlane.Complete(context.Background(), running.ID, running.LeaseToken,
		running.FencingToken, json.RawMessage(`{"output":"answer one"}`), nil)
	if err != nil {
		t.Fatal(err)
	}
	if err = server.projectHostedAttemptTerminal(context.Background(), completed); err != nil {
		t.Fatal(err)
	}
	beforeLate, err := st.Orchestration().ListRunEvents(context.Background(), completed.RunID, 0, 1000)
	if err != nil {
		t.Fatal(err)
	}
	runtimeToken, _, err := server.runtimeTokens.Mint(host.HostKey, host.Tenant, host.Namespace, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	attemptToken, err := server.taskTokens.MintAttempt(completed.ID, completed.DispatchGeneration,
		string(completed.BackendKind), host.ID.String(), time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	latePayload, _ := json.Marshal(map[string]any{"leaseToken": completed.LeaseToken,
		"fencingToken": completed.FencingToken, "ordinal": 99, "provider": "codex",
		"eventType": "item.completed", "raw": json.RawMessage(`{"item":{"type":"agent_message","text":"late"}}`)})
	lateReq := httptest.NewRequest(http.MethodPost, "/api/v1/runtime-hosts/"+host.ID.String()+
		"/execution-attempts/"+completed.ID.String()+"/events", bytes.NewReader(latePayload))
	lateReq.Header.Set("Authorization", "Bearer "+runtimeToken)
	lateReq.Header.Set("X-Execution-Attempt-Token", attemptToken)
	lateReq.Header.Set("Content-Type", "application/json")
	lateOut := httptest.NewRecorder()
	server.router.ServeHTTP(lateOut, lateReq)
	var lateResponse struct {
		Accepted bool `json:"accepted"`
	}
	if lateOut.Code != http.StatusOK || json.Unmarshal(lateOut.Body.Bytes(), &lateResponse) != nil || lateResponse.Accepted {
		t.Fatalf("late provider event was not rejected cleanly: %d %s", lateOut.Code, lateOut.Body)
	}
	afterLate, err := st.Orchestration().ListRunEvents(context.Background(), completed.RunID, 0, 1000)
	if err != nil || len(afterLate) != len(beforeLate) {
		t.Fatalf("terminal Run timeline changed after late event: before=%d after=%d err=%v",
			len(beforeLate), len(afterLate), err)
	}

	continueBody := `{"tenant":"t","namespace":"n","agentId":"` + agent.ID.String() +
		`","message":"second"}`
	continueReq := httptest.NewRequest(http.MethodPost, "/api/v1/playground/sessions/"+response.SessionID+"/turns",
		bytes.NewBufferString(continueBody))
	continueReq.Header.Set("Authorization", "Bearer console")
	continueReq.Header.Set("Content-Type", "application/json")
	continueOut := httptest.NewRecorder()
	server.router.ServeHTTP(continueOut, continueReq)
	if continueOut.Code != http.StatusAccepted {
		t.Fatalf("continue hosted conversation: %d %s", continueOut.Code, continueOut.Body)
	}
	attempts, err = st.ExecutionAttempts().List(context.Background(), store.ExecutionAttemptFilter{
		Tenant: "t", Namespace: "n", AgentID: agent.ID, SessionID: response.SessionID,
		NewestFirst: true,
	})
	var resumedSnapshot controlmodel.RuntimeDispatchSnapshot
	var affinity struct {
		PreferredHostID uuid.UUID `json:"preferredHostId"`
		WorkspaceKey    string    `json:"workspaceKey"`
	}
	if len(attempts) > 0 {
		_ = json.Unmarshal(attempts[0].RuntimeBinding, &resumedSnapshot)
		_ = json.Unmarshal(resumedSnapshot.Policy, &affinity)
	}
	if err != nil || len(attempts) != 2 || attempts[0].ProviderSessionID != "provider-session-1" ||
		attempts[0].WorkspaceKey != "workspace" ||
		affinity.PreferredHostID != host.ID || affinity.WorkspaceKey != "workspace" {
		t.Fatalf("provider session was not resumed: attempts=%+v err=%v", attempts, err)
	}
	storedSession, err := st.Sessions().GetByID(context.Background(), response.SessionRef)
	if err != nil {
		t.Fatal(err)
	}
	checkpoint, err := server.latestHostedAttempt(context.Background(), storedSession)
	if err != nil || checkpoint == nil || checkpoint.ID != first.ID ||
		checkpoint.WorkspaceKey != "workspace" {
		t.Fatalf("new pending attempt replaced successful resume checkpoint: checkpoint=%+v err=%v",
			checkpoint, err)
	}
	otherHost, err := st.RuntimeRegistry().UpsertRuntimeHost(context.Background(), &controlmodel.RuntimeHost{
		Tenant: "t", Namespace: "n", HostKey: "other-host", PoolName: host.PoolName,
		State: controlmodel.RuntimeHostOnline, Capacity: 2, Labels: json.RawMessage(`{"region":"cn"}`),
		Capabilities: json.RawMessage(`{"providers":{"codex":"test"},"providerCapabilities":{"codex":{"resume":true}},"sandbox":{"network":false}}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	_, err = server.taskPlane.Claim(context.Background(), store.ExecutionClaim{Tenant: "t", Namespace: "n",
		RuntimePoolName: host.PoolName, HostID: otherHost.ID, HostGeneration: otherHost.LeaseGeneration,
		LeaseOwner: "other/1", LeaseToken: "wrong-host", LeaseTTL: time.Minute})
	if !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("another host claimed a provider-local conversation: %v", err)
	}
	events, _ = st.Events().List(context.Background(), response.SessionRef)
	var sawAssistant, sawSecond bool
	for _, event := range events {
		sawAssistant = sawAssistant || event.Role == "assistant" && event.Content == "answer one"
		sawSecond = sawSecond || event.Role == "user" && event.Content == "second"
	}
	if !sawAssistant || !sawSecond {
		t.Fatalf("conversation history was not preserved across turns: %+v", events)
	}
}

func TestDirectAgentJobCreatesOperationalWork(t *testing.T) {
	st, agent, _, _ := setupConversationAgent(t)
	server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	body := `{"tenant":"t","namespace":"n","targetType":"agent","targetRef":"` + agent.ID.String() + `","mode":"job","message":"do it"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/playground/invocations", bytes.NewBufferString(body))
	req.Header.Set("Authorization", "Bearer console")
	req.Header.Set("Content-Type", "application/json")
	out := httptest.NewRecorder()
	server.router.ServeHTTP(out, req)
	if out.Code != http.StatusAccepted {
		t.Fatalf("invoke: %d %s", out.Code, out.Body)
	}
	var result struct {
		IssueID uuid.UUID `json:"issueId"`
		RunID   uuid.UUID `json:"runId"`
	}
	if err := json.Unmarshal(out.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	issue, err := st.Collaboration().GetIssue(context.Background(), result.IssueID)
	if err != nil {
		t.Fatal(err)
	}
	if result.RunID == uuid.Nil || issue.Kind != controlmodel.IssueKindPlaygroundJob ||
		issue.Visibility != controlmodel.IssueVisibilityOperational || issue.AssigneeRef != agent.ID.String() ||
		issue.SourceType != "playground" {
		t.Fatalf("unexpected Playground work record: result=%+v issue=%+v", result, issue)
	}
}

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

package httpapi

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
	"github.com/spring-ai-alibaba/aistio/internal/prober"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestSessionEventWaitTimedOutRecognizesWrappedDeadline(t *testing.T) {
	if !sessionEventWaitTimedOut(fmt.Errorf("postgres wait: %w", context.DeadlineExceeded)) {
		t.Fatal("wrapped deadline should produce an SSE heartbeat instead of closing the stream")
	}
	if sessionEventWaitTimedOut(context.Canceled) {
		t.Fatal("client cancellation must close the stream")
	}
}

func TestGetSessionMessages_TranscriptHitSkipsCapability(t *testing.T) {
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	sess, err := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "s1", AgentName: "agent-a", Namespace: "default",
		Framework: "x", Phase: store.SessionPhaseIdle,
	})
	if err != nil {
		t.Fatal(err)
	}

	calledProber := false
	s := NewServer(ServerOptions{
		Store: st,
		Prober: &stubProber{fetchMessages: func() (*prober.MessagePage, error) {
			calledProber = true
			return nil, nil
		}},
		TranscriptMessages: func(ctx context.Context, tenant, agentName, namespace, sessionID string, offset, limit int, fromEnd bool) (*prober.MessagePage, bool, error) {
			return &prober.MessagePage{
				SessionID: sessionID,
				Offset:    offset,
				Limit:     limit,
				Total:     1,
				Messages:  []prober.MessageItem{{Seq: 1, Role: "user", Content: "hi"}},
				Source:    "transcript",
			}, true, nil
		},
	})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/sessions/"+sess.ID.String()+"/messages", nil)
	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
	}
	if calledProber {
		t.Fatal("live DP fetch should not run on transcript hit")
	}
	var page prober.MessagePage
	if err := json.Unmarshal(w.Body.Bytes(), &page); err != nil {
		t.Fatal(err)
	}
	if page.Total != 1 || len(page.Messages) != 1 || page.Messages[0].Content != "hi" {
		t.Fatalf("unexpected page: %+v", page)
	}
}

func TestGetSessionMessages_ProjectsDurableEventsBeforeLiveQuery(t *testing.T) {
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	sess, err := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "event-only", AgentName: "agent-events", Namespace: "default",
		Framework: "agentscope", Phase: store.SessionPhaseIdle,
	})
	if err != nil {
		t.Fatal(err)
	}
	for _, event := range []*store.SessionEvent{
		{SessionFK: sess.ID, Seq: 1, EventType: "session_start"},
		{SessionFK: sess.ID, Seq: 2, EventType: "message", Role: "user", Content: "hello"},
		{SessionFK: sess.ID, Seq: 3, EventType: "message", Role: "assistant", Content: "hi"},
	} {
		if err := st.Events().Append(context.Background(), event); err != nil {
			t.Fatal(err)
		}
	}

	calledProber := false
	s := NewServer(ServerOptions{
		Store: st,
		Prober: &stubProber{fetchMessages: func() (*prober.MessagePage, error) {
			calledProber = true
			return nil, nil
		}},
	})
	req := httptest.NewRequest(http.MethodGet, "/api/v1/sessions/"+sess.ID.String()+"/messages?fromEnd=true&limit=1", nil)
	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
	}
	if calledProber {
		t.Fatal("live message-query should not run when durable message events exist")
	}
	var page prober.MessagePage
	if err := json.Unmarshal(w.Body.Bytes(), &page); err != nil {
		t.Fatal(err)
	}
	if page.Source != "events" || page.Total != 2 || page.Offset != 1 || len(page.Messages) != 1 ||
		page.Messages[0].Role != "assistant" || page.Messages[0].Content != "hi" {
		t.Fatalf("unexpected event projection: %+v", page)
	}
}

func TestPostSessionUserMessage_ForwardsToHoldingInstance(t *testing.T) {
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	sess, err := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "byo-1", AgentName: "dsh", Namespace: "default",
		Framework: "deepseek-harness", Phase: store.SessionPhaseIdle,
		InstanceRef: "inst-1",
	})
	if err != nil {
		t.Fatal(err)
	}
	reg := dataplane.NewRegistry()
	reg.Upsert(dataplane.Entry{
		InstanceID: "inst-1",
		AgentName:  "dsh",
		Namespace:  "default",
		BaseURL:    "http://127.0.0.1:18091",
		Healthy:    true,
	})
	var gotEndpoint, gotSession, gotContent string
	s := NewServer(ServerOptions{
		Store:    st,
		Registry: reg,
		Prober: &stubProber{sendUserMessage: func(endpoint, sessionID, content string) error {
			gotEndpoint, gotSession, gotContent = endpoint, sessionID, content
			return nil
		}},
	})
	req := httptest.NewRequest(
		http.MethodPost,
		"/api/v1/sessions/"+sess.ID.String()+"/user-message",
		bytes.NewBufferString(`{"content":"hello from console"}`),
	)
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, req)
	if w.Code != http.StatusAccepted {
		t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
	}
	if gotEndpoint != "http://127.0.0.1:18091" || gotSession != "byo-1" || gotContent != "hello from console" {
		t.Fatalf("forward mismatch endpoint=%s session=%s content=%s", gotEndpoint, gotSession, gotContent)
	}
}

func TestGetSessionMessages_MissFallsBackWithCapabilityGate(t *testing.T) {
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	_, err = st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "s2", AgentName: "agent-b", Namespace: "default",
		Framework: "x", Phase: store.SessionPhaseIdle,
	})
	if err != nil {
		t.Fatal(err)
	}

	s := NewServer(ServerOptions{
		Store: st,
		// No registry / kube → resolveSessionAgent fails after transcript miss
		// unless we only test the miss path returning NotImplemented via missing agent.
		TranscriptMessages: func(ctx context.Context, tenant, agentName, namespace, sessionID string, offset, limit int, fromEnd bool) (*prober.MessagePage, bool, error) {
			return nil, false, nil
		},
	})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/sessions/s2/messages?agent=agent-b", nil)
	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, req)
	// Without kube/registry the fallback cannot resolve an agent → 503.
	if w.Code != http.StatusServiceUnavailable && w.Code != http.StatusNotImplemented {
		t.Fatalf("expected fallback gate error, got %d body=%s", w.Code, w.Body.String())
	}
}

func TestGetSessionEvents_BeforeSeqReversePaging(t *testing.T) {
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	sess, err := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "ev1", AgentName: "agent-e", Namespace: "default",
		Framework: "x", Phase: store.SessionPhaseIdle,
	})
	if err != nil {
		t.Fatal(err)
	}
	for i := 1; i <= 5; i++ {
		if err := st.Events().Append(context.Background(), &store.SessionEvent{
			SessionFK: sess.ID, Seq: i, EventType: "message", Content: "c",
		}); err != nil {
			t.Fatal(err)
		}
	}

	s := NewServer(ServerOptions{Store: st})
	req := httptest.NewRequest(http.MethodGet, "/api/v1/sessions/"+sess.ID.String()+"/events?before=4&limit=2", nil)
	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
	}
	var body struct {
		Events []*store.SessionEvent `json:"events"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Events) != 2 {
		t.Fatalf("want 2 events, got %d", len(body.Events))
	}
	if body.Events[0].Seq != 2 || body.Events[1].Seq != 3 {
		t.Fatalf("want seq 2,3 got %d,%d", body.Events[0].Seq, body.Events[1].Seq)
	}
}

func TestGetSessionEvents_AfterSeqForwardGapRepair(t *testing.T) {
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	sess, err := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "ev-forward", AgentName: "agent-e", Namespace: "default",
		Framework: "x", Phase: store.SessionPhaseIdle,
	})
	if err != nil {
		t.Fatal(err)
	}
	for i := 1; i <= 5; i++ {
		if err := st.Events().Append(context.Background(), &store.SessionEvent{
			SessionFK: sess.ID, Seq: i, EventType: "message", Content: "c",
		}); err != nil {
			t.Fatal(err)
		}
	}

	s := NewServer(ServerOptions{Store: st})
	req := httptest.NewRequest(http.MethodGet, "/api/v1/sessions/"+sess.ID.String()+"/events?after=2&limit=2", nil)
	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
	}
	var body struct {
		Events []*store.SessionEvent `json:"events"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Events) != 2 || body.Events[0].Seq != 3 || body.Events[1].Seq != 4 {
		t.Fatalf("want seq 3,4 got %+v", body.Events)
	}
}

func TestStreamSessionEvents_ResumesAfterExclusiveSequence(t *testing.T) {
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	sess, err := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "stream-1", AgentName: "agent-stream", Namespace: "default",
		Framework: "agentscope", Phase: store.SessionPhaseIdle,
	})
	if err != nil {
		t.Fatal(err)
	}
	for _, event := range []*store.SessionEvent{
		{SessionFK: sess.ID, Seq: 1, EventType: "message", Role: "user", Content: "old"},
		{SessionFK: sess.ID, Seq: 2, EventType: "message", Role: "assistant", Content: "new"},
	} {
		if err := st.Events().Append(context.Background(), event); err != nil {
			t.Fatal(err)
		}
	}

	s := NewServer(ServerOptions{Store: st})
	testServer := httptest.NewServer(s.router)
	defer testServer.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		testServer.URL+"/api/v1/sessions/"+sess.ID.String()+"/events/stream?after=1", nil)
	if err != nil {
		t.Fatal(err)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status=%d", resp.StatusCode)
	}
	if contentType := resp.Header.Get("Content-Type"); !strings.HasPrefix(contentType, "text/event-stream") {
		t.Fatalf("content-type=%q", contentType)
	}

	scanner := bufio.NewScanner(resp.Body)
	var eventID string
	var payload store.SessionEvent
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "id: ") {
			eventID = strings.TrimPrefix(line, "id: ")
		}
		if strings.HasPrefix(line, "data: ") {
			if err := json.Unmarshal([]byte(strings.TrimPrefix(line, "data: ")), &payload); err != nil {
				t.Fatal(err)
			}
			break
		}
	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}
	if eventID != "2" || payload.Seq != 2 || payload.Content != "new" {
		t.Fatalf("unexpected resumed event id=%q payload=%+v", eventID, payload)
	}
}

func TestStreamSessionEvents_OutlivesServerWriteTimeout(t *testing.T) {
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	sess, err := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "stream-deadline", AgentName: "agent-stream", Namespace: "default",
		Framework: "agentscope", Phase: store.SessionPhaseIdle,
	})
	if err != nil {
		t.Fatal(err)
	}

	s := NewServer(ServerOptions{Store: st})
	testServer := httptest.NewUnstartedServer(s.router)
	testServer.Config.WriteTimeout = 50 * time.Millisecond
	testServer.Start()
	defer testServer.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		testServer.URL+"/api/v1/sessions/"+sess.ID.String()+"/events/stream", nil)
	if err != nil {
		t.Fatal(err)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	// Publish only after the server-wide deadline would have expired. The SSE
	// handler must have cleared that deadline for this connection.
	time.Sleep(100 * time.Millisecond)
	if err := st.Events().Append(context.Background(), &store.SessionEvent{
		SessionFK: sess.ID, Seq: 1, EventType: "message", Role: "assistant", Content: "still-open",
	}); err != nil {
		t.Fatal(err)
	}
	scanner := bufio.NewScanner(resp.Body)
	for scanner.Scan() {
		line := scanner.Text()
		if !strings.HasPrefix(line, "data: ") {
			continue
		}
		var event store.SessionEvent
		if err := json.Unmarshal([]byte(strings.TrimPrefix(line, "data: ")), &event); err != nil {
			t.Fatal(err)
		}
		if event.Seq != 1 || event.Content != "still-open" {
			t.Fatalf("unexpected event after write timeout: %+v", event)
		}
		return
	}
	if err := scanner.Err(); err != nil {
		t.Fatalf("stream closed at the server write timeout: %v", err)
	}
	t.Fatal("stream closed before the event published after the server write timeout")
}

func TestFilesystemTranscriptMessages(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "default", "agent-a", "sess-1", "events")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "1-2-w1.jsonl")
	content := `{"type":"message","role":"user","content":"hello","timestamp":"2026-01-01T00:00:00Z"}
{"type":"tool_use","name":"bash","input":{"cmd":"ls"},"timestamp":"2026-01-01T00:00:01Z"}
`
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	fn := FilesystemTranscriptMessages(root)
	page, ok, err := fn(context.Background(), "default", "agent-a", "default", "sess-1", 0, 10, false)
	if err != nil || !ok {
		t.Fatalf("ok=%v err=%v", ok, err)
	}
	if page.Total != 2 || len(page.Messages) != 2 {
		t.Fatalf("page=%+v", page)
	}
	if page.Messages[0].Role != "user" || page.Messages[1].ToolName != "bash" {
		t.Fatalf("messages=%+v", page.Messages)
	}
	if page.Source != "transcript" || page.Messages[1].Content == "" {
		t.Fatalf("source/content not set: %+v", page.Messages[1])
	}

	// fromEnd with limit=1 should return only the newest entry.
	tail, ok, err := fn(context.Background(), "default", "agent-a", "default", "sess-1", 0, 1, true)
	if err != nil || !ok {
		t.Fatalf("tail ok=%v err=%v", ok, err)
	}
	if tail.Offset != 1 || len(tail.Messages) != 1 || tail.Messages[0].ToolName != "bash" {
		t.Fatalf("fromEnd page=%+v", tail)
	}
}

func TestTranscriptIndexUpsertFromSnapshot(t *testing.T) {
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	sess, err := st.Sessions().Upsert(context.Background(), &store.Session{
		SessionID: "idx1", AgentName: "a", Namespace: "ns", Framework: "x", Phase: store.SessionPhaseActive,
	})
	if err != nil {
		t.Fatal(err)
	}
	fk := sess.ID
	if err := store.UpsertTranscriptIndexFromSnapshot(context.Background(), st, fk, 42, 100, 20); err != nil {
		t.Fatal(err)
	}
	idx, err := st.TranscriptIndex().Get(context.Background(), fk)
	if err != nil {
		t.Fatal(err)
	}
	if idx.EntryCount != 42 || idx.PromptTokens != 100 || idx.CompletionTokens != 20 {
		t.Fatalf("idx=%+v", idx)
	}
}

type stubProber struct {
	fetchMessages   func() (*prober.MessagePage, error)
	sendUserMessage func(endpoint, sessionID, content string) error
}

func (s *stubProber) ProbeInfo(context.Context, string) (*prober.DataPlaneInfo, error) {
	return nil, nil
}
func (s *stubProber) ProbeHealth(context.Context, string) (bool, error) { return true, nil }
func (s *stubProber) ProbeSessions(context.Context, string) ([]prober.SessionSnapshot, error) {
	return nil, nil
}
func (s *stubProber) SendCompress(context.Context, string, string) error  { return nil }
func (s *stubProber) SendTerminate(context.Context, string, string) error { return nil }
func (s *stubProber) FetchSessionState(context.Context, string, string) (*prober.SessionState, error) {
	return nil, nil
}
func (s *stubProber) FetchContext(context.Context, string, string) (*prober.ContextSnapshot, error) {
	return nil, nil
}
func (s *stubProber) FetchMessages(context.Context, string, string, int, int) (*prober.MessagePage, error) {
	if s.fetchMessages != nil {
		return s.fetchMessages()
	}
	return nil, nil
}
func (s *stubProber) FetchSubagents(context.Context, string) ([]prober.SubagentInfo, error) {
	return nil, nil
}
func (s *stubProber) FetchWorkspaces(context.Context, string) ([]prober.WorkspaceInfo, error) {
	return nil, nil
}
func (s *stubProber) FetchTasks(context.Context, string, string) ([]prober.TaskInfo, error) {
	return nil, nil
}
func (s *stubProber) FetchSubagentTasks(context.Context, string, string) ([]prober.SubagentTaskInfo, error) {
	return nil, nil
}
func (s *stubProber) CancelSubagentTask(context.Context, string, string, string) error { return nil }
func (s *stubProber) SendPlanMode(context.Context, string, string, bool) error         { return nil }
func (s *stubProber) SendUserMessage(_ context.Context, endpoint, sessionID, content string) error {
	if s.sendUserMessage != nil {
		return s.sendUserMessage(endpoint, sessionID, content)
	}
	return nil
}

package httpapi

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

func TestBuildAgentFromPush_Declarative(t *testing.T) {
	s := &Server{}
	req := &PushAgentRequest{
		DisplayName:  "Chatbot",
		SystemPrompt: "be nice",
		Runtime:      "agentscope-java",
		Deployment:   &DeploymentSpec{Replicas: 4},
		Tools: &ToolsSpec{Tools: []ToolEntry{
			{Name: "search", MCPServerName: "tavily", MCPServerURL: "http://tavily"},
		}},
	}
	agent := s.buildAgentFromPush("chatbot", "default", req)

	if agent.Spec.Type != v1alpha1.AgentTypeDeclarative {
		t.Fatalf("type = %q, want Declarative", agent.Spec.Type)
	}
	if agent.Spec.Declarative == nil {
		t.Fatal("declarative spec missing")
	}
	if agent.Spec.Declarative.AgentConfig.ModelConfigRef != "chatbot-model" {
		t.Errorf("modelConfigRef = %q", agent.Spec.Declarative.AgentConfig.ModelConfigRef)
	}
	if agent.Spec.Declarative.Replicas == nil || *agent.Spec.Declarative.Replicas != 4 {
		t.Errorf("replicas not propagated: %+v", agent.Spec.Declarative.Replicas)
	}
	if len(agent.Spec.Declarative.Tools) != 1 {
		t.Errorf("tools = %v", agent.Spec.Declarative.Tools)
	}
}

func TestBuildAgentFromPush_BYOImage(t *testing.T) {
	s := &Server{}
	req := &PushAgentRequest{
		Image:   "example.com/agent:1",
		Command: []string{"run"},
	}
	agent := s.buildAgentFromPush("byo", "default", req)

	if agent.Spec.Type != v1alpha1.AgentTypeBYO {
		t.Fatalf("type = %q, want BYO", agent.Spec.Type)
	}
	if agent.Spec.BYO == nil || agent.Spec.BYO.Image != "example.com/agent:1" {
		t.Errorf("byo image not set: %+v", agent.Spec.BYO)
	}
	if agent.Spec.Declarative != nil {
		t.Error("declarative must be nil for BYO push")
	}
}

func TestBuildModelConfigAndSecret(t *testing.T) {
	s := &Server{}
	owner := agentOwnerRef(&v1alpha1.Agent{
		ObjectMeta: metav1.ObjectMeta{Name: "chatbot", Namespace: "default", UID: "uid-123"},
	})

	secret := buildModelSecret("chatbot-model-key", "default", "sk-abc", owner)
	if string(secret.Data["api-key"]) != "sk-abc" {
		t.Errorf("secret api-key = %q", secret.Data["api-key"])
	}
	if len(secret.OwnerReferences) != 1 || secret.OwnerReferences[0].UID != "uid-123" {
		t.Errorf("secret ownerRef wrong: %+v", secret.OwnerReferences)
	}

	mc := s.buildModelConfigFromPush("chatbot", "default", &ModelSpec{
		Provider: "DashScope",
		ModelID:  "qwen-max",
	}, "chatbot-model-key", owner)
	if mc.Spec.APIKeySecret != "chatbot-model-key" {
		t.Errorf("APIKeySecret = %q", mc.Spec.APIKeySecret)
	}
	if mc.Spec.APIKeySecretKey != "api-key" {
		t.Errorf("APIKeySecretKey = %q", mc.Spec.APIKeySecretKey)
	}
	if len(mc.OwnerReferences) != 1 || mc.OwnerReferences[0].Kind != "Agent" {
		t.Errorf("modelconfig ownerRef wrong: %+v", mc.OwnerReferences)
	}
}

func TestAgentOwnerRef(t *testing.T) {
	ref := agentOwnerRef(&v1alpha1.Agent{
		ObjectMeta: metav1.ObjectMeta{Name: "a", UID: "u1"},
	})
	if ref.Controller == nil || !*ref.Controller {
		t.Error("expected controller=true")
	}
	if ref.Kind != "Agent" || ref.Name != "a" || ref.UID != "u1" {
		t.Errorf("ownerRef = %+v", ref)
	}
}

func TestRevisionHistory(t *testing.T) {
	meta := &metav1.ObjectMeta{}
	if got := readRevisions(meta); len(got) != 0 {
		t.Errorf("expected empty history, got %v", got)
	}

	spec := &v1alpha1.AgentSpec{Runtime: "test"}
	appendRevision(meta, "rev1", "push", spec)
	appendRevision(meta, "rev2", "push", spec)
	entries := readRevisions(meta)
	if len(entries) != 2 {
		t.Fatalf("history len = %d, want 2", len(entries))
	}
	if entries[0].Revision != "rev1" || entries[1].Revision != "rev2" {
		t.Errorf("unexpected order: %+v", entries)
	}
	if len(entries[1].SpecSnapshot) == 0 {
		t.Error("expected spec snapshot on latest entry")
	}
}

func TestRevisionHistory_Bounded(t *testing.T) {
	meta := &metav1.ObjectMeta{}
	for i := 0; i < maxRevisionHistory+5; i++ {
		appendRevision(meta, "r", "push", nil)
	}
	if got := len(readRevisions(meta)); got != maxRevisionHistory {
		t.Errorf("history len = %d, want %d", got, maxRevisionHistory)
	}
}

// newTestServer builds a Server backed by a fake controller-runtime client.
func newTestServer(t *testing.T) *Server {
	t.Helper()
	scheme := runtime.NewScheme()
	if err := v1alpha1.AddToScheme(scheme); err != nil {
		t.Fatalf("add scheme: %v", err)
	}
	cl := fake.NewClientBuilder().
		WithScheme(scheme).
		WithStatusSubresource(&v1alpha1.Agent{}).
		Build()
	gin.SetMode(gin.TestMode)
	return &Server{client: cl}
}

func pushForTest(t *testing.T, s *Server, name string, body PushAgentRequest) PushAgentResponse {
	t.Helper()
	raw, _ := json.Marshal(body)
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest(http.MethodPost, "/api/v1/agents/"+name+"/push", bytes.NewReader(raw))
	c.Request.Header.Set("Content-Type", "application/json")
	c.Params = gin.Params{{Key: "name", Value: name}}
	s.pushAgent(c)
	if w.Code >= 400 {
		t.Fatalf("push %q failed: %d %s", name, w.Code, w.Body.String())
	}
	var resp PushAgentResponse
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode push response: %v", err)
	}
	return resp
}

// TestRollbackRestoresSpec verifies the rollback feature is self-consistent:
// the revision returned by a push snapshots the spec that push applied, and
// rolling back to an earlier revision restores that exact spec.
func TestRollbackRestoresSpec(t *testing.T) {
	s := newTestServer(t)
	const name = "chatbot"

	rev1 := pushForTest(t, s, name, PushAgentRequest{
		SystemPrompt: "version-one",
		Model:        &ModelSpec{Provider: "DashScope", ModelID: "qwen-max"},
	})
	rev2 := pushForTest(t, s, name, PushAgentRequest{
		SystemPrompt: "version-two",
		Model:        &ModelSpec{Provider: "DashScope", ModelID: "qwen-max"},
	})
	if rev1.Revision == rev2.Revision {
		t.Fatalf("expected distinct revisions, both = %q", rev1.Revision)
	}

	get := func() *v1alpha1.Agent {
		var a v1alpha1.Agent
		if err := s.client.Get(t.Context(), types.NamespacedName{Name: name, Namespace: defaultNamespace}, &a); err != nil {
			t.Fatalf("get agent: %v", err)
		}
		return &a
	}

	// After the second push the live spec is version-two, and the latest
	// revision (rev2) must snapshot that same spec (the bug stored the prior spec).
	if got := get().Spec.Declarative.AgentConfig.SystemMessage; got != "version-two" {
		t.Fatalf("live systemMessage = %q, want version-two", got)
	}
	for _, e := range readRevisions(&get().ObjectMeta) {
		if e.Revision == rev2.Revision {
			var snap v1alpha1.AgentSpec
			if err := json.Unmarshal(e.SpecSnapshot, &snap); err != nil {
				t.Fatalf("decode rev2 snapshot: %v", err)
			}
			if snap.Declarative.AgentConfig.SystemMessage != "version-two" {
				t.Fatalf("rev2 snapshot systemMessage = %q, want version-two",
					snap.Declarative.AgentConfig.SystemMessage)
			}
		}
	}

	// Roll back to rev1 and confirm the live spec becomes version-one again.
	raw, _ := json.Marshal(RollbackRequest{Revision: rev1.Revision})
	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest(http.MethodPost, "/api/v1/agents/"+name+"/rollback", bytes.NewReader(raw))
	c.Request.Header.Set("Content-Type", "application/json")
	c.Params = gin.Params{{Key: "name", Value: name}}
	s.rollbackAgent(c)
	if w.Code != http.StatusOK {
		t.Fatalf("rollback failed: %d %s", w.Code, w.Body.String())
	}

	if got := get().Spec.Declarative.AgentConfig.SystemMessage; got != "version-one" {
		t.Fatalf("after rollback systemMessage = %q, want version-one", got)
	}
}

func TestBuildMCPServerFromPush_HeadersAndProtocol(t *testing.T) {
	s := &Server{}
	mcp := s.buildMCPServerFromPush("default", &ToolEntry{
		Name:          "search",
		MCPServerName: "tavily",
		MCPServerURL:  "https://tavily.example",
		Protocol:      "SSE",
		Timeout:       "10s",
		HeadersFrom: []HeaderFromEntry{
			{Name: "tavily-key", Key: "token", Header: "Authorization"},
		},
	})
	if mcp.Spec.Remote.Protocol != "SSE" || mcp.Spec.Remote.Timeout != "10s" {
		t.Errorf("protocol/timeout not propagated: %+v", mcp.Spec.Remote)
	}
	if len(mcp.Spec.Remote.HeadersFrom) != 1 {
		t.Fatalf("headersFrom = %+v", mcp.Spec.Remote.HeadersFrom)
	}
	h := mcp.Spec.Remote.HeadersFrom[0]
	if h.Kind != "Secret" || h.Name != "tavily-key" || h.Header != "Authorization" {
		t.Errorf("headerFrom mapping wrong: %+v", h)
	}
}

func TestRevisionHistory_SnapshotPruning(t *testing.T) {
	meta := &metav1.ObjectMeta{}
	spec := &v1alpha1.AgentSpec{Runtime: "test"}
	for i := 0; i < maxSnapshotRevisions+3; i++ {
		appendRevision(meta, fmt.Sprintf("r%d", i), "push", spec)
	}
	entries := readRevisions(meta)
	// Older entries beyond the snapshot window should have nil snapshots.
	for i := 0; i < 3; i++ {
		if len(entries[i].SpecSnapshot) != 0 {
			t.Errorf("entry %d should have pruned snapshot", i)
		}
	}
	// The last maxSnapshotRevisions entries should retain snapshots.
	for i := 3; i < len(entries); i++ {
		if len(entries[i].SpecSnapshot) == 0 {
			t.Errorf("entry %d should have snapshot", i)
		}
	}
}

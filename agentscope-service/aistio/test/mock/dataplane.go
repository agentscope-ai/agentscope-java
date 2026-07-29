package mock

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"

	"github.com/spring-ai-alibaba/aistio/internal/prober"
)

// MockDataPlane is a test HTTP server implementing the data plane contract API.
type MockDataPlane struct {
	Server         *httptest.Server
	ContractLevel  int32
	Capabilities   []string
	Sessions       []prober.SessionSnapshot
	SessionStates  map[string]*prober.SessionState
	Contexts       map[string]*prober.ContextSnapshot
	MessagePages   map[string]*prober.MessagePage
	Subagents      []prober.SubagentInfo
	Workspaces     []prober.WorkspaceInfo
	CompressCalls  []string
	TerminateCalls []string

	mu sync.Mutex
}

// NewMockDataPlane creates a new mock data plane server with the given contract level.
func NewMockDataPlane(contractLevel int32) *MockDataPlane {
	m := &MockDataPlane{
		ContractLevel: contractLevel,
		SessionStates: make(map[string]*prober.SessionState),
		Contexts:      make(map[string]*prober.ContextSnapshot),
		MessagePages:  make(map[string]*prober.MessagePage),
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/agentscope/info", m.handleInfo)
	mux.HandleFunc("/agentscope/health", m.handleHealth)
	mux.HandleFunc("/agentscope/sessions", m.handleSessions)
	mux.HandleFunc("/agentscope/sessions/", m.handleSessionAction)
	mux.HandleFunc("/agentscope/subagents", m.handleSubagents)
	mux.HandleFunc("/agentscope/workspaces", m.handleWorkspaces)
	m.Server = httptest.NewServer(mux)
	return m
}

// AddSession adds a session to the mock's session list.
func (m *MockDataPlane) AddSession(snap prober.SessionSnapshot) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.Sessions = append(m.Sessions, snap)
}

// SetSessionState sets the state for a specific session ID.
func (m *MockDataPlane) SetSessionState(sessionID string, state *prober.SessionState) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.SessionStates[sessionID] = state
}

// SetContext sets the Level-4 context snapshot for a specific session ID.
func (m *MockDataPlane) SetContext(sessionID string, ctx *prober.ContextSnapshot) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.Contexts[sessionID] = ctx
}

// SetMessages sets the Level-3 message page for a specific session ID.
func (m *MockDataPlane) SetMessages(sessionID string, page *prober.MessagePage) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.MessagePages[sessionID] = page
}

// CompressCalledFor returns true if compress was called for the given session ID.
func (m *MockDataPlane) CompressCalledFor(sessionID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, id := range m.CompressCalls {
		if id == sessionID {
			return true
		}
	}
	return false
}

// TerminateCalledFor returns true if terminate was called for the given session ID.
func (m *MockDataPlane) TerminateCalledFor(sessionID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, id := range m.TerminateCalls {
		if id == sessionID {
			return true
		}
	}
	return false
}

// Endpoint returns the server URL suitable for passing to prober methods.
func (m *MockDataPlane) Endpoint() string {
	return m.Server.URL
}

// Close shuts down the test server.
func (m *MockDataPlane) Close() {
	m.Server.Close()
}

func (m *MockDataPlane) handleInfo(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	m.mu.Lock()
	info := prober.DataPlaneInfo{
		Name:          "mock-agent",
		Runtime:       "mock",
		ContractLevel: m.ContractLevel,
		Capabilities:  m.Capabilities,
		Port:          8080,
	}
	m.mu.Unlock()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(info)
}

func (m *MockDataPlane) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.WriteHeader(http.StatusOK)
}

func (m *MockDataPlane) handleSessions(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	m.mu.Lock()
	sessions := m.Sessions
	m.mu.Unlock()

	if sessions == nil {
		sessions = []prober.SessionSnapshot{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"sessions": sessions,
	})
}

func (m *MockDataPlane) handleSessionAction(w http.ResponseWriter, r *http.Request) {
	// Parse: /agentscope/sessions/{id}/state or /agentscope/sessions/{id}/compress or /agentscope/sessions/{id}/terminate
	path := strings.TrimPrefix(r.URL.Path, "/agentscope/sessions/")
	parts := strings.SplitN(path, "/", 2)
	if len(parts) < 2 {
		http.Error(w, "invalid path", http.StatusBadRequest)
		return
	}

	sessionID := parts[0]
	action := parts[1]

	switch action {
	case "state":
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		m.mu.Lock()
		state, ok := m.SessionStates[sessionID]
		m.mu.Unlock()
		if !ok {
			http.Error(w, "session not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(state)

	case "context":
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		m.mu.Lock()
		ctx, ok := m.Contexts[sessionID]
		m.mu.Unlock()
		if !ok {
			http.Error(w, "session not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(ctx)

	case "messages":
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		m.mu.Lock()
		page, ok := m.MessagePages[sessionID]
		m.mu.Unlock()
		if !ok {
			http.Error(w, "session not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(page)

	case "compress":
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		m.mu.Lock()
		m.CompressCalls = append(m.CompressCalls, sessionID)
		m.mu.Unlock()
		w.WriteHeader(http.StatusOK)

	case "terminate":
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		m.mu.Lock()
		m.TerminateCalls = append(m.TerminateCalls, sessionID)
		m.mu.Unlock()
		w.WriteHeader(http.StatusOK)

	default:
		http.Error(w, "unknown action", http.StatusNotFound)
	}
}

func (m *MockDataPlane) handleSubagents(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	m.mu.Lock()
	subs := m.Subagents
	m.mu.Unlock()
	if subs == nil {
		subs = []prober.SubagentInfo{}
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{"subagents": subs})
}

func (m *MockDataPlane) handleWorkspaces(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	m.mu.Lock()
	workspaces := m.Workspaces
	m.mu.Unlock()
	if workspaces == nil {
		workspaces = []prober.WorkspaceInfo{}
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{"workspaces": workspaces})
}

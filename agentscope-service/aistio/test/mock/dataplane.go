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

package mock

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
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
	Tasks          map[string][]prober.TaskInfo
	Subagents      []prober.SubagentInfo
	Workspaces     []prober.WorkspaceInfo
	CompressCalls  []string
	TerminateCalls []string
	AbortCalls     []string

	// Fault injection.
	fault501       map[string]bool // capability name -> return 501 on related endpoints
	fault409Compress bool
	stale          bool // health returns 503 (simulates no heartbeat)

	mu sync.Mutex
}

// NewMockDataPlane creates a new mock data plane server with the given contract level.
func NewMockDataPlane(contractLevel int32) *MockDataPlane {
	m := &MockDataPlane{
		ContractLevel: contractLevel,
		SessionStates: make(map[string]*prober.SessionState),
		Contexts:      make(map[string]*prober.ContextSnapshot),
		MessagePages:  make(map[string]*prober.MessagePage),
		Tasks:         make(map[string][]prober.TaskInfo),
		fault501:      make(map[string]bool),
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

// SetContractLevel updates the advertised contract level.
func (m *MockDataPlane) SetContractLevel(level int32) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.ContractLevel = level
}

// SetCapabilities sets the advertised capability list returned by /agentscope/info.
func (m *MockDataPlane) SetCapabilities(caps []string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.Capabilities = append([]string(nil), caps...)
}

// InjectFault501 makes endpoints for the given capability return 501 Not Implemented.
func (m *MockDataPlane) InjectFault501(capability string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.fault501[capability] = true
}

// InjectFault409Compress makes POST .../compress return 409 Conflict with wait_idle.
func (m *MockDataPlane) InjectFault409Compress() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.fault409Compress = true
}

// MarkStale makes /agentscope/health return 503 (simulates missed heartbeats).
func (m *MockDataPlane) MarkStale() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.stale = true
}

// ClearStale restores healthy health responses.
func (m *MockDataPlane) ClearStale() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.stale = false
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

// SetTasks sets the task list for GET .../tasks on a session.
func (m *MockDataPlane) SetTasks(sessionID string, tasks []prober.TaskInfo) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.Tasks[sessionID] = tasks
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

// AbortCalledFor returns true if abort was called for the given session ID.
func (m *MockDataPlane) AbortCalledFor(sessionID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, id := range m.AbortCalls {
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

func (m *MockDataPlane) hasCapability(want string) bool {
	for _, c := range m.Capabilities {
		if c == want {
			return true
		}
	}
	return false
}

func (m *MockDataPlane) writeUnsupported(w http.ResponseWriter, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusNotImplemented)
	_ = json.NewEncoder(w).Encode(map[string]string{
		"error": msg,
		"code":  "unsupported",
	})
}

func (m *MockDataPlane) writeBusy(w http.ResponseWriter, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusConflict)
	_ = json.NewEncoder(w).Encode(map[string]string{
		"error": msg,
		"code":  "busy",
		"hint":  "wait_idle",
	})
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
		Capabilities:  append([]string(nil), m.Capabilities...),
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
	m.mu.Lock()
	stale := m.stale
	m.mu.Unlock()
	if stale {
		http.Error(w, "stale", http.StatusServiceUnavailable)
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

func capabilityForAction(action string) string {
	switch action {
	case "compress", "terminate":
		return v1alpha1.CapabilitySessionCommand
	case "abort":
		return v1alpha1.CapabilitySessionAbort
	case "context":
		return v1alpha1.CapabilityContextQuery
	case "messages":
		return v1alpha1.CapabilityMessageQuery
	case "tasks":
		return v1alpha1.CapabilityTaskQuery
	case "undo":
		return v1alpha1.CapabilitySessionUndo
	case "redo":
		return v1alpha1.CapabilitySessionRedo
	default:
		return ""
	}
}

func (m *MockDataPlane) handleSessionAction(w http.ResponseWriter, r *http.Request) {
	// Parse: /agentscope/sessions/{id}/{action}
	path := strings.TrimPrefix(r.URL.Path, "/agentscope/sessions/")
	parts := strings.SplitN(path, "/", 2)
	if len(parts) < 2 {
		http.Error(w, "invalid path", http.StatusBadRequest)
		return
	}

	sessionID := parts[0]
	action := parts[1]

	// Capability-gated endpoints: undeclared or fault-injected → 501.
	if capName := capabilityForAction(action); capName != "" {
		m.mu.Lock()
		fault := m.fault501[capName]
		declared := m.hasCapability(capName)
		// state is Level-2 baseline; always available when sessions exist.
		skipGate := action == "state"
		m.mu.Unlock()
		if !skipGate && (fault || !declared) {
			m.writeUnsupported(w, "capability not available: "+capName)
			return
		}
	}

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

	case "tasks":
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		m.mu.Lock()
		tasks, ok := m.Tasks[sessionID]
		m.mu.Unlock()
		if !ok {
			tasks = []prober.TaskInfo{}
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{"tasks": tasks})

	case "compress":
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		m.mu.Lock()
		busy := m.fault409Compress
		m.mu.Unlock()
		if busy {
			m.writeBusy(w, "session busy on data plane")
			return
		}
		m.mu.Lock()
		m.CompressCalls = append(m.CompressCalls, sessionID)
		m.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"accepted":  true,
			"commandId": "cmd-mock-compress",
			"phase":     "compressing",
			"result":    map[string]any{},
		})

	case "terminate":
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		m.mu.Lock()
		m.TerminateCalls = append(m.TerminateCalls, sessionID)
		m.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"accepted":  true,
			"commandId": "cmd-mock-terminate",
			"phase":     "terminated",
			"result":    map[string]any{},
		})

	case "abort":
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		m.mu.Lock()
		m.AbortCalls = append(m.AbortCalls, sessionID)
		m.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"accepted":  true,
			"commandId": "cmd-mock-abort",
			"phase":     "idle",
			"result":    map[string]any{},
		})

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
	fault := m.fault501[v1alpha1.CapabilitySubagentInventory]
	declared := m.hasCapability(v1alpha1.CapabilitySubagentInventory)
	subs := m.Subagents
	m.mu.Unlock()
	if fault || !declared {
		m.writeUnsupported(w, "capability not available: "+v1alpha1.CapabilitySubagentInventory)
		return
	}
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
	fault := m.fault501[v1alpha1.CapabilityWorkspaceInventory]
	declared := m.hasCapability(v1alpha1.CapabilityWorkspaceInventory)
	workspaces := m.Workspaces
	m.mu.Unlock()
	if fault || !declared {
		m.writeUnsupported(w, "capability not available: "+v1alpha1.CapabilityWorkspaceInventory)
		return
	}
	if workspaces == nil {
		workspaces = []prober.WorkspaceInfo{}
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{"workspaces": workspaces})
}

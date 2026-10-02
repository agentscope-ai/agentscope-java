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

package connector

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"time"

	"github.com/spring-ai-alibaba/aistio/internal/prober"
)

// ErrNotFound is returned by ContractProvider methods when the requested
// session/resource does not exist on this data plane.
var ErrNotFound = errors.New("connector: not found")

// ContractProvider supplies the payloads of the embedded data-plane HTTP
// contract server (docs/zh/controlplane/contract.md). The hosting agent
// implements it against its in-process state. Methods may return
// ErrNotFound (mapped to 404) or nil where a resource is legitimately empty.
type ContractProvider interface {
	// Info returns the Level-1 agent metadata (GET /agentscope/info).
	Info() *prober.DataPlaneInfo
	// Sessions returns the Level-2 session summaries (GET /agentscope/sessions).
	Sessions() ([]prober.SessionSnapshot, error)
	// SessionState returns the Level-3 session state (GET /agentscope/sessions/{id}/state).
	SessionState(sessionID string) (*prober.SessionState, error)
	// Context returns the Level-4 effective context (GET /agentscope/sessions/{id}/context).
	Context(sessionID string) (*prober.ContextSnapshot, error)
	// Messages returns a page of the Level-3 full history (GET /agentscope/sessions/{id}/messages).
	Messages(sessionID string, offset, limit int) (*prober.MessagePage, error)
	// Subagents returns the subagent inventory (GET /agentscope/subagents).
	Subagents() ([]prober.SubagentInfo, error)
	// Workspaces returns the workspace inventory (GET /agentscope/workspaces).
	Workspaces() ([]prober.WorkspaceInfo, error)
	// Compress handles POST /agentscope/sessions/{id}/compress.
	Compress(sessionID string) error
	// Terminate handles POST /agentscope/sessions/{id}/terminate.
	Terminate(sessionID string) error
}

// ContractServer is an embedded HTTP server exposing the aistio data-plane
// contract so the control plane can poll and probe this instance.
type ContractServer struct {
	provider ContractProvider
	server   *http.Server
	ln       net.Listener
}

// NewContractServer creates a contract server bound to addr (e.g. ":8080").
func NewContractServer(addr string, provider ContractProvider) *ContractServer {
	s := &ContractServer{provider: provider}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /agentscope/info", s.handleInfo)
	mux.HandleFunc("GET /agentscope/health", s.handleHealth)
	mux.HandleFunc("GET /agentscope/sessions", s.handleSessions)
	mux.HandleFunc("GET /agentscope/sessions/{id}/state", s.handleSessionState)
	mux.HandleFunc("GET /agentscope/sessions/{id}/context", s.handleSessionContext)
	mux.HandleFunc("GET /agentscope/sessions/{id}/messages", s.handleSessionMessages)
	mux.HandleFunc("GET /agentscope/subagents", s.handleSubagents)
	mux.HandleFunc("GET /agentscope/workspaces", s.handleWorkspaces)
	mux.HandleFunc("POST /agentscope/sessions/{id}/compress", s.handleCompress)
	mux.HandleFunc("POST /agentscope/sessions/{id}/terminate", s.handleTerminate)
	s.server = &http.Server{
		Addr:              addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	return s
}

// Start binds the listener and serves in a background goroutine. It returns
// once the socket is bound (or fails), so startup errors surface immediately.
func (s *ContractServer) Start() error {
	ln, err := net.Listen("tcp", s.server.Addr)
	if err != nil {
		return fmt.Errorf("contract server listen: %w", err)
	}
	s.ln = ln
	go func() {
		_ = s.server.Serve(ln)
	}()
	return nil
}

// Addr returns the bound address (useful when configured with ":0").
func (s *ContractServer) Addr() string {
	if s.ln == nil {
		return s.server.Addr
	}
	return s.ln.Addr().String()
}

// Stop gracefully shuts the server down.
func (s *ContractServer) Stop(ctx context.Context) error {
	return s.server.Shutdown(ctx)
}

func (s *ContractServer) handleInfo(w http.ResponseWriter, r *http.Request) {
	info := s.provider.Info()
	if info == nil {
		writeError(w, http.StatusInternalServerError, "no info available")
		return
	}
	writeJSON(w, http.StatusOK, info)
}

func (s *ContractServer) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *ContractServer) handleSessions(w http.ResponseWriter, r *http.Request) {
	sessions, err := s.provider.Sessions()
	if err != nil {
		writeProviderError(w, err)
		return
	}
	if sessions == nil {
		sessions = []prober.SessionSnapshot{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"sessions": sessions})
}

func (s *ContractServer) handleSessionState(w http.ResponseWriter, r *http.Request) {
	state, err := s.provider.SessionState(r.PathValue("id"))
	if err != nil {
		writeProviderError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, state)
}

func (s *ContractServer) handleSessionContext(w http.ResponseWriter, r *http.Request) {
	ctx, err := s.provider.Context(r.PathValue("id"))
	if err != nil {
		writeProviderError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, ctx)
}

func (s *ContractServer) handleSessionMessages(w http.ResponseWriter, r *http.Request) {
	offset := parseQueryInt(r, "offset", 0)
	limit := parseQueryInt(r, "limit", 100)
	page, err := s.provider.Messages(r.PathValue("id"), offset, limit)
	if err != nil {
		writeProviderError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, page)
}

func (s *ContractServer) handleSubagents(w http.ResponseWriter, r *http.Request) {
	subs, err := s.provider.Subagents()
	if err != nil {
		writeProviderError(w, err)
		return
	}
	if subs == nil {
		subs = []prober.SubagentInfo{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"subagents": subs})
}

func (s *ContractServer) handleWorkspaces(w http.ResponseWriter, r *http.Request) {
	workspaces, err := s.provider.Workspaces()
	if err != nil {
		writeProviderError(w, err)
		return
	}
	if workspaces == nil {
		workspaces = []prober.WorkspaceInfo{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"workspaces": workspaces})
}

func (s *ContractServer) handleCompress(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("id")
	if err := s.provider.Compress(sessionID); err != nil {
		writeProviderError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"sessionId": sessionID, "command": "compress", "status": "initiated"})
}

func (s *ContractServer) handleTerminate(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("id")
	if err := s.provider.Terminate(sessionID); err != nil {
		writeProviderError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"sessionId": sessionID, "command": "terminate", "status": "initiated"})
}

// writeProviderError maps provider errors to HTTP status codes.
func writeProviderError(w http.ResponseWriter, err error) {
	if errors.Is(err, ErrNotFound) {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}
	writeError(w, http.StatusInternalServerError, err.Error())
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func parseQueryInt(r *http.Request, key string, def int) int {
	raw := r.URL.Query().Get(key)
	if raw == "" {
		return def
	}
	if n, err := strconv.Atoi(raw); err == nil && n >= 0 {
		return n
	}
	return def
}

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package httpapi

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

func TestManagedRuntimeCallbacksAreRetired(t *testing.T) {
	st, err := store.Open(context.Background(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	server := NewServer(ServerOptions{Store: st, InternalToken: "internal-secret"})
	for _, operation := range []string{"start", "heartbeat", "events"} {
		t.Run(operation, func(t *testing.T) {
			request := httptest.NewRequest(http.MethodPost, "/api/internal/runtime-sessions/session/"+operation, bytes.NewBufferString(`{}`))
			request.Header.Set("X-Managed-Agents-Internal-Token", "internal-secret")
			request.Header.Set("Content-Type", "application/json")
			response := httptest.NewRecorder()
			server.router.ServeHTTP(response, request)
			if response.Code != http.StatusNotFound {
				t.Fatalf("retired callback returned %d: %s", response.Code, response.Body.String())
			}
		})
	}
}

func TestPrivateRuntimeDoesNotRunPublicServiceWorkers(t *testing.T) {
	server := &Server{runtimeOnly: true}
	for name, worker := range map[string]func(context.Context){"invocations": server.runServiceInvocations, "public-webhooks": server.runPublicSessionWebhooks} {
		t.Run(name, func(t *testing.T) {
			ctx, cancel := context.WithCancel(t.Context())
			defer cancel()
			done := make(chan any, 1)
			go func() { defer func() { done <- recover() }(); worker(ctx) }()
			select {
			case failure := <-done:
				if failure != nil {
					t.Fatalf("private role touched a public worker dependency: %v", failure)
				}
				if ctx.Err() != nil {
					t.Fatal("worker returned only after cancellation")
				}
			case <-time.After(5 * time.Second):
				t.Fatal("private role started a public background worker")
			}
		})
	}
}

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//     http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package httpapi

import (
	"bytes"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
	"github.com/gin-gonic/gin"
	"io"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestNativeSessionResourceCallsKeepExplicitArguments(t *testing.T) {
	for _, test := range []struct{ method, suffix, query, body, operation, kind string }{
		{"GET", "/budget", "", "", "budget", ""},
		{"PUT", "/budget", "", `{"max_model_calls":20}`, "configure_budget", ""},
		{"GET", "/artifacts", "?turn=turn&snapshot_id=snapshot&after=cursor&limit=1", "", "artifacts", ""},
		{"GET", "/required-actions", "?snapshot_id=snapshot&after=cursor&limit=1", "", "resource_page", "required_actions"},
		{"POST", "/artifacts", "", `{"file_id":"file"}`, "publish_artifact", ""},
		{"GET", "/checkpoints", "", "", "checkpoints", ""},
		{"POST", "/checkpoints/restore", "", `{"checkpoint_id":"checkpoint_saved"}`, "restore_checkpoint", ""},
		{"GET", "/checkpoint-operations/receipt", "", "", "checkpoint_operation", ""},
		{"POST", "/checkpoint-operations/receipt/cancel", "", "", "cancel_checkpoint_operation", ""},
		{"GET", "/subagents/child/events", "", "", "", ""},
	} {
		t.Run(test.method+test.suffix, func(t *testing.T) {
			c, _ := gin.CreateTestContext(httptest.NewRecorder())
			c.Request = httptest.NewRequest(test.method, "/session"+test.suffix+test.query, strings.NewReader(test.body))
			c.Request.Header.Set("If-Match", `"7"`)
			c.Request.Header.Set("Idempotency-Key", "publication-key")
			c.Params = gin.Params{{Key: "resourceId", Value: "receipt"}}
			origin := runtimeprotocol.Context{SessionID: "session", CommandID: "key", Actor: map[string]any{"user": "alice"}}
			call, err := nativeSessionResourceCall(c, test.suffix, origin)
			if err != nil || call.Operation != test.operation || call.ResourceKind != test.kind || call.Context.Actor["user"] != "alice" || call.Context.CommandID != "key" {
				t.Fatalf("wrong operation: %+v %v", call, err)
			}
			if test.query != "" && (call.SnapshotID != "snapshot" || call.After != "cursor" || call.Limit != 1) {
				t.Fatalf("lost pinned page: %+v", call)
			}
			if test.method == "PUT" && call.ExpectedRevision != `"7"` {
				t.Fatalf("lost budget revision: %+v", call)
			}
			if strings.Contains(test.suffix, "checkpoint-operations") && call.ReceiptID != "receipt" {
				t.Fatalf("lost native operation identity: %+v", call)
			}
		})
	}
	for _, test := range []struct{ method, query, body string }{
		{"GET", "?limit=bad", ""}, {"GET", "?limit=0", ""}, {"GET", "?limit=257", ""},
		{"PUT", "", `null`}, {"PUT", "", `{"max_model_calls":20} {"extra":true}`},
	} {
		c, _ := gin.CreateTestContext(httptest.NewRecorder())
		c.Request = httptest.NewRequest(test.method, "/session/budget"+test.query, strings.NewReader(test.body))
		if _, err := nativeSessionResourceCall(c, "/budget", runtimeprotocol.Context{}); err == nil {
			t.Fatalf("invalid resource request accepted: %+v", test)
		}
	}
}

func TestSessionProtocolScopeDoesNotRewriteCommandBodies(t *testing.T) {
	server := &Server{scopeMode: ScopeModeSingle, defaultTenant: "tenant", defaultNamespace: "namespace"}
	for _, path := range []string{"/api/v1/agent-sessions", "/api/v1/agent-sessions/id/budget", "/api/v1/agent-sessions/id/artifacts", "/api/v1/agent-sessions/id/turns", "/api/v1/agent-sessions/id/files"} {
		t.Run(path, func(t *testing.T) {
			payload := []byte(`{"max_model_calls":20,"input":{"message":"original"}}`)
			router := gin.New()
			router.Use(server.scopeMiddleware())
			router.POST(path, func(c *gin.Context) {
				actual, err := io.ReadAll(c.Request.Body)
				if err != nil || !bytes.Equal(actual, payload) {
					t.Errorf("Session body changed: %s %v", actual, err)
				}
				if c.Query("tenant") != "tenant" || c.Query("namespace") != "namespace" {
					t.Errorf("scope selection lost: %s", c.Request.URL)
				}
				c.Status(204)
			})
			request := httptest.NewRequest("POST", path, bytes.NewReader(payload))
			request.Header.Set("Content-Type", "application/json")
			response := httptest.NewRecorder()
			router.ServeHTTP(response, request)
			if response.Code != 204 {
				t.Fatalf("status %d", response.Code)
			}
		})
	}
}

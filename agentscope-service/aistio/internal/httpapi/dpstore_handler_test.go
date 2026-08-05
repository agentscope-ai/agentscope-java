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
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/spring-ai-alibaba/aistio/internal/store"
	"github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func newHostedStoreTestServer(t *testing.T) *Server {
	t.Helper()
	gin.SetMode(gin.TestMode)
	st, err := memory.Open(context.Background(), store.Config{})
	if err != nil {
		t.Fatalf("memory.Open: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })
	return NewServer(ServerOptions{
		Store:         st,
		HostedStore:   true,
		InternalToken: "test",
		Addr:          "127.0.0.1:0",
	})
}

func dpReq(method, path string, body any, token string) *http.Request {
	var buf *bytes.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		buf = bytes.NewReader(b)
	}
	var req *http.Request
	if buf != nil {
		req = httptest.NewRequest(method, path, buf)
		req.Header.Set("Content-Type", "application/json")
	} else {
		req = httptest.NewRequest(method, path, nil)
	}
	if token != "" {
		req.Header.Set("X-Builder-Internal-Token", token)
	}
	return req
}
func TestDPStore_NoToken(t *testing.T) {
	s := newHostedStoreTestServer(t)
	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, dpReq(http.MethodGet, "/api/v1/dp/healthz", nil, ""))
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401; body=%s", w.Code, w.Body.String())
	}
}

func TestDPStore_WrongToken(t *testing.T) {
	s := newHostedStoreTestServer(t)
	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, dpReq(http.MethodGet, "/api/v1/dp/healthz", nil, "wrong"))
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401; body=%s", w.Code, w.Body.String())
	}
}

func TestDPStore_KVTenantIsolation(t *testing.T) {
	s := newHostedStoreTestServer(t)

	put := func(agent string) {
		w := httptest.NewRecorder()
		s.router.ServeHTTP(w, dpReq(http.MethodPut, "/api/v1/dp/kv/item", map[string]any{
			"agentName":          agent,
			"namespace":          "default",
			"namespaceSegments":  []string{"ws"},
			"key":                "k1",
			"value":              map[string]any{"v": agent},
		}, "test"))
		if w.Code != http.StatusOK {
			t.Fatalf("put %s: status=%d body=%s", agent, w.Code, w.Body.String())
		}
	}
	put("agent-a")
	put("agent-b")

	get := func(agent string) map[string]any {
		w := httptest.NewRecorder()
		s.router.ServeHTTP(w, dpReq(http.MethodGet,
			"/api/v1/dp/kv/item?agentName="+agent+"&namespace=default&ns=ws&key=k1", nil, "test"))
		if w.Code != http.StatusOK {
			t.Fatalf("get %s: status=%d body=%s", agent, w.Code, w.Body.String())
		}
		var item map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &item); err != nil {
			t.Fatalf("unmarshal: %v", err)
		}
		return item
	}

	a := get("agent-a")
	b := get("agent-b")
	av, _ := a["value"].(map[string]any)
	bv, _ := b["value"].(map[string]any)
	if av["v"] != "agent-a" {
		t.Fatalf("tenant a value = %#v", av)
	}
	if bv["v"] != "agent-b" {
		t.Fatalf("tenant b value = %#v", bv)
	}
}

func TestDPStore_PutIfVersionConflict(t *testing.T) {
	s := newHostedStoreTestServer(t)

	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, dpReq(http.MethodPut, "/api/v1/dp/kv/item", map[string]any{
		"agentName":         "agent-a",
		"namespace":         "default",
		"namespaceSegments": []string{},
		"key":               "cas",
		"value":             map[string]any{"n": 1},
	}, "test"))
	if w.Code != http.StatusOK {
		t.Fatalf("initial put: %d %s", w.Code, w.Body.String())
	}

	w = httptest.NewRecorder()
	s.router.ServeHTTP(w, dpReq(http.MethodPut, "/api/v1/dp/kv/item", map[string]any{
		"agentName":         "agent-a",
		"namespace":         "default",
		"namespaceSegments": []string{},
		"key":               "cas",
		"value":             map[string]any{"n": 2},
		"expectedVersion":   int64(0),
	}, "test"))
	if w.Code != http.StatusConflict {
		t.Fatalf("cas conflict: status=%d body=%s", w.Code, w.Body.String())
	}
	var resp map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &resp)
	if _, ok := resp["currentVersion"]; !ok {
		t.Fatalf("expected currentVersion in conflict response: %s", w.Body.String())
	}
}

func TestDPStore_LockAcquireConflict(t *testing.T) {
	s := newHostedStoreTestServer(t)

	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, dpReq(http.MethodPost, "/api/v1/dp/locks/acquire", map[string]any{
		"agentName":  "agent-a",
		"namespace":  "default",
		"name":       "lock-1",
		"ttlSeconds": 60,
		"holder":     "h1",
	}, "test"))
	if w.Code != http.StatusOK {
		t.Fatalf("first acquire: %d %s", w.Code, w.Body.String())
	}

	w = httptest.NewRecorder()
	s.router.ServeHTTP(w, dpReq(http.MethodPost, "/api/v1/dp/locks/acquire", map[string]any{
		"agentName":  "agent-a",
		"namespace":  "default",
		"name":       "lock-1",
		"ttlSeconds": 60,
		"holder":     "h2",
	}, "test"))
	if w.Code != http.StatusConflict {
		t.Fatalf("second acquire: status=%d body=%s", w.Code, w.Body.String())
	}
	var resp map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &resp)
	if resp["holder"] != "h1" {
		t.Fatalf("conflict holder = %#v, want h1; body=%s", resp["holder"], w.Body.String())
	}
}

func TestDPStore_TasksUnauthorized(t *testing.T) {
	s := newHostedStoreTestServer(t)
	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, dpReq(http.MethodGet,
		"/api/v1/dp/tasks?agentName=a&parentSessionId=s", nil, ""))
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", w.Code)
	}
}

func TestDPStore_TasksTenantIsolation(t *testing.T) {
	s := newHostedStoreTestServer(t)

	put := func(agent, taskID string) {
		w := httptest.NewRecorder()
		s.router.ServeHTTP(w, dpReq(http.MethodPut, "/api/v1/dp/tasks/"+taskID, map[string]any{
			"agentName":       agent,
			"namespace":       "default",
			"parentSessionId": "sess-1",
			"status":          "RUNNING",
			"subAgentId":      agent,
		}, "test"))
		if w.Code != http.StatusOK {
			t.Fatalf("put %s: status=%d body=%s", agent, w.Code, w.Body.String())
		}
	}

	put("agent-a", "task-a")
	put("agent-b", "task-b")

	get := func(agent, taskID string) map[string]any {
		w := httptest.NewRecorder()
		s.router.ServeHTTP(w, dpReq(http.MethodGet,
			"/api/v1/dp/tasks/"+taskID+"?agentName="+agent+"&namespace=default&parentSessionId=sess-1", nil, "test"))
		if w.Code != http.StatusOK {
			t.Fatalf("get %s: status=%d body=%s", agent, w.Code, w.Body.String())
		}
		var item map[string]any
		_ = json.Unmarshal(w.Body.Bytes(), &item)
		return item
	}

	a := get("agent-a", "task-a")
	b := get("agent-b", "task-b")
	if a["subAgentId"] != "agent-a" || b["subAgentId"] != "agent-b" {
		t.Fatalf("tenant isolation failed: a=%#v b=%#v", a["subAgentId"], b["subAgentId"])
	}

	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, dpReq(http.MethodGet,
		"/api/v1/dp/tasks/task-a?agentName=agent-b&namespace=default&parentSessionId=sess-1", nil, "test"))
	if w.Code != http.StatusNotFound {
		t.Fatalf("cross-tenant get: status=%d body=%s", w.Code, w.Body.String())
	}
}

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
	"time"

	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/features"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	_ "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store/memory"
)

func TestOrchestrationRuntimeKeepsHostIdentityBehindThePortalCredential(t *testing.T) {
	testPortalHostIdentity(t, false)
}

func TestCombinedControlPlaneKeepsHostIdentityBehindThePortalCredential(t *testing.T) {
	testPortalHostIdentity(t, true)
}

func testPortalHostIdentity(t *testing.T, combined bool) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.DefaultConfig())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	_, err = st.RuntimeRegistry().UpsertRuntimePool(ctx, &controlmodel.RuntimePool{
		Tenant: "tenant", Namespace: "space", Name: "configured-pool",
	})
	if err != nil {
		t.Fatal(err)
	}
	s := NewServer(ServerOptions{RuntimeOnly: !combined, PortalRuntime: combined, Store: st,
		InternalToken: "portal-service-secret", TaskTokenSecret: "0123456789abcdef0123456789abcdef",
		Features: features.Gates{RuntimeHost: true}})
	token, _, err := s.runtimeTokens.Mint("host-a", "tenant", "space", time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	request := func(path, body, credential, internal string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPost, path, bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
		if credential != "" {
			req.Header.Set("Authorization", "Bearer "+credential)
		}
		if internal != "" {
			req.Header.Set("X-Builder-Internal-Token", internal)
		}
		out := httptest.NewRecorder()
		s.router.ServeHTTP(out, req)
		return out
	}
	path := "/api/internal/runtime/runtime-hosts/register"
	body := `{"hostKey":"host-a","tenant":"tenant","namespace":"space","poolName":"configured-pool","capacity":1,"capabilities":{"providers":{"codex":"1"}}}`
	for _, headers := range [][2]string{{"", "portal-service-secret"}, {token, ""}, {token, "wrong"}} {
		if out := request(path, body, headers[0], headers[1]); out.Code != http.StatusUnauthorized {
			t.Fatalf("missing identity accepted: status=%d body=%s", out.Code, out.Body.String())
		}
	}
	wrong := `{"hostKey":"host-b","tenant":"tenant","namespace":"space","poolName":"configured-pool","capacity":1}`
	if out := request(path, wrong, token, "portal-service-secret"); out.Code != http.StatusUnauthorized {
		t.Fatalf("cross-host registration: %d %s", out.Code, out.Body.String())
	}
	out := request(path, body, token, "portal-service-secret")
	if out.Code != http.StatusOK {
		t.Fatalf("scoped registration: %d %s", out.Code, out.Body.String())
	}
	var registered struct {
		Host controlmodel.RuntimeHost `json:"host"`
	}
	if err := json.Unmarshal(out.Body.Bytes(), &registered); err != nil || registered.Host.HostKey != "host-a" {
		t.Fatalf("host response=%+v err=%v", registered, err)
	}
	if _, err := st.RuntimeRegistry().GetRuntimeProfile(ctx, "tenant", "space", "auto-codex"); err != store.ErrNotFound {
		t.Fatalf("runtime registration wrote a resource definition: %v", err)
	}
	if combined {
		out := httptest.NewRecorder()
		s.router.ServeHTTP(out, httptest.NewRequest("GET", "/healthz", nil))
		if out.Code != 200 {
			t.Fatalf("combined control health: %d", out.Code)
		}
		out = httptest.NewRecorder()
		s.router.ServeHTTP(out, httptest.NewRequest("GET", "/api/v1/agents", nil))
		if out.Code == 404 {
			t.Fatalf("combined control lost resource routes: %d", out.Code)
		}
		return
	}
	for _, forbidden := range []string{"/api/v1/runtime-hosts/register", "/api/v1/agent-sessions", "/api/v1/agents", "/api/v1/issues"} {
		if out := request(forbidden, body, token, "portal-service-secret"); out.Code != http.StatusNotFound {
			t.Fatalf("public/CRUD route exposed at %s: %d", forbidden, out.Code)
		}
	}
}

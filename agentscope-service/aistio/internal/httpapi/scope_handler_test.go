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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestCurrentScopeHidesAndCanonicalizesSingleScope(t *testing.T) {
	server := NewServer(ServerOptions{
		AuthToken: "console", ScopeMode: ScopeModeSingle,
		DefaultTenant: "acme", DefaultNamespace: "engineering",
	})
	request := httptest.NewRequest(http.MethodGet, "/api/v1/me/scope?tenant=other&namespace=other", nil)
	request.Header.Set("Authorization", "Bearer console")
	response := httptest.NewRecorder()
	server.router.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", response.Code, response.Body.String())
	}
	var body struct {
		Mode            string `json:"mode"`
		Tenant          string `json:"tenant"`
		Namespace       string `json:"namespace"`
		SelectorVisible bool   `json:"selectorVisible"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.Mode != ScopeModeSingle || body.Tenant != "acme" || body.Namespace != "engineering" || body.SelectorVisible {
		t.Fatalf("scope = %+v", body)
	}
}

func TestSingleScopeCanonicalizesTopLevelJSONScope(t *testing.T) {
	st, err := store.Open(context.Background(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	server := NewServer(ServerOptions{
		Store: st, AuthToken: "console", ScopeMode: ScopeModeSingle,
		DefaultTenant: "acme", DefaultNamespace: "engineering",
	})
	request := httptest.NewRequest(http.MethodPost, "/api/v1/runtime-profiles",
		bytes.NewBufferString(`{"tenant":"other","namespace":"other","name":"profile","provider":"codex"}`))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer console")
	response := httptest.NewRecorder()
	server.router.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", response.Code, response.Body.String())
	}
	profile, err := st.RuntimeRegistry().GetRuntimeProfile(context.Background(), "acme", "engineering", "profile")
	if err != nil {
		t.Fatal(err)
	}
	if profile.Tenant != "acme" || profile.Namespace != "engineering" {
		t.Fatalf("profile scope=%s/%s", profile.Tenant, profile.Namespace)
	}
}

func TestCurrentScopeKeepsExplicitMultiScope(t *testing.T) {
	server := NewServer(ServerOptions{AuthToken: "console", ScopeMode: ScopeModeMulti})
	request := httptest.NewRequest(http.MethodGet, "/api/v1/me/scope?tenant=acme&namespace=engineering", nil)
	request.Header.Set("Authorization", "Bearer console")
	response := httptest.NewRecorder()
	server.router.ServeHTTP(response, request)
	if response.Code != http.StatusOK || !json.Valid(response.Body.Bytes()) {
		t.Fatalf("status=%d body=%s", response.Code, response.Body.String())
	}
	var body map[string]any
	_ = json.Unmarshal(response.Body.Bytes(), &body)
	if body["mode"] != ScopeModeMulti || body["tenant"] != "acme" || body["namespace"] != "engineering" || body["selectorVisible"] != true {
		t.Fatalf("scope = %+v", body)
	}
}

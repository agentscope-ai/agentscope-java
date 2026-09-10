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

package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestLocalRuntimeConfigRoundTripIsOwnerOnly(t *testing.T) {
	path := filepath.Join(t.TempDir(), "nested", "config.json")
	config := defaultLocalRuntimeConfig(path)
	config.ControlPlane = "https://agentscope.example"
	config.Credential = "secret"
	config.Providers = []localRuntimeProvider{{Name: "codex", Binary: "/bin/codex", Version: "1.0"}}
	if err := saveLocalRuntimeConfig(path, &config); err != nil {
		t.Fatal(err)
	}
	loaded, err := loadLocalRuntimeConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.ControlPlane != config.ControlPlane || loaded.Credential != "secret" || len(loaded.Providers) != 1 {
		t.Fatalf("loaded config = %+v", loaded)
	}
	if runtime.GOOS != "windows" {
		info, err := os.Stat(path)
		if err != nil {
			t.Fatal(err)
		}
		if got := info.Mode().Perm(); got != 0o600 {
			t.Fatalf("config permissions = %o, want 600", got)
		}
	}
}

func TestResolveControlPlaneDiscoversHealthyLocalServer(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/healthz" {
			http.NotFound(response, request)
			return
		}
		response.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	got, err := resolveControlPlane(context.Background(), server.URL+"/")
	if err != nil {
		t.Fatal(err)
	}
	if got != server.URL {
		t.Fatalf("server = %q, want %q", got, server.URL)
	}
}

func TestEnrollRuntimeHostCredentialUsesPlatformBearer(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/api/v1/runtime-host-enrollments" || request.Header.Get("Authorization") != "Bearer platform-token" {
			http.Error(response, "unexpected request", http.StatusUnauthorized)
			return
		}
		var body map[string]string
		if err := json.NewDecoder(request.Body).Decode(&body); err != nil || body["hostKey"] != "host-1" {
			http.Error(response, "bad body", http.StatusBadRequest)
			return
		}
		response.Header().Set("Content-Type", "application/json")
		response.WriteHeader(http.StatusCreated)
		_, _ = response.Write([]byte(`{"runtimeToken":"asrh_scoped","hostKey":"host-1","tenant":"acme","namespace":"engineering"}`))
	}))
	defer server.Close()
	enrollment, err := enrollRuntimeHostCredential(context.Background(), server.URL, "platform-token", "host-1", "acme", "engineering")
	if err != nil || enrollment.RuntimeToken != "asrh_scoped" || enrollment.Tenant != "acme" || enrollment.Namespace != "engineering" {
		t.Fatalf("enrollment=%+v err=%v", enrollment, err)
	}
}

func TestExchangeRuntimeHostEnrollmentTokenReturnsAssignedScope(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/api/v1/runtime-host-enrollments/exchange" || request.Header.Get("Authorization") != "Bearer asre_bootstrap" {
			http.Error(response, "unexpected request", http.StatusUnauthorized)
			return
		}
		response.Header().Set("Content-Type", "application/json")
		response.WriteHeader(http.StatusCreated)
		_, _ = response.Write([]byte(`{"runtimeToken":"asrh_scoped","hostKey":"host-1","tenant":"acme","namespace":"engineering"}`))
	}))
	defer server.Close()
	enrollment, err := exchangeRuntimeHostEnrollmentToken(context.Background(), server.URL, "asre_bootstrap", "host-1")
	if err != nil || enrollment.RuntimeToken != "asrh_scoped" || enrollment.Tenant != "acme" || enrollment.Namespace != "engineering" {
		t.Fatalf("enrollment=%+v err=%v", enrollment, err)
	}
}

func TestLoginPlatformUser(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/api/auth/login" {
			http.NotFound(response, request)
			return
		}
		var body map[string]string
		if err := json.NewDecoder(request.Body).Decode(&body); err != nil || body["username"] != "admin" || body["password"] != "secret" {
			http.Error(response, "bad credentials", http.StatusUnauthorized)
			return
		}
		response.Header().Set("Content-Type", "application/json")
		_, _ = response.Write([]byte(`{"token":"platform-token"}`))
	}))
	defer server.Close()
	token, err := loginPlatformUser(context.Background(), server.URL, "admin", "secret")
	if err != nil || token != "platform-token" {
		t.Fatalf("token=%q err=%v", token, err)
	}
}

func TestConciseVersionSkipsWarnings(t *testing.T) {
	got := conciseVersion("Skipped invalid MCP server\nqodercli 1.2.3\n")
	if got != "qodercli 1.2.3" {
		t.Fatalf("version = %q", got)
	}
}

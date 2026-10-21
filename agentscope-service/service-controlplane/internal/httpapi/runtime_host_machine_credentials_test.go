// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/features"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
)

func TestRuntimeHostMachineRenewalPreservesScopeWithoutRenewingRegistration(t *testing.T) {
	st, err := store.Open(t.Context(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	server := NewServer(ServerOptions{Store: st, RuntimeOnly: true,
		InternalToken:   "machine-service-token-at-least-32-bytes",
		TaskTokenSecret: "machine-signing-secret-at-least-32-bytes", Features: features.Gates{RuntimeHost: true}})
	short := server.runtimeTokens
	short.TTL = time.Minute
	credential, initial, err := short.Mint("host-1", "tenant", "space", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	call := func(path, token string, body any) *httptest.ResponseRecorder {
		data, _ := json.Marshal(body)
		request := httptest.NewRequest(http.MethodPost, "/api/internal/runtime/runtime-hosts/"+path, bytes.NewReader(data))
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("X-Builder-Internal-Token", "machine-service-token-at-least-32-bytes")
		request.Header.Set("Authorization", "Bearer "+token)
		response := httptest.NewRecorder()
		server.router.ServeHTTP(response, request)
		return response
	}
	registered := call("register", credential, map[string]any{"hostKey": "host-1", "tenant": "tenant",
		"namespace": "space", "poolName": "coding-default", "capacity": 1})
	if registered.Code != http.StatusOK {
		t.Fatalf("register=%d %s", registered.Code, registered.Body.String())
	}
	var registration struct {
		Host *model.RuntimeHost `json:"host"`
	}
	if err := json.Unmarshal(registered.Body.Bytes(), &registration); err != nil || registration.Host == nil {
		t.Fatal("missing Host registration")
	}
	host := registration.Host
	path := host.ID.String() + "/credentials"
	request := map[string]any{"generation": host.LeaseGeneration, "tenant": "forged", "hostKey": "other"}
	wrong, _, _ := short.Mint("other", "tenant", "space", time.Now())
	expired, _, _ := short.Mint("host-1", "tenant", "space", time.Now().Add(-2*time.Minute))
	for _, token := range []string{"", wrong, expired, "machine-service-token-at-least-32-bytes"} {
		if response := call(path, token, request); response.Code != http.StatusUnauthorized {
			t.Fatalf("wrong machine authority accepted: %d", response.Code)
		}
	}
	if response := call(path, credential, map[string]any{"generation": host.LeaseGeneration + 1}); response.Code != http.StatusConflict {
		t.Fatalf("stale registration renewed: %d", response.Code)
	}
	response := call(path, credential, request)
	if response.Code != http.StatusOK {
		t.Fatalf("renew=%d %s", response.Code, response.Body.String())
	}
	var renewed struct {
		RuntimeToken string `json:"runtimeToken"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &renewed); err != nil {
		t.Fatal(err)
	}
	claims, err := server.runtimeTokens.Verify(renewed.RuntimeToken, time.Now())
	if err != nil || claims.HostKey != initial.HostKey || claims.Tenant != initial.Tenant ||
		claims.Namespace != initial.Namespace || claims.ExpiresAt <= initial.ExpiresAt {
		t.Fatal("renewal changed scope or did not extend the credential")
	}
	after, err := st.RuntimeRegistry().GetRuntimeHost(t.Context(), host.ID)
	if err != nil || after.LeaseGeneration != host.LeaseGeneration || !after.LastSeenAt.Equal(host.LastSeenAt) || after.State != host.State {
		t.Fatal("credential renewal changed the physical registration")
	}
	if _, err := server.taskTokens.VerifyClaims(renewed.RuntimeToken, time.Now()); err == nil {
		t.Fatal("Host renewal created task execution authority")
	}
}

func TestRuntimeHostEnrollmentUsesAuthorizedNamespaceInSingleScopeMode(t *testing.T) {
	st, err := store.Open(t.Context(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	_, err = st.Access().PutNamespace(t.Context(), &model.Namespace{Tenant: "default", Name: "engineering", DisplayName: "Engineering",
		Kind: "shared", Owner: "alice", Members: map[string][]string{"viewer": {"viewer"}}}, 0, "alice")
	if err != nil {
		t.Fatal(err)
	}
	server := NewServer(ServerOptions{Store: st, ScopeMode: ScopeModeSingle,
		TaskTokenSecret: "machine-signing-secret-at-least-32-bytes", Features: features.Gates{RuntimeHost: true}})
	server.router = gin.New()
	server.router.Use(func(c *gin.Context) {
		c.Set("userId", c.GetHeader("X-Test-User"))
		c.Set("username", c.GetHeader("X-Test-User"))
		c.Set("groups", []string{"user"})
		c.Set(ctxConsoleAuth, true)
		c.Next()
	})
	server.registerRoutes()
	for _, path := range []string{"runtime-host-enrollments", "runtime-host-enrollment-tokens"} {
		response := accessRequest(server, "alice", http.MethodPost, "/api/v1/"+path,
			`{"hostKey":"host","tenant":"default","namespace":"engineering"}`)
		if response.Code != http.StatusCreated {
			t.Fatalf("authorized enrollment %s=%d %s", path, response.Code, response.Body.String())
		}
		var value struct {
			Namespace string `json:"namespace"`
		}
		if json.Unmarshal(response.Body.Bytes(), &value) != nil || value.Namespace != "engineering" {
			t.Fatal("enrollment replaced the authorized namespace with the default")
		}
		response = accessRequest(server, "viewer", http.MethodPost, "/api/v1/"+path,
			`{"hostKey":"host","tenant":"default","namespace":"engineering"}`)
		if response.Code != http.StatusForbidden {
			t.Fatalf("viewer enrolled a machine: %d", response.Code)
		}
	}
}

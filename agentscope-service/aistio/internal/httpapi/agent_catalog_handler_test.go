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

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestExternalAgentRegistrationClaimsStableIdentity(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	server := NewServer(ServerOptions{Store: st, InternalToken: "bootstrap-secret", AuthToken: "console-secret"})

	request := func(body string, headers map[string]string) *httptest.ResponseRecorder {
		t.Helper()
		req := httptest.NewRequest(http.MethodPost, "/api/v1/agent-registrations", bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
		for key, value := range headers {
			req.Header.Set(key, value)
		}
		response := httptest.NewRecorder()
		server.router.ServeHTTP(response, req)
		return response
	}

	first := request(`{"tenant":"acme","namespace":"engineering","agentKey":"reviewer","instanceKey":"pod-1","capacity":2}`,
		map[string]string{"X-Builder-Internal-Token": "bootstrap-secret"})
	if first.Code != http.StatusCreated {
		t.Fatalf("first registration status=%d body=%s", first.Code, first.Body.String())
	}
	var created struct {
		Agent                  controlmodel.Agent         `json:"agent"`
		Binding                controlmodel.AgentBinding  `json:"binding"`
		Instance               controlmodel.AgentInstance `json:"instance"`
		RegistrationCredential string                     `json:"registrationCredential"`
	}
	if err := json.Unmarshal(first.Body.Bytes(), &created); err != nil {
		t.Fatal(err)
	}
	if created.RegistrationCredential == "" || created.Agent.ID != created.Instance.AgentID || created.Binding.ID != created.Instance.BindingID {
		t.Fatalf("registration did not return one stable identity: %+v", created)
	}

	second := request(`{"tenant":"acme","namespace":"engineering","agentKey":"reviewer","instanceKey":"pod-2","capacity":2}`,
		map[string]string{"X-Agent-Registration-Credential": created.RegistrationCredential})
	if second.Code != http.StatusOK {
		t.Fatalf("credential claim status=%d body=%s", second.Code, second.Body.String())
	}
	var scaled struct {
		Agent    controlmodel.Agent         `json:"agent"`
		Binding  controlmodel.AgentBinding  `json:"binding"`
		Instance controlmodel.AgentInstance `json:"instance"`
	}
	if err := json.Unmarshal(second.Body.Bytes(), &scaled); err != nil {
		t.Fatal(err)
	}
	if scaled.Agent.ID != created.Agent.ID || scaled.Binding.ID != created.Binding.ID || scaled.Instance.ID == created.Instance.ID {
		t.Fatalf("scale-out created a second logical identity: first=%+v second=%+v", created, scaled)
	}

	forged := request(`{"tenant":"acme","namespace":"engineering","agentKey":"reviewer","instanceKey":"pod-forged"}`,
		map[string]string{"X-Agent-Registration-Credential": "asreg_forged"})
	if forged.Code != http.StatusForbidden {
		t.Fatalf("forged credential status=%d body=%s", forged.Code, forged.Body.String())
	}
	bootstrapClaim := request(`{"tenant":"acme","namespace":"engineering","agentKey":"reviewer","instanceKey":"pod-bootstrap"}`,
		map[string]string{"X-Builder-Internal-Token": "bootstrap-secret"})
	if bootstrapClaim.Code != http.StatusConflict {
		t.Fatalf("bootstrap reclaimed existing key status=%d body=%s", bootstrapClaim.Code, bootstrapClaim.Body.String())
	}

	instances, err := st.RuntimeRegistry().ListAgentInstances(ctx, "acme", "engineering", created.Agent.ID)
	if err != nil || len(instances) != 2 {
		t.Fatalf("logical Agent should have two instances: instances=%+v err=%v", instances, err)
	}
}

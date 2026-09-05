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

	"github.com/spring-ai-alibaba/aistio/internal/features"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestRuntimeHostEnrollmentIssuesGatewaySafeScopedCredential(t *testing.T) {
	st, err := store.Open(context.Background(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	server := NewServer(ServerOptions{
		Store: st, AuthToken: "console-secret", InternalToken: "legacy-internal-secret",
		TaskTokenSecret: "0123456789abcdef0123456789abcdef", Features: features.Gates{RuntimeHost: true},
	})

	enroll := httptest.NewRequest(http.MethodPost, "/api/v1/runtime-host-enrollments",
		bytes.NewBufferString(`{"hostKey":"host-1","tenant":"acme","namespace":"engineering"}`))
	enroll.Header.Set("Content-Type", "application/json")
	enroll.Header.Set("Authorization", "Bearer console-secret")
	enrollment := httptest.NewRecorder()
	server.router.ServeHTTP(enrollment, enroll)
	if enrollment.Code != http.StatusCreated {
		t.Fatalf("enrollment status=%d body=%s", enrollment.Code, enrollment.Body.String())
	}
	var credential struct {
		RuntimeToken string `json:"runtimeToken"`
	}
	if err := json.Unmarshal(enrollment.Body.Bytes(), &credential); err != nil || credential.RuntimeToken == "" {
		t.Fatalf("credential=%+v err=%v", credential, err)
	}

	register := func(hostKey string) *httptest.ResponseRecorder {
		body := `{"hostKey":"` + hostKey + `","tenant":"acme","namespace":"engineering","poolName":"coding-default","capacity":1}`
		request := httptest.NewRequest(http.MethodPost, "/api/v1/runtime-hosts/register", bytes.NewBufferString(body))
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("Authorization", "Bearer "+credential.RuntimeToken)
		response := httptest.NewRecorder()
		server.router.ServeHTTP(response, request)
		return response
	}
	if response := register("host-1"); response.Code != http.StatusOK {
		t.Fatalf("scoped registration status=%d body=%s", response.Code, response.Body.String())
	}
	if response := register("host-2"); response.Code != http.StatusUnauthorized {
		t.Fatalf("cross-host registration status=%d body=%s", response.Code, response.Body.String())
	}
}

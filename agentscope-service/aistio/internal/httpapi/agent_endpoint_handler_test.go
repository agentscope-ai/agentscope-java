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

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestAgentEndpointJobIsIdempotent(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "t", Namespace: "n", AgentKey: "worker", DisplayName: "worker", Status: controlmodel.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	binding, err := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{AgentID: agent.ID, Tenant: "t", Namespace: "n", Kind: controlmodel.DataPlaneExternalApplication, Configuration: json.RawMessage(`{"instanceSelector":{}}`), Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	_ = binding
	server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	request := func(method, path, body, token string, idem bool) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, path, bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
			req.Header.Set("X-API-Key", token)
		}
		if idem {
			req.Header.Set("Idempotency-Key", "same-job")
		}
		w := httptest.NewRecorder()
		server.router.ServeHTTP(w, req)
		return w
	}
	created := request(http.MethodPost, "/api/v1/agent-endpoints", `{"tenant":"t","namespace":"n","name":"worker jobs","slug":"worker-jobs","targetType":"agent","targetRef":"`+agent.ID.String()+`","invocationMode":"job"}`, "console", false)
	if created.Code != http.StatusCreated {
		t.Fatalf("create endpoint: %d %s", created.Code, created.Body.String())
	}
	var out struct {
		Credential string `json:"credential"`
	}
	if err = json.Unmarshal(created.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	first := request(http.MethodPost, "/invoke/v1/endpoints/worker-jobs/jobs", `{"title":"do it","input":{"x":1}}`, out.Credential, true)
	second := request(http.MethodPost, "/invoke/v1/endpoints/worker-jobs/jobs", `{"title":"do it again"}`, out.Credential, true)
	if first.Code != http.StatusAccepted || second.Code != http.StatusAccepted {
		t.Fatalf("invoke statuses: %d %s / %d %s", first.Code, first.Body, second.Code, second.Body)
	}
	var a, b struct {
		IssueID uuid.UUID `json:"issueId"`
		RunID   uuid.UUID `json:"runId"`
	}
	_ = json.Unmarshal(first.Body.Bytes(), &a)
	_ = json.Unmarshal(second.Body.Bytes(), &b)
	if a.IssueID == uuid.Nil || a.IssueID != b.IssueID || a.RunID != b.RunID {
		t.Fatalf("idempotency diverged: %+v %+v", a, b)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: a.IssueID})
	if err != nil || len(tasks) != 1 || tasks[0].AgentRef != agent.ID.String() {
		t.Fatalf("expected one stable Agent task: %+v err=%v", tasks, err)
	}
}

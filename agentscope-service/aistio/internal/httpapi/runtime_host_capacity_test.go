// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"context"
	"encoding/json"
	"testing"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func TestRuntimeHostCapacity(t *testing.T) {
	for _, driver := range []string{"memory", "postgres"} {
		t.Run(driver, func(t *testing.T) {
			cfg := store.Config{Driver: store.DriverMemory}
			if driver == "postgres" {
				cfg = acceptancePostgresConfig(t)
			}
			s, st := accessTestServer(t, cfg)
			ctx := context.Background()
			registry := st.RuntimeRegistry()
			register := func(capacity int32, capabilities string) *controlmodel.RuntimeHost {
				h, err := registry.UpsertRuntimeHost(ctx, &controlmodel.RuntimeHost{Tenant: "default", Namespace: "engineering", HostKey: "capacity-test", PoolName: "coding", Capacity: capacity, Capabilities: json.RawMessage(capabilities)})
				if err != nil {
					t.Fatal(err)
				}
				return h
			}
			h := register(1, `{}`)
			path := "/api/v1/runtime-hosts/" + h.ID.String() + "/capacity"
			for _, user := range []string{"bob", "carol", "developer", "outsider"} {
				if w := accessRequest(s, user, "PATCH", path, `{"capacity":4,"expectedCapacity":1}`); w.Code < 400 {
					t.Fatalf("%s changed shared capacity: %d", user, w.Code)
				}
			}
			if w := accessRequest(s, "operator", "PATCH", path+"?tenant=default&namespace=other", `{"capacity":4,"expectedCapacity":1}`); w.Code < 400 {
				t.Fatal("cross namespace update allowed")
			}
			for _, body := range []string{`{}`, `{"capacity":4}`, `{"capacity":0,"expectedCapacity":1}`, `{"capacity":51,"expectedCapacity":1}`, `{"capacity":1.5,"expectedCapacity":1}`} {
				if w := accessRequest(s, "operator", "PATCH", path, body); w.Code != 400 {
					t.Fatalf("invalid %s: %d %s", body, w.Code, w.Body.String())
				}
			}
			if w := accessRequest(s, "operator", "PATCH", path, `{"capacity":4,"expectedCapacity":1}`); w.Code != 200 {
				t.Fatalf("save: %d %s", w.Code, w.Body.String())
			}
			if w := accessRequest(s, "operator", "PATCH", path, `{"capacity":2,"expectedCapacity":1}`); w.Code != 409 {
				t.Fatalf("stale save: %d %s", w.Code, w.Body.String())
			}
			h, err := registry.HeartbeatRuntimeHost(ctx, h.ID, h.LeaseGeneration, 3, nil)
			if err != nil || h.Capacity != 4 || !h.CapacityManaged {
				t.Fatalf("heartbeat lost capacity: %+v %v", h, err)
			}
			// Lowering the limit must preserve active work and the current lease generation.
			generation := h.LeaseGeneration
			if w := accessRequest(s, "alice", "PATCH", path, `{"capacity":2,"expectedCapacity":4}`); w.Code != 200 {
				t.Fatalf("lower: %d %s", w.Code, w.Body.String())
			}
			h, err = registry.GetRuntimeHost(ctx, h.ID)
			if err != nil || h.Active != 3 || h.Capacity != 2 || h.LeaseGeneration != generation {
				t.Fatalf("lower interrupted active work: %+v %v", h, err)
			}
			h = register(1, `{}`)
			if h.Capacity != 2 || !h.CapacityManaged {
				t.Fatalf("restart overwrote console capacity: %+v", h)
			}
		})
	}
}

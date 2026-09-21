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

package memory

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func TestEndpointRateLimitAndIdempotencyArePrincipalScoped(t *testing.T) {
	ctx := context.Background()
	raw, err := Open(ctx, store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	defer raw.Close()
	repo := raw.Endpoints()
	endpoint, err := repo.Create(ctx, &controlmodel.Endpoint{Tenant: "t", Namespace: "n", Name: "jobs",
		Slug: "jobs", TargetType: controlmodel.EndpointTargetAgent, TargetRef: uuid.New(),
		InvocationMode: controlmodel.EndpointJobMode, AuthPolicy: []byte(`{"type":"api_key"}`)})
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	allowed, _, err := repo.ConsumeRateLimit(ctx, endpoint.ID, "caller-a", 1, 60, now)
	if err != nil || !allowed {
		t.Fatalf("first request rejected: allowed=%v err=%v", allowed, err)
	}
	allowed, _, err = repo.ConsumeRateLimit(ctx, endpoint.ID, "caller-a", 1, 60, now)
	if err != nil || allowed {
		t.Fatalf("same principal bypassed limit: allowed=%v err=%v", allowed, err)
	}
	allowed, _, err = repo.ConsumeRateLimit(ctx, endpoint.ID, "caller-b", 1, 60, now)
	if err != nil || !allowed {
		t.Fatalf("independent principal was throttled: allowed=%v err=%v", allowed, err)
	}
	first, fresh, err := repo.ReserveInvocation(ctx, &controlmodel.EndpointInvocation{EndpointID: endpoint.ID,
		Mode: controlmodel.EndpointJobMode, PrincipalRef: "caller-a", IdempotencyKey: "same", CorrelationID: "a"})
	if err != nil || !fresh {
		t.Fatalf("first invocation: fresh=%v err=%v", fresh, err)
	}
	second, fresh, err := repo.ReserveInvocation(ctx, &controlmodel.EndpointInvocation{EndpointID: endpoint.ID,
		Mode: controlmodel.EndpointJobMode, PrincipalRef: "caller-b", IdempotencyKey: "same", CorrelationID: "b"})
	if err != nil || !fresh || first.ID == second.ID {
		t.Fatalf("idempotency leaked across principals: first=%s second=%s fresh=%v err=%v", first.ID, second.ID, fresh, err)
	}
}

func TestEndpointReleaseDeploymentKeepsStableEndpoint(t *testing.T) {
	ctx := context.Background()
	raw, err := Open(ctx, store.Config{})
	if err != nil {
		t.Fatal(err)
	}
	defer raw.Close()
	repo := raw.Endpoints()
	firstTarget := uuid.New()
	endpoint, err := repo.Create(ctx, &controlmodel.Endpoint{Tenant: "t", Namespace: "n", Name: "workflow",
		Slug: "workflow", TargetType: controlmodel.EndpointTargetOrchestrationRevision, TargetRef: firstTarget,
		InvocationMode: controlmodel.EndpointJobMode, AuthPolicy: []byte(`{"type":"api_key"}`)})
	if err != nil {
		t.Fatal(err)
	}
	endpoint, first, err := repo.DeployRelease(ctx, endpoint.ID, endpoint.TargetType, firstTarget,
		endpoint.Version, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "developer"}, "initial publication")
	if err != nil {
		t.Fatal(err)
	}
	secondTarget := uuid.New()
	endpoint, second, err := repo.DeployRelease(ctx, endpoint.ID, endpoint.TargetType, secondTarget,
		endpoint.Version, controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "developer"}, "deploy revision 2")
	if err != nil {
		t.Fatal(err)
	}
	if endpoint.ID == uuid.Nil || endpoint.TargetRef != secondTarget || endpoint.ActiveRelease != 2 ||
		endpoint.ActiveReleaseID == nil || *endpoint.ActiveReleaseID != second.ID {
		t.Fatalf("unexpected active deployment: %#v", endpoint)
	}
	if first.Number != 1 || second.Number != 2 || first.EndpointID != endpoint.ID || second.EndpointID != endpoint.ID {
		t.Fatalf("unexpected release sequence: first=%#v second=%#v", first, second)
	}
	releases, err := repo.ListReleases(ctx, endpoint.ID)
	if err != nil || len(releases) != 2 || releases[0].ID != second.ID || releases[1].ID != first.ID {
		t.Fatalf("release history not newest-first: releases=%#v err=%v", releases, err)
	}
	if _, _, err = repo.DeployRelease(ctx, endpoint.ID, endpoint.TargetType, firstTarget,
		endpoint.Version-1, controlmodel.Actor{Type: controlmodel.ActorHuman}, "stale"); err == nil {
		t.Fatal("stale endpoint version was accepted")
	}
}

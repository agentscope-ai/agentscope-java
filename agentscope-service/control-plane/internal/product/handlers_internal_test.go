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

package product

import (
	"context"
	"errors"
	"github.com/gin-gonic/gin"
	"net/http"
	"net/http/httptest"
	"os"
	"sync"
	"testing"
)

func TestEnsureDefaultLocalEnvironmentIsConcurrentAndIdempotent(t *testing.T) {
	dsn := os.Getenv("CONTROL_PLANE_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("CONTROL_PLANE_TEST_POSTGRES_DSN not set; skipping product postgres test")
	}
	ctx := context.Background()
	db, err := openDB(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	if err = migrate(ctx, db); err != nil {
		t.Fatal(err)
	}
	ownerID := "test-default-environment-" + shortID("")
	t.Cleanup(func() {
		_, _ = db.Pool.Exec(context.Background(), `DELETE FROM environments WHERE owner_id=$1`, ownerID)
	})
	server := &Server{db: db, cfg: Config{AllowLocalEnvironment: true}}

	const callers = 8
	ids := make(chan string, callers)
	errs := make(chan error, callers)
	var wg sync.WaitGroup
	for range callers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			id, createErr := server.ensureDefaultLocalEnvironment(ctx, ownerID)
			if createErr != nil {
				errs <- createErr
				return
			}
			ids <- id
		}()
	}
	wg.Wait()
	close(ids)
	close(errs)
	for createErr := range errs {
		t.Errorf("ensure default environment: %v", createErr)
	}
	var expected string
	for id := range ids {
		if expected == "" {
			expected = id
		}
		if id != expected {
			t.Errorf("environment id=%s, want shared id %s", id, expected)
		}
	}
	var count int
	if err = db.Pool.QueryRow(ctx,
		`SELECT COUNT(*) FROM environments WHERE owner_id=$1 AND archived_at IS NULL AND lower(type)='local'`, ownerID).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Fatalf("active default environments=%d, want one", count)
	}
}

func TestEnsureManagedDefinitionBindsDefaultLocalEnvironmentWhenAllowed(t *testing.T) {
	dsn := os.Getenv("CONTROL_PLANE_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("CONTROL_PLANE_TEST_POSTGRES_DSN not set; skipping product postgres test")
	}
	ctx := context.Background()
	db, err := openDB(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	if err = migrate(ctx, db); err != nil {
		t.Fatal(err)
	}
	ownerID := "test-managed-default-" + shortID("")
	agentID := "agent-" + shortID("")
	t.Cleanup(func() {
		_, _ = db.Pool.Exec(context.Background(), `DELETE FROM agent_versions WHERE owner_id=$1`, ownerID)
		_, _ = db.Pool.Exec(context.Background(), `DELETE FROM agents WHERE owner_id=$1`, ownerID)
		_, _ = db.Pool.Exec(context.Background(), `DELETE FROM environments WHERE owner_id=$1`, ownerID)
	})
	server := &Server{db: db, cfg: Config{
		WorkspaceRoot:         t.TempDir(),
		AllowLocalEnvironment: true,
	}}

	definition, err := server.EnsureManagedDefinition(ctx, ownerID, agentID, ManagedDefinitionInput{
		Name:                        "managed-default",
		ProvisionDefaultEnvironment: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	environmentID, ok := definition["defaultEnvironmentId"].(string)
	if !ok || environmentID == "" {
		t.Fatalf("defaultEnvironmentId=%#v, want non-empty string", definition["defaultEnvironmentId"])
	}
	environment, err := server.validateEnvironmentBinding(ctx, ownerID, environmentID)
	if err != nil {
		t.Fatal(err)
	}
	if got := normalizeEnvironmentType(environment.Type); got != localEnvironmentType {
		t.Fatalf("environment type=%q, want %q", got, localEnvironmentType)
	}
	version, err := server.ManagedDefinitionVersion(ctx, ownerID, agentID, 1)
	if err != nil {
		t.Fatal(err)
	}
	snapshot, ok := version["snapshot"].(map[string]any)
	if !ok || snapshot["defaultEnvironmentId"] != environmentID {
		t.Fatalf("snapshot defaultEnvironmentId=%#v, want %q", snapshot["defaultEnvironmentId"], environmentID)
	}
}

func TestManagedDefinitionRemainsUnboundWhenLocalEnvironmentIsDisabled(t *testing.T) {
	dsn := os.Getenv("CONTROL_PLANE_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("CONTROL_PLANE_TEST_POSTGRES_DSN not set; skipping product postgres test")
	}
	ctx := context.Background()
	db, err := openDB(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	if err = migrate(ctx, db); err != nil {
		t.Fatal(err)
	}
	ownerID := "test-managed-policy-" + shortID("")
	agentID := "agent-" + shortID("")
	t.Cleanup(func() {
		_, _ = db.Pool.Exec(context.Background(), `DELETE FROM agent_versions WHERE owner_id=$1`, ownerID)
		_, _ = db.Pool.Exec(context.Background(), `DELETE FROM agents WHERE owner_id=$1`, ownerID)
		_, _ = db.Pool.Exec(context.Background(), `DELETE FROM environments WHERE owner_id=$1`, ownerID)
	})
	server := &Server{db: db, cfg: Config{WorkspaceRoot: t.TempDir()}}

	definition, err := server.EnsureManagedDefinition(ctx, ownerID, agentID, ManagedDefinitionInput{
		Name:                        "production-managed",
		ProvisionDefaultEnvironment: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	if got := definition["defaultEnvironmentId"]; got != nil {
		t.Fatalf("defaultEnvironmentId=%#v, want nil", got)
	}
	if _, err = server.resolveDefaultEnvironmentID(ctx, ownerID, agentID); !errors.Is(err, ErrLocalEnvironmentDisabled) {
		t.Fatalf("resolve error=%v, want ErrLocalEnvironmentDisabled", err)
	}
	var environmentCount int
	if err = db.Pool.QueryRow(ctx, `SELECT COUNT(*) FROM environments WHERE owner_id=$1`, ownerID).Scan(&environmentCount); err != nil {
		t.Fatal(err)
	}
	if environmentCount != 0 {
		t.Fatalf("environment count=%d, want zero", environmentCount)
	}
}

func TestLocalEnvironmentCannotCreateNewBindingWhenDisabled(t *testing.T) {
	dsn := os.Getenv("CONTROL_PLANE_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("CONTROL_PLANE_TEST_POSTGRES_DSN not set; skipping product postgres test")
	}
	ctx := context.Background()
	db, err := openDB(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	if err = migrate(ctx, db); err != nil {
		t.Fatal(err)
	}
	ownerID := "test-local-policy-" + shortID("")
	environmentID := "env_" + shortID("")
	t.Cleanup(func() {
		_, _ = db.Pool.Exec(context.Background(), `DELETE FROM environments WHERE owner_id=$1`, ownerID)
	})
	now := nowMillis()
	if _, err = db.Pool.Exec(ctx, `INSERT INTO environments
		(environment_id,owner_id,name,type,config_json,api_key_hash,created_at,updated_at)
		VALUES($1,$2,'existing-local','local','{}',$3,$4,$4)`, environmentID, ownerID, sha256Hex("key"), now); err != nil {
		t.Fatal(err)
	}
	server := &Server{db: db, cfg: Config{AllowLocalEnvironment: false}}
	if _, err = server.validateEnvironmentBinding(ctx, ownerID, environmentID); !errors.Is(err, ErrLocalEnvironmentDisabled) {
		t.Fatalf("binding error=%v, want ErrLocalEnvironmentDisabled", err)
	}
	if _, err = server.ensureDefaultLocalEnvironment(ctx, ownerID); !errors.Is(err, ErrLocalEnvironmentDisabled) {
		t.Fatalf("default error=%v, want ErrLocalEnvironmentDisabled", err)
	}
}

func TestManagedRuntimeStatusCallbackIsRetired(t *testing.T) {
	router := gin.New()
	(&Server{}).registerInternal(router)
	response := httptest.NewRecorder()
	router.ServeHTTP(response, httptest.NewRequest(http.MethodPatch, "/api/internal/sessions/session/runtime", nil))
	if response.Code != http.StatusNotFound {
		t.Fatalf("retired runtime callback returned %d", response.Code)
	}
}

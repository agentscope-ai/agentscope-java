/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package postgres

import (
	"context"
	"fmt"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

func serviceMigrationConfig(t *testing.T) store.Config {
	t.Helper()
	dsn := os.Getenv("CONTROL_PLANE_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("CONTROL_PLANE_TEST_POSTGRES_DSN not set")
	}
	u, err := url.Parse(dsn)
	if err != nil {
		t.Fatal(err)
	}
	conn, err := pgx.Connect(t.Context(), dsn)
	if err != nil {
		t.Fatal(err)
	}
	schema := fmt.Sprintf("service_migration_%x", time.Now().UnixNano())
	quoted := pgx.Identifier{schema}.Sanitize()
	if _, err = conn.Exec(t.Context(), "CREATE SCHEMA "+quoted); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = conn.Exec(context.Background(), "DROP SCHEMA "+quoted+" CASCADE")
		_ = conn.Close(context.Background())
	})
	q := u.Query()
	q.Set("search_path", schema)
	u.RawQuery = q.Encode()
	return store.Config{Driver: store.DriverPostgres, PostgresDSN: u.String(), MaxOpenConns: 2}
}

func TestServiceMigrationsConcurrentFreshStart(t *testing.T) {
	cfg := serviceMigrationConfig(t)
	ctx, cancel := context.WithTimeout(t.Context(), 45*time.Second)
	defer cancel()
	start := make(chan struct{})
	results := make(chan error, 3)
	for range 3 {
		go func() {
			<-start
			st, err := store.Open(ctx, cfg)
			if err == nil {
				err = st.Close()
			}
			results <- err
		}()
	}
	close(start)
	for range 3 {
		if err := <-results; err != nil {
			t.Fatal(err)
		}
	}
}

func TestServiceMigrationsSeededUpgradeRollbackAndRetry(t *testing.T) {
	cfg := serviceMigrationConfig(t)
	ctx := t.Context()
	st, err := store.Open(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	conn, err := pgx.Connect(ctx, cfg.PostgresDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(context.Background())
	exec := func(query string, args ...any) {
		t.Helper()
		if _, err := conn.Exec(ctx, query, args...); err != nil {
			t.Fatal(err)
		}
	}
	down := func(version string) {
		t.Helper()
		body, err := migrationFS.ReadFile("migrations/" + version + ".down.sql")
		if err != nil {
			t.Fatal(err)
		}
		exec(string(body))
		exec("DELETE FROM schema_migrations WHERE version=$1", version)
	}
	const identity = "0113_service_application_identity"
	const contract = "0112_service_api_contract"
	down(identity)
	down(contract)
	ep, release, credential, inv := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	exec(`INSERT INTO endpoints(id,tenant,namespace,name,slug,target_type,target_ref,invocation_mode,auth_policy) VALUES($1,'t','n','legacy','legacy','agent',$2,'job','{}')`, ep, uuid.New())
	exec(`INSERT INTO endpoint_releases(id,endpoint_id,release_number,target_type,target_ref,created_by_type) VALUES($1,$2,1,'agent',$3,'human')`, release, ep, uuid.New())
	exec(`INSERT INTO endpoint_credentials(id,endpoint_id,name,key_prefix,secret_hash,secret_ciphertext) VALUES($1,$2,'legacy','prefix',decode('00','hex'),decode('00','hex'))`, credential, ep)
	exec(`INSERT INTO endpoint_invocations(id,endpoint_id,mode,principal_type,principal_ref,status,correlation_id) VALUES($1,$2,'job','human','owner','completed','fixture')`, inv, ep)
	// Fail the identity migration midway, after result_mapping was added, and
	// verify that neither its DDL nor its schema version leaks from the tx.
	exec(`ALTER TABLE endpoint_invocations ADD COLUMN actor JSONB`)
	if err = st.Migrate(ctx); err == nil || !strings.Contains(err.Error(), identity) {
		t.Fatalf("expected identity migration failure: %v", err)
	}
	var leaked bool
	if err = conn.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='endpoints' AND column_name='result_mapping')`).Scan(&leaked); err != nil || leaked {
		t.Fatalf("failed migration leaked DDL: %v %v", leaked, err)
	}
	if err = conn.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE version=$1)`, identity).Scan(&leaked); err != nil || leaked {
		t.Fatalf("failed migration recorded version: %v %v", leaked, err)
	}
	exec(`ALTER TABLE endpoint_invocations DROP COLUMN actor`)
	if err = st.Migrate(ctx); err != nil {
		t.Fatal(err)
	}
	var unbound bool
	if err = conn.QueryRow(ctx, `SELECT application_id IS NULL FROM endpoint_credentials WHERE id=$1`, credential).Scan(&unbound); err != nil || !unbound {
		t.Fatalf("legacy key unexpectedly granted Application identity: %v %v", unbound, err)
	}
	app := uuid.New()
	exec(`INSERT INTO applications(id,tenant,namespace,name,owner_user_id) VALUES($1,'t','n','client','owner')`, app)
	exec(`UPDATE endpoints SET result_mapping='{"answer":"/answer"}' WHERE id=$1`, ep)
	exec(`UPDATE endpoint_credentials SET application_id=$2 WHERE id=$1`, credential, app)
	exec(`UPDATE endpoint_releases SET contract='{"frozen":true}' WHERE id=$1`, release)
	exec(`UPDATE endpoint_invocations SET application_id=$2,credential_id=$3,release_id=$4,contract='{"frozen":true}',status='partial_succeeded',consumed_tokens=12 WHERE id=$1`, inv, app, credential, release)
	down(identity)
	down(contract)
	var status string
	if err = conn.QueryRow(ctx, `SELECT status FROM endpoint_invocations WHERE id=$1`, inv).Scan(&status); err != nil || status != "partial_succeeded" {
		t.Fatalf("rollback removed invocation: %s %v", status, err)
	}
	if err = st.Migrate(ctx); err != nil {
		t.Fatal(err)
	}
	if n, err := st.Endpoints().CountActiveInvocations(ctx, store.EndpointInvocationFilter{EndpointID: ep}); err != nil || n != 0 {
		t.Fatalf("partial success counted active after re-upgrade: %d %v", n, err)
	}
}

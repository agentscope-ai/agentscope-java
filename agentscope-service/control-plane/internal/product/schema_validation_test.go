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

package product

import (
	"context"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

func TestProductSchemaMigrationAndReadOnlyStartup(t *testing.T) {
	dsn := os.Getenv("CONTROL_PLANE_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("CONTROL_PLANE_TEST_POSTGRES_DSN not set")
	}
	ctx, cancel := context.WithTimeout(t.Context(), 45*time.Second)
	defer cancel()
	admin, err := pgx.Connect(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close(context.Background())
	suffix := strings.ReplaceAll(uuid.NewString(), "-", "")
	database, role := "product_schema_"+suffix, "product_reader_"+suffix
	quotedDB, quotedRole := pgx.Identifier{database}.Sanitize(), pgx.Identifier{role}.Sanitize()
	if _, err = admin.Exec(ctx, "CREATE DATABASE "+quotedDB); err != nil {
		t.Fatal(err)
	}
	defer func() {
		if _, err := admin.Exec(context.Background(), "DROP DATABASE "+quotedDB); err != nil {
			t.Error(err)
		}
		if _, err := admin.Exec(context.Background(), "DROP ROLE IF EXISTS "+quotedRole); err != nil {
			t.Error(err)
		}
	}()
	u, err := url.Parse(dsn)
	if err != nil {
		t.Fatal(err)
	}
	u.Path = "/" + database
	dsn = u.String()
	owner, err := openDB(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer owner.Close()
	if err = validateSchema(ctx, owner); err == nil || !strings.Contains(err.Error(), "migration job") {
		t.Fatalf("uninitialized product schema accepted: %v", err)
	}
	results := make(chan error, 3)
	for range 3 {
		go func() { results <- MigrateSchema(ctx, dsn) }()
	}
	for range 3 {
		if err = <-results; err != nil {
			t.Fatal(err)
		}
	}
	var users int
	if err = owner.Pool.QueryRow(ctx, "SELECT count(*) FROM cp.users").Scan(&users); err != nil || users != 0 {
		t.Fatalf("schema job must not bootstrap users: %d %v", users, err)
	}
	// Recreate the immediately preceding schema in this test-owned database.
	// The new migration must remove persisted physical Managed markers as well.
	if _, err = owner.Pool.Exec(ctx, `DELETE FROM cp.product_schema_migrations WHERE version='0006_remove_managed_runtime_markers';
 ALTER TABLE cp.sessions ADD COLUMN runtime_agent_task_id TEXT;
 ALTER TABLE cp.sessions ADD COLUMN runtime_attempt_id TEXT;
 ALTER TABLE cp.sessions ADD COLUMN runtime_dispatch_generation BIGINT;
 ALTER TABLE cp.sessions ADD COLUMN runtime_turn_id TEXT;
 INSERT INTO cp.sessions(session_id,owner_id,agent_id,environment_id,status,version,created_at,updated_at,runtime_dispatch_generation,runtime_turn_id)
 VALUES('obsolete-marker','owner','agent','environment','running',1,1,1,7,'old-turn')`); err != nil {
		t.Fatal(err)
	}
	if err = MigrateSchema(ctx, dsn); err != nil {
		t.Fatal(err)
	}
	var markers int
	if err = owner.Pool.QueryRow(ctx, `SELECT count(*) FROM information_schema.columns WHERE table_schema='cp' AND table_name='sessions' AND column_name IN ('runtime_agent_task_id','runtime_attempt_id','runtime_dispatch_generation','runtime_turn_id')`).Scan(&markers); err != nil || markers != 0 {
		t.Fatalf("obsolete marker migration: %d %v", markers, err)
	}
	if _, err = (&Server{db: owner}).loadSession(ctx, "obsolete-marker"); err != nil {
		t.Fatalf("session scanner still needs retired columns: %v", err)
	}
	if _, err = owner.Pool.Exec(ctx, "CREATE ROLE "+quotedRole+" NOLOGIN; GRANT USAGE ON SCHEMA cp TO "+quotedRole+"; GRANT SELECT ON cp.product_schema_migrations TO "+quotedRole); err != nil {
		t.Fatal(err)
	}
	query := u.Query()
	query.Set("role", role)
	u.RawQuery = query.Encode()
	server, err := Open(ctx, Config{DSN: u.String(), SchemaMode: "validate", JWTSecret: strings.Repeat("t", 32), WorkspaceRoot: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	var currentRole string
	if err = server.db.Pool.QueryRow(ctx, "SELECT current_user").Scan(&currentRole); err != nil || currentRole != role {
		t.Fatalf("read-only role was not applied: %s %v", currentRole, err)
	}
	if _, err = server.db.Pool.Exec(ctx, "CREATE TABLE cp.startup_must_not_write(id INT)"); err == nil {
		t.Fatal("application role unexpectedly has DDL permission")
	}
	if _, err = owner.Pool.Exec(ctx, "UPDATE cp.product_schema_migrations SET checksum='changed' WHERE version='0004_product_policy_changes'"); err != nil {
		t.Fatal(err)
	}
	if err = validateSchema(ctx, server.db); err == nil || !strings.Contains(err.Error(), "checksum") {
		t.Fatalf("changed published migration accepted: %v", err)
	}
	if _, err = owner.Pool.Exec(ctx, "DELETE FROM cp.product_schema_migrations WHERE version='0004_product_policy_changes'"); err != nil {
		t.Fatal(err)
	}
	if err = validateSchema(ctx, server.db); err == nil || !strings.Contains(err.Error(), "missing") {
		t.Fatalf("missing migration accepted: %v", err)
	}
	if err = MigrateSchema(ctx, dsn); err != nil {
		t.Fatal(err)
	}
	if _, err = owner.Pool.Exec(ctx, "INSERT INTO cp.product_schema_migrations(version,checksum) VALUES('9999_future_fixture','future')"); err != nil {
		t.Fatal(err)
	}
	if err = validateSchema(ctx, server.db); err == nil || !strings.Contains(err.Error(), "unsupported") {
		t.Fatalf("future schema accepted: %v", err)
	}
}

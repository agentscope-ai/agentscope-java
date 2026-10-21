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

package postgres

import (
	"context"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/jackc/pgx/v5"
)

func TestRuntimeSchemaValidationWithReadOnlyRole(t *testing.T) {
	cfg := serviceMigrationConfig(t)
	ctx, cancel := context.WithTimeout(t.Context(), 45*time.Second)
	defer cancel()
	cfg.SchemaMode = "validate"
	if _, err := store.Open(ctx, cfg); err == nil || !strings.Contains(err.Error(), "migration job") {
		t.Fatalf("fresh schema must require explicit migration: %v", err)
	}
	cfg.SchemaMode = "migrate"
	st, err := store.Open(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	st.Close()
	owner, err := pgx.Connect(ctx, cfg.PostgresDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer owner.Close(context.Background())
	role := "runtime_reader_" + strings.ReplaceAll(time.Now().Format("150405.000000000"), ".", "")
	quotedRole := pgx.Identifier{role}.Sanitize()
	var schema string
	if err = owner.QueryRow(ctx, "SELECT current_schema()").Scan(&schema); err != nil {
		t.Fatal(err)
	}
	quotedSchema := pgx.Identifier{schema}.Sanitize()
	if _, err = owner.Exec(ctx, "CREATE ROLE "+quotedRole+" NOLOGIN"); err != nil {
		t.Fatal(err)
	}
	defer func() {
		_, _ = owner.Exec(context.Background(), "REVOKE ALL ON "+quotedSchema+".schema_migrations FROM "+quotedRole)
		_, _ = owner.Exec(context.Background(), "REVOKE ALL ON SCHEMA "+quotedSchema+" FROM "+quotedRole)
		if _, err := owner.Exec(context.Background(), "DROP ROLE "+quotedRole); err != nil {
			t.Error(err)
		}
	}()
	if _, err = owner.Exec(ctx, "GRANT USAGE ON SCHEMA "+quotedSchema+" TO "+quotedRole+"; GRANT SELECT ON "+quotedSchema+".schema_migrations TO "+quotedRole); err != nil {
		t.Fatal(err)
	}
	u, _ := url.Parse(cfg.PostgresDSN)
	query := u.Query()
	query.Set("role", role)
	u.RawQuery = query.Encode()
	readerCfg := cfg
	readerCfg.PostgresDSN = u.String()
	readerCfg.SchemaMode = "validate"
	reader, err := store.Open(ctx, readerCfg)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	conn, err := pgx.Connect(ctx, readerCfg.PostgresDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(context.Background())
	var currentRole string
	if err = conn.QueryRow(ctx, "SELECT current_user").Scan(&currentRole); err != nil || currentRole != role {
		t.Fatalf("read-only role was not applied: %q %v", currentRole, err)
	}
	if _, err = conn.Exec(ctx, "CREATE TABLE startup_must_not_write(id INT)"); err == nil {
		t.Fatal("application role unexpectedly has schema DDL permission")
	}
	if _, err = owner.Exec(ctx, "INSERT INTO schema_migrations(version) VALUES('9999_future_fixture')"); err != nil {
		t.Fatal(err)
	}
	if err = reader.ValidateSchema(ctx); err == nil || !strings.Contains(err.Error(), "unsupported") {
		t.Fatalf("future schema accepted: %v", err)
	}
	if _, err = owner.Exec(ctx, "DELETE FROM schema_migrations WHERE version IN ('9999_future_fixture','0124_portal_subgraph_observation')"); err != nil {
		t.Fatal(err)
	}
	if err = reader.ValidateSchema(ctx); err == nil || !strings.Contains(err.Error(), "0124_portal_subgraph_observation") {
		t.Fatalf("missing migration accepted: %v", err)
	}
}

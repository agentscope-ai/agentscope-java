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

package policychanges

import (
	"context"
	"errors"
	"net/url"
	"os"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

func TestMemoryLeaseReplacementAndAcknowledgement(t *testing.T) {
	repo := &Memory{}
	repo.Record("tenant", "namespace", "app")
	verifyClaimFence(t, repo)
}
func verifyClaimFence(t *testing.T, repo Repository) {
	t.Helper()
	now := time.Now().UTC()
	first, err := repo.Claim(t.Context(), now, time.Second, 1)
	if err != nil || len(first) != 1 {
		t.Fatalf("initial claim: %v %v", first, err)
	}
	none, err := repo.Claim(t.Context(), now, time.Second, 1)
	if err != nil || len(none) != 0 {
		t.Fatalf("lease was not exclusive: %v %v", none, err)
	}
	next, err := repo.Claim(t.Context(), now.Add(2*time.Second), 30*time.Second, 1)
	if err != nil || len(next) != 1 || next[0].ID != first[0].ID || next[0].ClaimToken == first[0].ClaimToken {
		t.Fatalf("reclaim: %v %v", next, err)
	}
	if err = repo.Acknowledge(t.Context(), first[0].ID, first[0].ClaimToken); !errors.Is(err, ErrClaim) {
		t.Fatalf("stale claim acknowledged: %v", err)
	}
	for range 2 {
		if err = repo.Acknowledge(t.Context(), next[0].ID, next[0].ClaimToken); err != nil {
			t.Fatal(err)
		}
	}
	none, err = repo.Claim(t.Context(), now.Add(time.Minute), time.Second, 1)
	if err != nil || len(none) != 0 {
		t.Fatalf("delivered record was claimed again: %v %v", none, err)
	}
}
func TestPostgresRollbackAndClaimFence(t *testing.T) {
	dsn := os.Getenv("CONTROL_PLANE_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("CONTROL_PLANE_TEST_POSTGRES_DSN not set")
	}
	parent, err := pgx.Connect(t.Context(), dsn)
	if err != nil {
		t.Fatal(err)
	}
	schema := "policy_" + uuid.New().String()[:8]
	quoted := pgx.Identifier{schema}.Sanitize()
	if _, err = parent.Exec(t.Context(), "CREATE SCHEMA "+quoted); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		parent.Exec(context.Background(), "DROP SCHEMA "+quoted+" CASCADE")
		parent.Close(context.Background())
	})
	u, err := url.Parse(dsn)
	if err != nil {
		t.Fatal(err)
	}
	q := u.Query()
	q.Set("search_path", schema)
	u.RawQuery = q.Encode()
	pool, err := pgxpool.New(t.Context(), u.String())
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	if _, err = pool.Exec(t.Context(), SchemaSQL+`CREATE TABLE resources(id text PRIMARY KEY);CREATE TRIGGER resource_policy_change AFTER INSERT ON resources FOR EACH ROW EXECUTE FUNCTION emit_policy_change('global');`); err != nil {
		t.Fatal(err)
	}
	tx, err := pool.Begin(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if _, err = tx.Exec(t.Context(), `INSERT INTO resources(id) VALUES('rollback')`); err != nil {
		t.Fatal(err)
	}
	if err = tx.Rollback(t.Context()); err != nil {
		t.Fatal(err)
	}
	repo := &Postgres{Pool: pool}
	rows, err := repo.Claim(t.Context(), time.Now(), time.Second, 1)
	if err != nil || len(rows) != 0 {
		t.Fatalf("rolled back update emitted a change: %v %v", rows, err)
	}
	if _, err = pool.Exec(t.Context(), `INSERT INTO resources(id) VALUES('committed')`); err != nil {
		t.Fatal(err)
	}
	verifyClaimFence(t, repo)
}

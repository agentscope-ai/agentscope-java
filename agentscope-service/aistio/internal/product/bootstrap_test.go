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
	"os"
	"strings"
	"sync"
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

func TestValidateBootstrap(t *testing.T) {
	for _, tc := range []struct {
		name  string
		cfg   Config
		valid bool
	}{
		{"disabled", Config{}, true},
		{"valid", Config{BootstrapAdmin: "operator", BootstrapPassword: "long-random-password"}, true},
		{"missing user", Config{BootstrapPassword: "long-random-password"}, false},
		{"short password", Config{BootstrapAdmin: "operator", BootstrapPassword: "short"}, false},
		{"bcrypt limit", Config{BootstrapAdmin: "operator", BootstrapPassword: strings.Repeat("a", 73)}, false},
		{"whitespace", Config{BootstrapAdmin: " admin", BootstrapPassword: "long-random-password"}, false},
		{"seed conflict", Config{BootstrapAdmin: "operator", BootstrapPassword: "long-random-password", SeedUsers: true}, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if err := validateBootstrap(tc.cfg); (err == nil) != tc.valid {
				t.Fatalf("valid=%v, error=%v", tc.valid, err)
			}
		})
	}
}

func TestBootstrapAdminConcurrentAndRestart(t *testing.T) {
	dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set")
	}
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	schema := "bootstrap_" + strings.ReplaceAll(shortID(""), "-", "")
	quoted := pgx.Identifier{schema}.Sanitize()
	if _, err = pool.Exec(ctx, "CREATE SCHEMA "+quoted); err != nil {
		t.Fatal(err)
	}
	defer pool.Exec(ctx, "DROP SCHEMA "+quoted+" CASCADE")
	pc, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		t.Fatal(err)
	}
	pc.ConnConfig.RuntimeParams["search_path"] = schema
	isolated, err := pgxpool.NewWithConfig(ctx, pc)
	if err != nil {
		t.Fatal(err)
	}
	defer isolated.Close()
	if _, err = isolated.Exec(ctx, `CREATE TABLE users(user_id text PRIMARY KEY,username text UNIQUE,password_hash text,roles_csv text,created_at bigint)`); err != nil {
		t.Fatal(err)
	}
	db := &DB{Pool: isolated}
	cfg := Config{BootstrapAdmin: "operator", BootstrapPassword: "first-password-123"}
	var wg sync.WaitGroup
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := bootstrapAdmin(ctx, db, cfg); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	cfg.BootstrapPassword = "different-password-123"
	if err = bootstrapAdmin(ctx, db, cfg); err != nil {
		t.Fatal(err)
	}
	var count int
	var hash, roles string
	if err = isolated.QueryRow(ctx, `SELECT count(*) FROM users`).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Fatalf("expected one administrator, got %d", count)
	}
	if err = isolated.QueryRow(ctx, `SELECT password_hash,roles_csv FROM users WHERE username='operator'`).Scan(&hash, &roles); err != nil {
		t.Fatal(err)
	}
	if !checkPassword(hash, "first-password-123") || checkPassword(hash, cfg.BootstrapPassword) || roles != "user,admin" {
		t.Fatal("restart must preserve initial administrator credentials and roles")
	}
}

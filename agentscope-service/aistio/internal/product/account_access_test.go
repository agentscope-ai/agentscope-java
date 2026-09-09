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
	"os"
	"testing"
)

func TestAccountTokenUsesLiveIdentityAndRoleRevocation(t *testing.T) {
	dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set")
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
	id := shortID("access-")
	username := id
	now := nowMillis()
	_, err = db.Pool.Exec(ctx, `INSERT INTO users(user_id,username,password_hash,roles_csv,created_at) VALUES($1,$2,'unused','admin',$3)`, id, username, now)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _, _ = db.Pool.Exec(context.Background(), `DELETE FROM users WHERE user_id=$1`, id) })
	cfg := DefaultConfig()
	server := &Server{db: db, cfg: cfg}
	token, err := issueToken(cfg.JWTSecret, id, username, []string{"admin"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = server.VerifyAccountToken(ctx, token); err != nil {
		t.Fatal(err)
	}
	_, err = db.Pool.Exec(ctx, `UPDATE users SET roles_csv='user' WHERE user_id=$1`, id)
	if err != nil {
		t.Fatal(err)
	}
	claims, err := server.VerifyAccountToken(ctx, token)
	if err != nil || len(claims.Roles) != 1 || claims.Roles[0] != "user" {
		t.Fatalf("stale global roles: %+v %v", claims, err)
	}
	_, err = db.Pool.Exec(ctx, `DELETE FROM users WHERE user_id=$1`, id)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = server.VerifyAccountToken(ctx, token); err == nil {
		t.Fatal("deleted account token still accepted")
	}
}

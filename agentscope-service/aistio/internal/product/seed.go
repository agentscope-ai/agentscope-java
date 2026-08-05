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
	"log"
)

func seedUsers(ctx context.Context, db *DB) error {
	var n int
	if err := db.Pool.QueryRow(ctx, `SELECT COUNT(*) FROM users`).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return nil
	}

	seeds := []struct {
		id, username, password, roles string
	}{
		{"admin", "admin", "admin", "user,admin"},
		{"bob", "bob", "bob", "user"},
		{"alice", "alice", "alice", "user"},
	}
	now := nowMillis()
	for _, u := range seeds {
		hash, err := hashPassword(u.password)
		if err != nil {
			return err
		}
		_, err = db.Pool.Exec(ctx,
			`INSERT INTO users (user_id, username, password_hash, roles_csv, created_at)
			 VALUES ($1,$2,$3,$4,$5) ON CONFLICT (user_id) DO NOTHING`,
			u.id, u.username, hash, u.roles, now)
		if err != nil {
			return err
		}
		log.Printf("seeded user %s/%s roles=%s", u.username, u.password, u.roles)
	}
	return nil
}

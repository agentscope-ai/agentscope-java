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
	"fmt"
	"strings"
)

func validateBootstrap(cfg Config) error {
	if cfg.BootstrapAdmin == "" && cfg.BootstrapPassword == "" {
		return nil
	}
	if strings.TrimSpace(cfg.BootstrapAdmin) == "" || cfg.BootstrapAdmin != strings.TrimSpace(cfg.BootstrapAdmin) {
		return fmt.Errorf("bootstrap admin must be a nonempty username without surrounding whitespace")
	}
	// bcrypt accepts at most 72 bytes. Reject invalid configuration before opening the DB.
	if len(cfg.BootstrapPassword) < 12 || len(cfg.BootstrapPassword) > 72 {
		return fmt.Errorf("bootstrap password must contain 12 to 72 bytes")
	}
	if cfg.SeedUsers {
		return fmt.Errorf("disable AISTIO_SEED_USERS when configuring a bootstrap administrator")
	}
	return nil
}

// bootstrapAdmin never resets an existing account. The lock serializes concurrent first starts.
func bootstrapAdmin(ctx context.Context, db *DB, cfg Config) error {
	if err := validateBootstrap(cfg); err != nil {
		return err
	}
	tx, err := db.Pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtext('agentscope-bootstrap-admin'))`); err != nil {
		return err
	}
	var count int
	if err = tx.QueryRow(ctx, `SELECT count(*) FROM users`).Scan(&count); err != nil {
		return err
	}
	if count == 0 {
		hash, err := hashPassword(cfg.BootstrapPassword)
		if err != nil {
			return err
		}
		if _, err = tx.Exec(ctx, `INSERT INTO users(user_id,username,password_hash,roles_csv,created_at)
			VALUES($1,$2,$3,'user,admin',$4)`, shortID("user-"), cfg.BootstrapAdmin, hash, nowMillis()); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

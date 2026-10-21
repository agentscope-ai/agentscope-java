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
	"testing"
	"time"
)

func TestProductPolicyChangesCommitWithAccountMutations(t *testing.T) {
	s := resourceTestServer(t)
	id := shortID("policy_user_")
	resourceSQL(t, s, `INSERT INTO users(user_id,username,password_hash,created_at) VALUES($1,$1,'fixture',1)`, id)
	var before int
	if err := s.db.Pool.QueryRow(t.Context(), `SELECT count(*) FROM policy_change_outbox`).Scan(&before); err != nil {
		t.Fatal(err)
	}
	tx, err := s.db.Pool.Begin(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if _, err = tx.Exec(t.Context(), `UPDATE users SET disabled=true WHERE user_id=$1`, id); err != nil {
		t.Fatal(err)
	}
	if err = tx.Rollback(t.Context()); err != nil {
		t.Fatal(err)
	}
	var total int
	if err = s.db.Pool.QueryRow(t.Context(), `SELECT count(*) FROM policy_change_outbox`).Scan(&total); err != nil || total != before {
		t.Fatalf("rollback emitted changes: %d %v", total, err)
	}
	resourceSQL(t, s, `UPDATE users SET disabled=true WHERE user_id=$1`, id)
	changes, err := s.PolicyChanges().Claim(t.Context(), time.Now(), time.Second, 8)
	if err != nil || len(changes) != 1 || changes[0].Tenant != "" || changes[0].Namespace != "" {
		t.Fatalf("account scope: %v %v", changes, err)
	}
	if err = s.PolicyChanges().Acknowledge(t.Context(), changes[0].ID, changes[0].ClaimToken); err != nil {
		t.Fatal(err)
	}
}

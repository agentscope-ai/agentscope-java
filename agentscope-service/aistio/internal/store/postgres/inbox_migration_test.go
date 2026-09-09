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

package postgres

import (
	"context"
	"os"
	"testing"

	"github.com/jackc/pgx/v5"
)

func TestInboxAttentionMigration(t *testing.T) {
	dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set")
	}
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(ctx)
	// Connection-local legacy fixtures exercise the upgrade without modifying
	// persistent application data or relying on store-suite execution order.
	_, err = conn.Exec(ctx, `
CREATE TEMP TABLE issues (id uuid PRIMARY KEY, tenant text, namespace text, title text,
 status text, kind text DEFAULT 'user_work', visibility text DEFAULT 'work_hub',
 completion_policy text DEFAULT 'review', archived_at timestamptz, parent_issue_id uuid,
 assignee_type text, assignee_ref text, creator_type text DEFAULT 'human', creator_ref text DEFAULT 'alice',
 version bigint DEFAULT 1, updated_at timestamptz DEFAULT now());
CREATE TEMP TABLE agent_tasks (id uuid, issue_id uuid, parent_task_id uuid,
 accountable_human_ref text, created_at timestamptz);
CREATE TEMP TABLE comments (id uuid, type text);
CREATE TEMP TABLE approvals (id uuid PRIMARY KEY, tenant text, namespace text, approver_ref text,
 status text, issue_id uuid, requested_by_type text, requested_by_ref text, reason text,
 created_at timestamptz DEFAULT now(), updated_at timestamptz DEFAULT now());
CREATE TEMP TABLE inbox_items (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant text,
 namespace text, recipient_type text DEFAULT 'human', recipient_ref text, type text, severity text,
 issue_id uuid, approval_id uuid, comment_id uuid, actor_type text, actor_ref text, title text,
 body text, details jsonb, read boolean DEFAULT false, archived boolean DEFAULT false,
 dedupe_key text, created_at timestamptz DEFAULT now());
CREATE UNIQUE INDEX legacy_inbox_dedupe ON inbox_items(tenant,dedupe_key) WHERE dedupe_key IS NOT NULL;
INSERT INTO issues(id,tenant,namespace,title,status) VALUES
 ('00000000-0000-0000-0000-000000000001','t','n','Blocked work','blocked'),
 ('00000000-0000-0000-0000-000000000002','t','n','Review work','in_review'),
 ('00000000-0000-0000-0000-000000000003','t','n','Finished work','done');
INSERT INTO issues(id,tenant,namespace,title,status,kind,visibility) VALUES
 ('00000000-0000-0000-0000-000000000004','t','n','Hidden work','blocked','operational','execution_only');
INSERT INTO approvals(id,tenant,namespace,approver_ref,status,requested_by_type,reason) VALUES
 ('00000000-0000-0000-0000-000000000011','t','n','alice','pending','agent','existing approval'),
 ('00000000-0000-0000-0000-000000000012','t','n','alice','pending','agent','missing inbox');
INSERT INTO inbox_items(tenant,namespace,recipient_ref,type,approval_id,read,archived) VALUES
 ('t','n','alice','approval','00000000-0000-0000-0000-000000000011',true,true),
 ('t','n','alice','approval','00000000-0000-0000-0000-000000000011',false,false);
INSERT INTO inbox_items(tenant,namespace,recipient_ref,type,issue_id) VALUES
 ('t','n','alice','review_request','00000000-0000-0000-0000-000000000002'),
 ('t','n','bob','review_request','00000000-0000-0000-0000-000000000002'),
 ('t','n','alice','review_request','00000000-0000-0000-0000-000000000003');`)
	if err != nil {
		t.Fatal(err)
	}
	body, err := migrationFS.ReadFile("migrations/0105_inbox_attention.up.sql")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = conn.Exec(ctx, string(body)); err != nil {
		t.Fatal(err)
	}
	checks := []struct {
		name, predicate string
		want            int
	}{
		{"one active copy per pending approval", "type='approval' AND needs_action AND NOT archived", 2},
		{"revived approval preserves read", "approval_id='00000000-0000-0000-0000-000000000011' AND read AND NOT archived AND needs_action", 1},
		{"root blocker backfilled", "type='issue_blocked' AND recipient_ref='alice' AND needs_action", 1},
		{"review owner actionable", "type='review_request' AND recipient_ref='alice' AND needs_action", 1},
		{"review subscriber informational", "recipient_ref='bob' AND NOT needs_action AND NOT archived", 1},
		{"stale review closed without reading", "issue_id='00000000-0000-0000-0000-000000000003' AND archived AND NOT read AND resolved_at IS NOT NULL", 1},
		{"hidden work excluded", "issue_id='00000000-0000-0000-0000-000000000004'", 0},
	}
	for _, check := range checks {
		t.Run(check.name, func(t *testing.T) {
			var got int
			if err := conn.QueryRow(ctx, "SELECT count(*) FROM inbox_items WHERE "+check.predicate).Scan(&got); err != nil {
				t.Fatal(err)
			}
			if got != check.want {
				t.Fatalf("got %d, want %d", got, check.want)
			}
		})
	}
}

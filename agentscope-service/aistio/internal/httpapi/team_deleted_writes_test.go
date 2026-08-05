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

package httpapi

import (
	"context"
	"net/http"
	"testing"
)

// Interrupting a member's turn is not immediate, so it keeps calling its team
// tools after teardown. Those calls must not repopulate the board or mailbox of
// a team that no longer exists.
func TestWritesForDeletedTeamAreRejected(t *testing.T) {
	srv, st := newTaskNotifyServer(t)
	if err := st.Teams().Delete(context.Background(), "default", "research"); err != nil {
		t.Fatal(err)
	}

	cases := []struct {
		name string
		path string
		body map[string]any
	}{
		{
			name: "createTask",
			path: "/api/v1/teams/research/tasks",
			body: map[string]any{"subject": "late task"},
		},
		{
			name: "sendMessage",
			path: "/api/v1/teams/research/messages",
			body: map[string]any{"from": "worker-1", "to": "lead", "content": "late result"},
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			w := postAs(t, srv, tc.path, tc.body, true)
			if w.Code != http.StatusNotFound {
				t.Fatalf("status=%d, want 404; body=%s", w.Code, w.Body.String())
			}
		})
	}

	if msgs := srv.messageRouter.GetMessageHistory("default", "research", 50); len(msgs) != 0 {
		t.Fatalf("deleted team kept %d messages", len(msgs))
	}
	if tasks := srv.taskStore.List("default", "research"); len(tasks) != 0 {
		t.Fatalf("deleted team kept %d tasks", len(tasks))
	}
}

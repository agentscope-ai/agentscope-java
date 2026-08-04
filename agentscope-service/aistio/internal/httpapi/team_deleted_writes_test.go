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

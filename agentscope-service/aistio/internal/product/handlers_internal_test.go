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
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

func TestPostSessionWakeEventRetriesLeaseReleaseRace(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/sessions/session-a/events" ||
			r.Header.Get("X-Builder-Internal-Token") != "internal-token" ||
			r.Header.Get("X-Builder-Internal-User") != "owner-a" {
			t.Errorf("unexpected wake request: path=%s headers=%v", r.URL.Path, r.Header)
		}
		var payload map[string]any
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Errorf("decode payload: %v", err)
		}
		if calls.Add(1) == 1 {
			w.WriteHeader(http.StatusConflict)
			return
		}
		w.WriteHeader(http.StatusAccepted)
	}))
	t.Cleanup(server.Close)

	s := &Server{cfg: Config{DataURL: server.URL, InternalToken: "internal-token"}}
	if err := s.PostSessionWakeEvent(t.Context(), "session-a", "owner-a", "hello"); err != nil {
		t.Fatal(err)
	}
	if got := calls.Load(); got != 2 {
		t.Fatalf("wake calls=%d, want 2", got)
	}
}

func TestPostManagedToolConfirmationUsesFencedInternalEndpoint(t *testing.T) {
	approvalID, taskID, attemptID := uuid.New(), uuid.New(), uuid.New()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/internal/sessions/session-a/tool-confirmations/call-1/decision" ||
			r.Header.Get("X-Builder-Internal-Token") != "internal-token" ||
			r.Header.Get("X-Builder-Internal-User") != "owner-a" {
			t.Errorf("unexpected confirmation request: path=%s headers=%v", r.URL.Path, r.Header)
		}
		var body ManagedToolConfirmationDecision
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		if body.ApprovalID != approvalID || body.AgentTaskID != taskID || body.AttemptID != attemptID ||
			body.DecisionVersion != 2 || body.Status != "approved" || !body.Allow ||
			body.DispatchGeneration != 3 || body.TurnID != "turn-1" {
			t.Errorf("unexpected confirmation body: %+v", body)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	t.Cleanup(server.Close)
	s := &Server{cfg: Config{DataURL: server.URL, InternalToken: "internal-token"}}
	err := s.PostManagedToolConfirmation(t.Context(), "session-a", "owner-a", "call-1",
		ManagedToolConfirmationDecision{ApprovalID: approvalID, AgentTaskID: taskID,
			AttemptID: attemptID, DecisionVersion: 2, Status: "approved", Allow: true,
			DispatchGeneration: 3, TurnID: "turn-1"})
	if err != nil {
		t.Fatal(err)
	}
}

func TestPostManagedAttemptAbortUsesOldTurnFence(t *testing.T) {
	taskID, attemptID := uuid.New(), uuid.New()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		wantPath := "/api/internal/sessions/session-a/managed-attempts/" + attemptID.String() + "/abort"
		if r.URL.Path != wantPath || r.Header.Get("X-Builder-Internal-Token") != "internal-token" ||
			r.Header.Get("X-Builder-Internal-User") != "owner-a" {
			t.Errorf("unexpected abort request: path=%s headers=%v", r.URL.Path, r.Header)
		}
		var body ManagedAttemptAbort
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		if body.AgentTaskID != taskID || body.AttemptID != attemptID || body.DispatchGeneration != 7 ||
			body.TurnID != "turn-a" || body.Reason != "heartbeat_timeout" {
			t.Errorf("unexpected abort body: %+v", body)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	t.Cleanup(server.Close)
	s := &Server{cfg: Config{DataURL: server.URL, InternalToken: "internal-token"}}
	err := s.PostManagedAttemptAbort(t.Context(), "session-a", "owner-a", ManagedAttemptAbort{
		AgentTaskID: taskID, AttemptID: attemptID, DispatchGeneration: 7,
		TurnID: "turn-a", Reason: "heartbeat_timeout",
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestManagedRuntimeFenceProjectionIsMonotonic(t *testing.T) {
	taskID := uuid.New().String()
	attemptA, attemptB := uuid.New().String(), uuid.New().String()
	fenceA := ManagedRuntimeFence{AgentTaskID: taskID, AttemptID: attemptA,
		DispatchGeneration: 1, TurnID: "turn-a"}
	fenceB := ManagedRuntimeFence{AgentTaskID: taskID, AttemptID: attemptB,
		DispatchGeneration: 2, TurnID: "turn-b"}
	var session sessionRow
	if !managedRuntimeFenceCanAdvance(session, fenceA) {
		t.Fatal("empty marker rejected Attempt A")
	}
	setRuntimeMarker := func(fence ManagedRuntimeFence) {
		task, attempt, turn, generation := fence.AgentTaskID, fence.AttemptID, fence.TurnID, fence.DispatchGeneration
		session.RuntimeAgentTaskID, session.RuntimeAttemptID = &task, &attempt
		session.RuntimeTurnID, session.RuntimeDispatchGen = &turn, &generation
	}
	setRuntimeMarker(fenceA)
	if !managedRuntimeFenceCanAdvance(session, fenceB) {
		t.Fatal("newer Attempt B could not replace A marker")
	}
	setRuntimeMarker(fenceB)
	if managedRuntimeFenceCanAdvance(session, fenceA) {
		t.Fatal("delayed Attempt A could overwrite B marker")
	}
	if !managedRuntimeFenceCanAdvance(session, fenceB) {
		t.Fatal("idempotent Attempt B patch was rejected")
	}
	sameGenerationDifferentTuple := fenceB
	sameGenerationDifferentTuple.AttemptID = uuid.New().String()
	if managedRuntimeFenceCanAdvance(session, sameGenerationDifferentTuple) {
		t.Fatal("same generation with a different physical tuple was accepted")
	}
}

func TestRunningRuntimePatchClearsPreviousAttemptStopReason(t *testing.T) {
	previous := `{"code":"turn_failed"}`
	sess := sessionRow{StopReasonJSON: &previous}
	running := "running"
	if got := sessionRuntimeStopReason(sess, &running, nil); got != nil {
		t.Fatalf("running inherited previous stop reason: %#v", got)
	}
	idle := "idle"
	if got := sessionRuntimeStopReason(sess, &idle, nil); got != previous {
		t.Fatalf("idle status-only patch did not preserve stop reason: %#v", got)
	}
}

func TestManagedRuntimePatchCannotOverwriteNewerPreWakeFence(t *testing.T) {
	dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set; skipping product postgres test")
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
	sessionID := "test-runtime-fence-" + shortID("")
	now := nowMillis()
	if _, err = db.Pool.Exec(ctx, `INSERT INTO sessions
		(session_id,owner_id,agent_id,environment_id,status,stop_reason_json,version,created_at,updated_at)
		VALUES($1,'owner','agent','environment','running','{"code":"attempt_a_failed"}',1,$2,$2)`, sessionID, now); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = db.Pool.Exec(context.Background(), `DELETE FROM sessions WHERE session_id=$1`, sessionID)
	})

	taskID, attemptA, attemptB := uuid.New(), uuid.New(), uuid.New()
	server := &Server{db: db, managedRuntimeValidator: func(context.Context, string, ManagedRuntimeFence) (bool, error) {
		// This deliberately models Attempt A having passed its runtime-store
		// validation immediately before Attempt B was claimed.
		return true, nil
	}}
	if err = server.ClaimManagedRuntimeFence(ctx, sessionID, taskID, attemptA, 1, "turn-a"); err != nil {
		t.Fatal(err)
	}
	if err = server.ClaimManagedRuntimeFence(ctx, sessionID, taskID, attemptB, 2, "turn-b"); err != nil {
		t.Fatal(err)
	}
	router := gin.New()
	server.registerInternal(router)
	patch := func(fence ManagedRuntimeFence, status string) *httptest.ResponseRecorder {
		body, marshalErr := json.Marshal(map[string]any{
			"status": status, "agentTaskId": fence.AgentTaskID, "attemptId": fence.AttemptID,
			"dispatchGeneration": fence.DispatchGeneration, "turnId": fence.TurnID,
		})
		if marshalErr != nil {
			t.Fatal(marshalErr)
		}
		req := httptest.NewRequest(http.MethodPatch, "/api/internal/sessions/"+sessionID+"/runtime", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		router.ServeHTTP(response, req)
		return response
	}
	fenceA := ManagedRuntimeFence{AgentTaskID: taskID.String(), AttemptID: attemptA.String(),
		DispatchGeneration: 1, TurnID: "turn-a"}
	fenceB := ManagedRuntimeFence{AgentTaskID: taskID.String(), AttemptID: attemptB.String(),
		DispatchGeneration: 2, TurnID: "turn-b"}
	if response := patch(fenceA, "terminated"); response.Code != http.StatusConflict {
		t.Fatalf("delayed Attempt A status=%d body=%s", response.Code, response.Body.String())
	}
	var status, markerAttempt string
	var stopReason *string
	var markerGeneration int64
	if err = db.Pool.QueryRow(ctx, `SELECT status,runtime_attempt_id,runtime_dispatch_generation,stop_reason_json
		FROM sessions WHERE session_id=$1`, sessionID).Scan(&status, &markerAttempt, &markerGeneration, &stopReason); err != nil {
		t.Fatal(err)
	}
	if status != "running" || markerAttempt != attemptB.String() || markerGeneration != 2 || stopReason != nil {
		t.Fatalf("Attempt A mutated B projection: status=%s attempt=%s generation=%d stop=%v",
			status, markerAttempt, markerGeneration, stopReason)
	}
	if response := patch(fenceB, "idle"); response.Code != http.StatusOK {
		t.Fatalf("current Attempt B status=%d body=%s", response.Code, response.Body.String())
	}
}

func TestEnsureDefaultLocalEnvironmentIsConcurrentAndIdempotent(t *testing.T) {
	dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set; skipping product postgres test")
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
	ownerID := "test-default-environment-" + shortID("")
	t.Cleanup(func() {
		_, _ = db.Pool.Exec(context.Background(), `DELETE FROM environments WHERE owner_id=$1`, ownerID)
	})
	server := &Server{db: db, cfg: Config{AllowLocalEnvironment: true}}

	const callers = 8
	ids := make(chan string, callers)
	errs := make(chan error, callers)
	var wg sync.WaitGroup
	for range callers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			id, createErr := server.ensureDefaultLocalEnvironment(ctx, ownerID)
			if createErr != nil {
				errs <- createErr
				return
			}
			ids <- id
		}()
	}
	wg.Wait()
	close(ids)
	close(errs)
	for createErr := range errs {
		t.Errorf("ensure default environment: %v", createErr)
	}
	var expected string
	for id := range ids {
		if expected == "" {
			expected = id
		}
		if id != expected {
			t.Errorf("environment id=%s, want shared id %s", id, expected)
		}
	}
	var count int
	if err = db.Pool.QueryRow(ctx,
		`SELECT COUNT(*) FROM environments WHERE owner_id=$1 AND archived_at IS NULL AND lower(type)='local'`, ownerID).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Fatalf("active default environments=%d, want one", count)
	}
}

func TestEnsureManagedDefinitionBindsDefaultLocalEnvironmentWhenAllowed(t *testing.T) {
	dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set; skipping product postgres test")
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
	ownerID := "test-managed-default-" + shortID("")
	agentID := "agent-" + shortID("")
	t.Cleanup(func() {
		_, _ = db.Pool.Exec(context.Background(), `DELETE FROM agent_versions WHERE owner_id=$1`, ownerID)
		_, _ = db.Pool.Exec(context.Background(), `DELETE FROM agents WHERE owner_id=$1`, ownerID)
		_, _ = db.Pool.Exec(context.Background(), `DELETE FROM environments WHERE owner_id=$1`, ownerID)
	})
	server := &Server{db: db, cfg: Config{
		WorkspaceRoot:         t.TempDir(),
		AllowLocalEnvironment: true,
	}}

	definition, err := server.EnsureManagedDefinition(ctx, ownerID, agentID, ManagedDefinitionInput{
		Name:                        "managed-default",
		ProvisionDefaultEnvironment: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	environmentID, ok := definition["defaultEnvironmentId"].(string)
	if !ok || environmentID == "" {
		t.Fatalf("defaultEnvironmentId=%#v, want non-empty string", definition["defaultEnvironmentId"])
	}
	environment, err := server.validateEnvironmentBinding(ctx, ownerID, environmentID)
	if err != nil {
		t.Fatal(err)
	}
	if got := normalizeEnvironmentType(environment.Type); got != localEnvironmentType {
		t.Fatalf("environment type=%q, want %q", got, localEnvironmentType)
	}
	version, err := server.ManagedDefinitionVersion(ctx, ownerID, agentID, 1)
	if err != nil {
		t.Fatal(err)
	}
	snapshot, ok := version["snapshot"].(map[string]any)
	if !ok || snapshot["defaultEnvironmentId"] != environmentID {
		t.Fatalf("snapshot defaultEnvironmentId=%#v, want %q", snapshot["defaultEnvironmentId"], environmentID)
	}
}

func TestManagedDefinitionRemainsUnboundWhenLocalEnvironmentIsDisabled(t *testing.T) {
	dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set; skipping product postgres test")
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
	ownerID := "test-managed-policy-" + shortID("")
	agentID := "agent-" + shortID("")
	t.Cleanup(func() {
		_, _ = db.Pool.Exec(context.Background(), `DELETE FROM agent_versions WHERE owner_id=$1`, ownerID)
		_, _ = db.Pool.Exec(context.Background(), `DELETE FROM agents WHERE owner_id=$1`, ownerID)
		_, _ = db.Pool.Exec(context.Background(), `DELETE FROM environments WHERE owner_id=$1`, ownerID)
	})
	server := &Server{db: db, cfg: Config{WorkspaceRoot: t.TempDir()}}

	definition, err := server.EnsureManagedDefinition(ctx, ownerID, agentID, ManagedDefinitionInput{
		Name:                        "production-managed",
		ProvisionDefaultEnvironment: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	if got := definition["defaultEnvironmentId"]; got != nil {
		t.Fatalf("defaultEnvironmentId=%#v, want nil", got)
	}
	if _, err = server.resolveDefaultEnvironmentID(ctx, ownerID, agentID); !errors.Is(err, ErrLocalEnvironmentDisabled) {
		t.Fatalf("resolve error=%v, want ErrLocalEnvironmentDisabled", err)
	}
	var environmentCount int
	if err = db.Pool.QueryRow(ctx, `SELECT COUNT(*) FROM environments WHERE owner_id=$1`, ownerID).Scan(&environmentCount); err != nil {
		t.Fatal(err)
	}
	if environmentCount != 0 {
		t.Fatalf("environment count=%d, want zero", environmentCount)
	}
}

func TestLocalEnvironmentCannotCreateNewBindingWhenDisabled(t *testing.T) {
	dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set; skipping product postgres test")
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
	ownerID := "test-local-policy-" + shortID("")
	environmentID := "env_" + shortID("")
	t.Cleanup(func() {
		_, _ = db.Pool.Exec(context.Background(), `DELETE FROM environments WHERE owner_id=$1`, ownerID)
	})
	now := nowMillis()
	if _, err = db.Pool.Exec(ctx, `INSERT INTO environments
		(environment_id,owner_id,name,type,config_json,api_key_hash,created_at,updated_at)
		VALUES($1,$2,'existing-local','local','{}',$3,$4,$4)`, environmentID, ownerID, sha256Hex("key"), now); err != nil {
		t.Fatal(err)
	}
	server := &Server{db: db, cfg: Config{AllowLocalEnvironment: false}}
	if _, err = server.validateEnvironmentBinding(ctx, ownerID, environmentID); !errors.Is(err, ErrLocalEnvironmentDisabled) {
		t.Fatalf("binding error=%v, want ErrLocalEnvironmentDisabled", err)
	}
	if _, err = server.ensureDefaultLocalEnvironment(ctx, ownerID); !errors.Is(err, ErrLocalEnvironmentDisabled) {
		t.Fatalf("default error=%v, want ErrLocalEnvironmentDisabled", err)
	}
}

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
package httpapi

import (
	"bytes"
	"encoding/json"
	"fmt"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/artifact"
	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeportal"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestPortalNativeArtifactsUseBoundedRecoveryAndPrivateTurnBinding(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		t.Run(driver, func(t *testing.T) {
			cfg := store.DefaultConfig()
			if driver == store.DriverPostgres {
				cfg = portalNativePostgresFixture(t)
			}
			st, err := store.Open(t.Context(), cfg)
			if err != nil {
				t.Fatal(err)
			}
			defer st.Close()
			provider := &artifact.LocalProvider{Root: t.TempDir()}
			s := NewServer(ServerOptions{RuntimeOnly: true, Store: st, InternalToken: "artifact-secret", ArtifactProvider: provider})
			sessionID, turnID := uuid.NewString(), uuid.NewString()
			scope := runtimeprotocol.Context{Version: 1, Tenant: "t", Namespace: "n", SessionID: sessionID, CommandID: "bootstrap", TraceID: "fixture", Actor: map[string]any{"user": "owner"}, PermissionExpiresAt: time.Now().Add(time.Minute).UnixMilli()}
			configuration := json.RawMessage(`{"session":{"ownerId":"owner"}}`)
			revision, _ := runtimeprotocol.Digest(configuration)
			bootstrap := runtimeprotocol.RuntimeSessionBootstrap{Context: scope, RuntimeKind: "team", ConfigurationRevision: revision, Configuration: configuration}
			digest, _ := runtimeprotocol.BootstrapDigest(bootstrap)
			if _, err = st.RuntimePortal().Bootstrap(t.Context(), bootstrap, digest, time.Now()); err != nil {
				t.Fatal(err)
			}
			scope.CommandID, scope.TurnID = turnID, &turnID
			input := map[string]any{"message": "deliver"}
			inputDigest, _ := runtimeprotocol.Digest(input)
			command := runtimeprotocol.TurnCommand{Context: scope, AdmissionSequence: 1, Input: input, InputDigest: inputDigest, IdempotencyKey: "turn"}
			digest, _ = runtimeprotocol.TurnDigest(command)
			turn, err := st.RuntimePortal().AcceptTurn(t.Context(), command, digest, time.Now())
			if err != nil {
				t.Fatal(err)
			}
			var first *model.Artifact
			content := bytes.Repeat([]byte("binary\x00"), 400000)
			for i := 0; i < 35; i++ {
				key := fmt.Sprintf("artifact/%d", i)
				info, err := provider.Put(t.Context(), key, bytes.NewReader(content))
				if err != nil {
					t.Fatal(err)
				}
				owner := turnID
				if i == 34 {
					owner = "different-turn"
				}
				item, err := st.Collaboration().CreateArtifact(t.Context(), &model.Artifact{Tenant: "t", Namespace: "n", StorageProvider: provider.Name(), StorageKey: key,
					Filename: "result.bin", ContentType: "application/octet-stream", SizeBytes: info.Size, Checksum: info.Checksum, PortalCommandID: owner}, nil)
				if err != nil {
					t.Fatal(err)
				}
				if i == 0 {
					first = item
				}
			}
			b := &portalNativeBackend{server: s}
			cursor := portalNativeCursor{}
			total := 0
			for round := 0; round < 3; round++ {
				var progress runtimeportal.Progress
				drained, err := b.observeArtifacts(t.Context(), *turn, &cursor, &progress)
				if err != nil {
					t.Fatal(err)
				}
				if len(progress.Facts) > 16 || drained != (round == 2) {
					t.Fatalf("round=%d facts=%d drained=%t", round, len(progress.Facts), drained)
				}
				for _, fact := range progress.Facts {
					if fact.Type != "artifact.published" || !strings.HasPrefix(fmt.Sprint(fact.Payload["download_url"]), "/api/v1/agent-sessions/"+sessionID+"/turns/"+turnID+"/artifacts/") {
						t.Fatalf("fact=%+v", fact)
					}
				}
				total += len(progress.Facts)
				raw, _ := json.Marshal(cursor)
				cursor = portalNativeCursor{}
				if err = json.Unmarshal(raw, &cursor); err != nil {
					t.Fatal(err)
				}
			}
			if total != 34 {
				t.Fatalf("facts=%d", total)
			}
			turn.Generation++
			var progress runtimeportal.Progress
			if _, err = b.observeArtifacts(t.Context(), *turn, &cursor, &progress); err != nil || len(progress.Facts) != 0 {
				t.Fatalf("writer replay=%+v %v", progress, err)
			}
			if _, err = st.Collaboration().ListRuntimeArtifacts(t.Context(), store.RuntimeArtifactFilter{Tenant: "t", Namespace: "n", Limit: 16}); err == nil {
				t.Fatal("unscoped scan accepted")
			}
			download := func(session, token string) *httptest.ResponseRecorder {
				req := httptest.NewRequest("GET", "/api/internal/runtime/sessions/"+session+"/turns/"+turnID+"/artifacts/"+first.ID.String()+"/content", nil)
				req.Header.Set("X-Builder-Internal-Token", token)
				out := httptest.NewRecorder()
				s.router.ServeHTTP(out, req)
				return out
			}
			if out := download(sessionID, "wrong"); out.Code != 401 {
				t.Fatalf("auth=%d", out.Code)
			}
			if out := download("foreign-session", "artifact-secret"); out.Code != 404 {
				t.Fatalf("session=%d", out.Code)
			}
			if out := download(sessionID, "artifact-secret"); out.Code != 200 || !bytes.Equal(out.Body.Bytes(), content) || out.Header().Get("ETag") == "" {
				t.Fatalf("content=%d length=%d", out.Code, out.Body.Len())
			}
			if _, err = provider.Put(t.Context(), first.StorageKey, strings.NewReader("changed")); err != nil {
				t.Fatal(err)
			}
			if out := download(sessionID, "artifact-secret"); out.Code != 422 {
				t.Fatalf("checksum=%d", out.Code)
			}
		})
	}
}

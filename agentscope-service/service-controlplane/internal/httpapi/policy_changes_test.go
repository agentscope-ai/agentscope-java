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
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"testing"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
)

func TestPolicyChangesKeepScopeAndRequireTheCurrentClaim(t *testing.T) {
	st, err := store.Open(t.Context(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	app, err := st.Applications().Create(t.Context(), &model.Application{Tenant: "t", Namespace: "n", Name: "app", OwnerUserID: "owner", Status: "active"})
	if err != nil {
		t.Fatal(err)
	}
	s := NewServer(ServerOptions{Store: st, InternalToken: "policy-fixture", LocalDev: false})
	initial, err := st.PolicyChanges().Claim(t.Context(), time.Now(), time.Second, 100)
	if err != nil {
		t.Fatal(err)
	}
	for _, v := range initial {
		if err = st.PolicyChanges().Acknowledge(t.Context(), v.ID, v.ClaimToken); err != nil {
			t.Fatal(err)
		}
	}
	app.Status = "disabled"
	if _, err = st.Applications().Update(t.Context(), app, app.Version); err != nil {
		t.Fatal(err)
	}
	if _, written, err := st.KV().PutIfVersion(t.Context(), "t", "application-credentials/"+app.ID.String(), "key", json.RawMessage(`{"status":"revoked"}`), 0); err != nil || !written {
		t.Fatalf("credential change: %v %v", written, err)
	}
	send := func(path, token string, payload any) *httptest.ResponseRecorder {
		raw, _ := json.Marshal(payload)
		r := httptest.NewRequest("POST", "/api/internal/policy-changes/"+path, bytes.NewReader(raw))
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("X-Builder-Internal-Token", token)
		w := httptest.NewRecorder()
		s.router.ServeHTTP(w, r)
		return w
	}
	if w := send("claim", "wrong", map[string]any{}); w.Code != 401 {
		t.Fatalf("invalid identity: %d", w.Code)
	}
	w := send("claim", "policy-fixture", map[string]any{})
	if w.Code != 200 {
		t.Fatalf("claim: %d %s", w.Code, w.Body)
	}
	var batch struct {
		Changes []map[string]any `json:"changes"`
	}
	if err = json.Unmarshal(w.Body.Bytes(), &batch); err != nil || len(batch.Changes) != 2 {
		t.Fatalf("changes: %s %v", w.Body, err)
	}
	for _, change := range batch.Changes {
		if change["tenant"] != "t" || change["application_id"] != app.ID.String() {
			t.Fatalf("wrong invalidation scope: %+v", change)
		}
		original := change["claim_token"]
		change["claim_token"] = "00000000-0000-0000-0000-000000000000"
		if w = send("ack", "policy-fixture", change); w.Code != 409 {
			t.Fatalf("stale ack: %d", w.Code)
		}
		change["claim_token"] = original
		for range 2 {
			if w = send("ack", "policy-fixture", change); w.Code != 204 {
				t.Fatalf("ack: %d", w.Code)
			}
		}
	}
	w = send("claim", "policy-fixture", map[string]any{})
	if err = json.Unmarshal(w.Body.Bytes(), &batch); err != nil || len(batch.Changes) != 0 {
		t.Fatalf("redelivered: %s %v", w.Body, err)
	}
}

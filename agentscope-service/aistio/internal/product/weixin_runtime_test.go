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
	"net/http"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestWeixinRuntimeReportsRejectStaleOwnersAndCredentials(t *testing.T) {
	f := newWeixinFixture(t)
	path := f.authorize()
	f.request("POST", path+"/complete", nil, 200)

	report := func(generation, sequence, revision int64, holder string, started bool, errorCode string) {
		t.Helper()
		body := gin.H{"channelId": f.channel, "accountId": f.account, "credentialRevision": revision,
			"leaseGeneration": generation, "leaseHolder": holder, "sequence": sequence, "started": started}
		if errorCode != "" {
			body["error"] = errorCode
		}
		out := f.call("POST", "/api/internal/channels/runtime", gin.H{"channels": []any{body}},
			map[string]string{"X-Builder-Internal-Token": "test-internal"})
		if out.Code != http.StatusNoContent {
			t.Fatalf("report failed: %d", out.Code)
		}
	}
	assertState := func(started bool, errorCode string) {
		t.Helper()
		ch, err := f.s.loadChannel(t.Context(), f.channel)
		if err != nil {
			t.Fatal(err)
		}
		if ch.RuntimeStarted != started || deref(ch.RuntimeError) != errorCode {
			t.Fatalf("unexpected runtime state: started=%v error=%s", ch.RuntimeStarted, deref(ch.RuntimeError))
		}
	}

	report(2, 3, 1, "active", true, "")
	assertState(true, "")
	report(1, 100, 1, "old", false, "CREDENTIAL_REJECTED")
	report(2, 2, 1, "active", false, "TRANSIENT_FAILURE")
	report(2, 4, 1, "other", false, "CREDENTIAL_REJECTED")
	report(0, 0, 1, "", false, "")
	assertState(true, "")

	report(2, 4, 1, "active", false, "CREDENTIAL_REJECTED")
	assertState(false, "CREDENTIAL_REJECTED")
	status := f.request("GET", f.root()+"/status", nil, 200)
	if status["status"] != "REAUTH_REQUIRED" {
		t.Fatalf("status=%v", status["status"])
	}
	if status["accountId"] != nil {
		t.Fatalf("accountId=%v", status["accountId"])
	}

	// Reauthorization resets the report fence but rejects every report for the old credential.
	path = f.authorize()
	f.request("POST", path+"/complete", nil, 200)
	report(100, 100, 1, "old", false, "CREDENTIAL_REJECTED")
	assertState(false, "")
	report(3, 1, 2, "new", true, "")
	assertState(true, "")
	status = f.request("GET", f.root()+"/status", nil, 200)
	if status["accountId"] != f.account {
		t.Fatalf("connected accountId=%v", status["accountId"])
	}
	f.request("POST", f.root()+"/disconnect", nil, 200)
	report(3, 2, 2, "new", true, "")
	assertState(false, "")
}

func TestWeixinRuntimeSurfacesAStartFailureWithoutLeaseAuthority(t *testing.T) {
	f := newWeixinFixture(t)
	path := f.authorize()
	f.request("POST", path+"/complete", nil, 200)

	report := func(body gin.H) {
		t.Helper()
		out := f.call("POST", "/api/internal/channels/runtime", gin.H{"channels": []any{body}},
			map[string]string{"X-Builder-Internal-Token": "test-internal"})
		if out.Code != http.StatusNoContent {
			t.Fatalf("report failed: %d", out.Code)
		}
	}
	assertChannelState := func(started bool, errorCode string) {
		t.Helper()
		ch, err := f.s.loadChannel(t.Context(), f.channel)
		if err != nil {
			t.Fatal(err)
		}
		if ch.RuntimeStarted != started || deref(ch.RuntimeError) != errorCode {
			t.Fatalf("unexpected runtime state: started=%v error=%s", ch.RuntimeStarted, deref(ch.RuntimeError))
		}
	}
	failureReport := gin.H{"channelId": f.channel, "accountId": f.account, "credentialRevision": int64(1),
		"started": false, "error": "credentialRevision is missing or not a number"}

	// Nobody holds the lease yet: a channel that failed to build reports the failure and it lands,
	// instead of being dropped like a healthy standby.
	report(failureReport)
	assertChannelState(false, "credentialRevision is missing or not a number")

	// The active replica claims the lease and reports healthy.
	report(gin.H{"channelId": f.channel, "accountId": f.account, "credentialRevision": int64(1),
		"leaseGeneration": int64(2), "leaseHolder": "active", "sequence": int64(3), "started": true})
	assertChannelState(true, "")

	// A lease-less report must not flap that state: a replica whose channel failed to build sees no
	// lease of its own, but the replica that holds the lease is the authority.
	report(failureReport)
	assertChannelState(true, "")

	// ... and it must not touch the lease authority the active replica owns either.
	var holder string
	var generation, sequence int64
	if err := f.s.db.Pool.QueryRow(t.Context(),
		`SELECT runtime_lease_holder, runtime_lease_generation, runtime_report_sequence
		 FROM weixin_connections WHERE channel_id=$1`,
		f.channel).Scan(&holder, &generation, &sequence); err != nil {
		t.Fatal(err)
	}
	if holder != "active" || generation != 2 || sequence != 3 {
		t.Fatalf("lease authority changed: holder=%q generation=%d sequence=%d", holder, generation, sequence)
	}

	// A lease-less report carrying no error is still a healthy standby and stays ignored.
	report(gin.H{"channelId": f.channel, "accountId": f.account, "credentialRevision": int64(1),
		"started": true})
	assertChannelState(true, "")
}

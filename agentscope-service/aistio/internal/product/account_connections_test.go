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
	"strings"
	"testing"
)

func TestPersonalChannelIdentityIsolationAndAccountDisable(t *testing.T) {
	f := channelSetup(t)
	f.s.registerAccountManagement(f.router)
	w := f.request(t, "GET", "/api/user/channel-connections", nil, f.user)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "sender-a") || strings.Contains(w.Body.String(), "sender-b") || strings.Contains(w.Body.String(), "test-token") {
		t.Fatalf("personal projection: %d %s", w.Code, w.Body.String())
	}
	foreign := map[string]string{"channelId": f.ch.ChannelID, "accountId": "org", "senderId": "sender-b"}
	w = f.request(t, "DELETE", "/api/user/channel-connections", foreign, f.user)
	if w.Code != 204 {
		t.Fatal(w.Code, w.Body.String())
	}
	var exists bool
	if err := f.s.db.Pool.QueryRow(t.Context(), `SELECT EXISTS(SELECT 1 FROM channel_identities WHERE channel_id=$1 AND sender_id='sender-b')`, f.ch.ChannelID).Scan(&exists); err != nil || !exists {
		t.Fatal("foreign identity was removed", err)
	}
	if _, _, err := f.s.channelActor(t.Context(), f.ch, f.other, true); err != nil {
		t.Fatal(err)
	}
	if _, err := f.s.db.Pool.Exec(t.Context(), `UPDATE users SET disabled=true WHERE user_id=$1`, f.other); err != nil {
		t.Fatal(err)
	}
	if _, _, err := f.s.channelActor(t.Context(), f.ch, f.other, true); err == nil {
		t.Fatal("disabled account can submit IM work")
	}
	if _, _, err := f.s.channelActor(t.Context(), f.ch, f.other, false); err == nil {
		t.Fatal("disabled account can read IM work")
	}
	own := map[string]string{"channelId": f.ch.ChannelID, "accountId": "org", "senderId": "sender-a"}
	w = f.request(t, "DELETE", "/api/user/channel-connections", own, f.user)
	if w.Code != 204 {
		t.Fatal(w.Code, w.Body.String())
	}
	w = f.request(t, "GET", "/api/user/channel-connections", nil, f.user)
	if w.Code != 200 || strings.Contains(w.Body.String(), "sender-a") {
		t.Fatal("identity remained linked", w.Body.String())
	}
}

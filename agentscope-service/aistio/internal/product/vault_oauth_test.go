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
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestOAuthRefreshRotatesTokensAndPreservesEncryptedPayloadShape(t *testing.T) {
	called := 0
	provider := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called++
		if err := r.ParseForm(); err != nil {
			t.Error(err)
		}
		if r.Form.Get("refresh_token") != "old-refresh" || r.Form.Get("client_secret") != "client-secret" {
			t.Error("incorrect refresh form")
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"token_type":"Bearer"}`))
	}))
	defer provider.Close()
	payload := map[string]any{"access_token": "expired", "expires_at": "2020-01-01T00:00:00Z", "refresh": map[string]any{
		"token_endpoint": provider.URL, "client_id": "client", "refresh_token": "old-refresh",
		"token_endpoint_auth": map[string]string{"type": "client_secret_post", "client_secret": "client-secret"},
	}}
	secret, _ := json.Marshal(payload)
	now := time.Now()
	updated, changed, err := refreshOAuthSecret(context.Background(), string(secret), now, provider.Client())
	if err != nil || !changed || called != 1 {
		t.Fatalf("refresh changed=%v calls=%d err=%v", changed, called, err)
	}
	if !strings.Contains(updated, "new-refresh") || !strings.Contains(updated, "new-access") {
		t.Fatal("tokens were not replaced")
	}
	_, changed, err = refreshOAuthSecret(context.Background(), updated, now, provider.Client())
	if err != nil || changed || called != 1 {
		t.Fatal("unexpired token refreshed unnecessarily")
	}
}

func TestOAuthErrorsDoNotEchoProviderBodyOrSecrets(t *testing.T) {
	provider := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(401)
		_, _ = w.Write([]byte("sensitive-provider-detail"))
	}))
	defer provider.Close()
	secret := mustJSON(map[string]any{"access_token": "old", "expires_at": "2020-01-01T00:00:00Z", "refresh": map[string]any{
		"token_endpoint": provider.URL, "client_id": "client", "refresh_token": "secret-refresh",
	}})
	_, _, err := refreshOAuthSecret(context.Background(), secret, time.Now(), provider.Client())
	if err == nil || strings.Contains(err.Error(), "sensitive-provider-detail") || strings.Contains(err.Error(), "secret-refresh") {
		t.Fatal("unsafe OAuth error")
	}
}

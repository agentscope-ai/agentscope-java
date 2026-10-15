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
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// OAuth secrets remain encrypted at rest. Refresh is serialized by credential row across CP replicas.
func (s *Server) refreshOAuthCredential(ctx context.Context, credentialID, ownerID string) (string, int64, error) {
	tx, err := s.db.Pool.Begin(ctx)
	if err != nil {
		return "", 0, err
	}
	defer tx.Rollback(ctx)
	var ciphertext []byte
	var revision int64
	err = tx.QueryRow(ctx, `SELECT c.ciphertext,c.revision FROM vault_credentials c JOIN vaults v ON v.vault_id=c.vault_id
        WHERE c.credential_id=$1 AND v.owner_id=$2 AND v.archived_at IS NULL FOR UPDATE OF c`, credentialID, ownerID).Scan(&ciphertext, &revision)
	if err != nil {
		return "", 0, err
	}
	secret, err := decryptAESGCM(s.vaultKey, ciphertext)
	if err != nil {
		return "", 0, fmt.Errorf("OAuth credential unavailable")
	}
	updated, changed, err := refreshOAuthSecret(ctx, secret, time.Now(), &http.Client{
		Timeout:       10 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	})
	if err != nil {
		return "", 0, err
	}
	if changed {
		ciphertext, err = encryptAESGCM(s.vaultKey, updated)
		if err != nil {
			return "", 0, fmt.Errorf("OAuth credential encryption failed")
		}
		revision++
		if _, err = tx.Exec(ctx, `UPDATE vault_credentials SET ciphertext=$1,revision=$2 WHERE credential_id=$3`, ciphertext, revision, credentialID); err != nil {
			return "", 0, err
		}
	}
	if err = tx.Commit(ctx); err != nil {
		return "", 0, err
	}
	return updated, revision, nil
}

func refreshOAuthSecret(ctx context.Context, secret string, now time.Time, client *http.Client) (string, bool, error) {
	var payload map[string]any
	if json.Unmarshal([]byte(secret), &payload) != nil {
		return "", false, fmt.Errorf("invalid OAuth secret JSON")
	}
	token, _ := payload["access_token"].(string)
	if token == "" {
		return "", false, fmt.Errorf("OAuth access_token is missing")
	}
	rawExpiry, ok := payload["expires_at"]
	if !ok {
		return secret, false, nil
	}
	var expires time.Time
	switch expiry := rawExpiry.(type) {
	case string:
		var err error
		expires, err = time.Parse(time.RFC3339, expiry)
		if err != nil {
			return "", false, fmt.Errorf("invalid OAuth expires_at")
		}
	case float64:
		expires = time.Unix(int64(expiry), 0)
	default:
		return "", false, fmt.Errorf("invalid OAuth expires_at")
	}
	if expires.After(now.Add(30 * time.Second)) {
		return secret, false, nil
	}
	refresh, ok := payload["refresh"].(map[string]any)
	if !ok {
		return "", false, fmt.Errorf("OAuth token expired; reauthorization required")
	}
	endpoint, _ := refresh["token_endpoint"].(string)
	parsed, err := url.Parse(endpoint)
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" || parsed.User != nil || parsed.Fragment != "" {
		return "", false, fmt.Errorf("OAuth token endpoint must be HTTPS")
	}
	refreshToken, _ := refresh["refresh_token"].(string)
	clientID, _ := refresh["client_id"].(string)
	if refreshToken == "" || clientID == "" {
		return "", false, fmt.Errorf("OAuth refresh configuration is incomplete")
	}
	form := url.Values{"grant_type": {"refresh_token"}, "refresh_token": {refreshToken}, "client_id": {clientID}}
	if resource, ok := refresh["resource"].(string); ok && resource != "" {
		form.Set("resource", resource)
	}
	if scope, ok := refresh["scope"].(string); ok && scope != "" {
		form.Set("scope", scope)
	}
	auth, _ := refresh["token_endpoint_auth"].(map[string]any)
	authType, _ := auth["type"].(string)
	clientSecret, _ := auth["client_secret"].(string)
	switch authType {
	case "", "none":
	case "client_secret_post":
		if clientSecret == "" {
			return "", false, fmt.Errorf("OAuth client secret is missing")
		}
		form.Set("client_secret", clientSecret)
	case "client_secret_basic":
		if clientSecret == "" {
			return "", false, fmt.Errorf("OAuth client secret is missing")
		}
	default:
		return "", false, fmt.Errorf("unsupported OAuth endpoint authentication")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return "", false, fmt.Errorf("invalid OAuth refresh request")
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	if authType == "client_secret_basic" {
		req.SetBasicAuth(url.QueryEscape(clientID), url.QueryEscape(clientSecret))
	}
	resp, err := client.Do(req)
	if err != nil {
		return "", false, fmt.Errorf("OAuth refresh connection failed")
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", false, fmt.Errorf("OAuth refresh rejected (HTTP %d)", resp.StatusCode)
	}
	var result struct {
		AccessToken  string `json:"access_token"`
		RefreshToken string `json:"refresh_token"`
		ExpiresIn    int64  `json:"expires_in"`
		TokenType    string `json:"token_type"`
	}
	if json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&result) != nil || result.AccessToken == "" || result.ExpiresIn <= 0 || result.ExpiresIn > 315360000 {
		return "", false, fmt.Errorf("invalid OAuth refresh response")
	}
	if result.TokenType != "" && !strings.EqualFold(result.TokenType, "Bearer") {
		return "", false, fmt.Errorf("unsupported OAuth token type")
	}
	payload["access_token"] = result.AccessToken
	payload["expires_at"] = now.Add(time.Duration(result.ExpiresIn) * time.Second).UTC().Format(time.RFC3339)
	if result.RefreshToken != "" {
		refresh["refresh_token"] = result.RefreshToken
	}
	encoded, err := json.Marshal(payload)
	if err != nil {
		return "", false, fmt.Errorf("cannot encode OAuth credential")
	}
	return string(encoded), true, nil
}

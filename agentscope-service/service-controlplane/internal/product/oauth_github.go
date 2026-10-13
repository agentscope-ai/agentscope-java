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
	"context"
	"encoding/json"
	"errors"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
)

// The destination and issuer are fixed. A namespace user cannot redirect the
// administrator's client secret or a GitHub account token to a custom endpoint.
const githubMCPURL = "https://api.githubcopilot.com/mcp/"

type githubAppSettings struct {
	Enabled      bool   `json:"enabled"`
	ClientID     string `json:"clientId"`
	ClientSecret string `json:"clientSecret,omitempty"`
	Scope        string `json:"scope"`
}

func (a githubAppSettings) oauthSettings() oauthSettings {
	return oauthSettings{AuthorizationEndpoint: "https://github.com/login/oauth/authorize", TokenEndpoint: "https://github.com/login/oauth/access_token", ClientID: a.ClientID, ClientSecret: a.ClientSecret, AuthMethod: "client_secret_post", Scope: a.Scope}
}

func isGitHubMCPEndpoint(endpoint string) bool {
	return endpoint == githubMCPURL || endpoint == strings.TrimSuffix(githubMCPURL, "/")
}

func (s *Server) registerGitHubOAuth(r gin.IRouter) {
	r.GET("/api/admin/integrations/github", s.githubAppGet)
	r.PUT("/api/admin/integrations/github", s.githubAppSave)
	r.GET("/api/oauth/providers/github", s.githubProviderStatus)
	r.POST("/api/vaults/:id/oauth-connections/:connectionId/verify", s.verifyGitHubConnection)
}

func (s *Server) loadGitHubApp(ctx context.Context) (githubAppSettings, int64, error) {
	var a githubAppSettings
	var ciphertext []byte
	var revision int64
	err := s.db.Pool.QueryRow(ctx, `SELECT settings_ciphertext,revision FROM oauth_provider_apps WHERE provider='github'`).Scan(&ciphertext, &revision)
	if err != nil {
		return a, 0, err
	}
	raw, err := decryptAESGCM(s.vaultKey, ciphertext)
	if err == nil {
		err = json.Unmarshal([]byte(raw), &a)
	}
	return a, revision, err
}

func (s *Server) githubProviderStatus(c *gin.Context) {
	a, _, err := s.loadGitHubApp(c.Request.Context())
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		writeErr(c, 500, "Cannot load GitHub integration")
		return
	}
	c.Header("Cache-Control", "no-store")
	c.JSON(200, gin.H{"provider": "github", "configured": err == nil && a.Enabled && a.ClientID != "" && a.ClientSecret != "", "scope": a.Scope})
}

func (s *Server) githubAppGet(c *gin.Context) {
	if !s.requireAdmin(c) {
		return
	}
	a, revision, err := s.loadGitHubApp(c.Request.Context())
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		writeErr(c, 500, "Cannot load GitHub application")
		return
	}
	origin, err := s.oauthOrigin(c)
	if err != nil {
		writeErr(c, 400, err.Error())
		return
	}
	c.Header("Cache-Control", "no-store")
	c.JSON(200, gin.H{"enabled": a.Enabled, "clientId": a.ClientID, "hasClientSecret": a.ClientSecret != "", "scope": a.Scope, "revision": revision, "callbackUrl": origin + oauthCallbackPrefix + "github"})
}

func (s *Server) githubAppSave(c *gin.Context) {
	if !s.requireAdmin(c) {
		return
	}
	var req struct {
		githubAppSettings
		Revision int64 `json:"revision"`
	}
	if c.ShouldBindJSON(&req) != nil {
		writeErr(c, 400, "Invalid GitHub application settings")
		return
	}
	if _, err := s.oauthOrigin(c); err != nil {
		writeErr(c, 400, err.Error())
		return
	}
	req.ClientID = strings.TrimSpace(req.ClientID)
	req.Scope = strings.Join(strings.Fields(req.Scope), " ")
	if len(req.ClientID) > 256 || len(req.ClientSecret) > 4096 || len(req.Scope) > 2048 || strings.ContainsAny(req.ClientID+req.ClientSecret, "\r\n") {
		writeErr(c, 400, "Invalid GitHub application settings")
		return
	}
	old, revision, err := s.loadGitHubApp(c.Request.Context())
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		writeErr(c, 500, "Cannot load GitHub application")
		return
	}
	if revision != req.Revision {
		writeErr(c, 409, "GitHub application changed; reload before saving")
		return
	}
	if req.ClientSecret == "" && req.ClientID == old.ClientID {
		req.ClientSecret = old.ClientSecret
	}
	if req.Enabled && (req.ClientID == "" || req.ClientSecret == "") {
		writeErr(c, 400, "Client ID and client secret are required to enable GitHub")
		return
	}
	if req.ClientID != old.ClientID && req.ClientID != "" && req.ClientSecret == "" {
		writeErr(c, 400, "Re-enter the secret when changing the client ID")
		return
	}
	ct, err := encryptAESGCM(s.vaultKey, mustJSON(req.githubAppSettings))
	if err != nil {
		writeErr(c, 500, "Cannot encrypt GitHub application")
		return
	}
	tx, err := s.db.Pool.Begin(c.Request.Context())
	if err != nil {
		writeErr(c, 500, "Cannot save GitHub application")
		return
	}
	defer tx.Rollback(c.Request.Context())
	tag, err := tx.Exec(c.Request.Context(), `INSERT INTO oauth_provider_apps(provider,settings_ciphertext,revision,updated_at) VALUES('github',$1,1,$2) ON CONFLICT(provider) DO UPDATE SET settings_ciphertext=EXCLUDED.settings_ciphertext,revision=oauth_provider_apps.revision+1,updated_at=EXCLUDED.updated_at WHERE oauth_provider_apps.revision=$3`, ct, nowMillis(), req.Revision)
	if err != nil || tag.RowsAffected() != 1 {
		writeErr(c, 409, "GitHub application changed; reload before saving")
		return
	}
	// Share-locking the provider in startOAuth closes the read-config/start-flow race.
	// Successful prior grants remain usable; in-flight consent must use the new app settings.
	_, err = tx.Exec(c.Request.Context(), `UPDATE mcp_oauth_connections SET generation=generation+1 WHERE provider='github'`)
	if err != nil || tx.Commit(c.Request.Context()) != nil {
		writeErr(c, 500, "Cannot save GitHub application")
		return
	}
	s.githubAppGet(c)
}

func (s *Server) createGitHubConnection(c *gin.Context, req oauthConnectionRequest) {
	if req.Provider != "github" || !isGitHubMCPEndpoint(req.Endpoint) || !mcpConnectionName.MatchString(req.ServerName) || strings.Contains(req.ServerName, "__") {
		writeErr(c, 400, "The GitHub provider requires the official GitHub MCP endpoint and a valid connection name")
		return
	}
	a, _, err := s.loadGitHubApp(c.Request.Context())
	if err != nil || !a.Enabled {
		writeErr(c, 409, "GitHub integration is not configured or is disabled; contact a platform administrator")
		return
	}
	if _, err := s.oauthOrigin(c); err != nil {
		writeErr(c, 400, err.Error())
		return
	}
	// Never copy administrator secrets into the editable connection definition.
	ct, err := encryptAESGCM(s.vaultKey, "{}")
	if err != nil {
		writeErr(c, 500, "Cannot create GitHub connection")
		return
	}
	id := "oa_" + oauthRandom()
	_, err = s.db.Pool.Exec(c.Request.Context(), `INSERT INTO mcp_oauth_connections(connection_id,vault_id,owner_id,server_name,endpoint,settings_ciphertext,provider,created_at,updated_at) VALUES($1,$2,$3,$4,$5,$6,'github',$7,$7)`, id, c.Param("id"), currentResourceOwner(c), req.ServerName, req.Endpoint, ct, nowMillis())
	if err != nil {
		writeErr(c, 409, "A connection already exists for this endpoint in the selected Vault")
		return
	}
	c.Params = append(c.Params, gin.Param{Key: "connectionId", Value: id})
	v, err := s.oauthConnection(c)
	if err != nil {
		writeErr(c, 500, "Cannot load GitHub connection")
		return
	}
	dto, err := s.oauthConnectionJSON(c, v)
	if err != nil {
		writeErr(c, 500, "Cannot load GitHub connection")
		return
	}
	c.Header("Cache-Control", "no-store")
	c.JSON(201, dto)
}

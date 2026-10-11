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
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
)

const oauthCallbackPrefix = "/api/oauth/mcp/callback/"
const oauthFlowLifetime = 10 * time.Minute

type oauthSettings struct {
	AuthorizationEndpoint string            `json:"authorizationEndpoint"`
	TokenEndpoint         string            `json:"tokenEndpoint"`
	ClientID              string            `json:"clientId"`
	ClientSecret          string            `json:"clientSecret,omitempty"`
	AuthMethod            string            `json:"authMethod"`
	Scope                 string            `json:"scope"`
	Resource              string            `json:"resource,omitempty"`
	Issuer                string            `json:"issuer,omitempty"`
	AuthorizationParams   map[string]string `json:"authorizationParams,omitempty"`
}
type oauthConnectionRequest struct {
	Provider   string `json:"provider,omitempty"`
	ServerName string `json:"serverName"`
	Endpoint   string `json:"endpoint"`
	oauthSettings
}
type oauthConnection struct {
	Provider, AccountJSON                      string
	ID, VaultID, OwnerID, ServerName, Endpoint string
	Ciphertext                                 []byte
	CredentialID                               *string
	Generation, CreatedAt, UpdatedAt           int64
}

const oauthConnectionSelect = `SELECT connection_id,vault_id,owner_id,server_name,endpoint,settings_ciphertext,credential_id,generation,created_at,updated_at,provider,account_json FROM mcp_oauth_connections`

func scanOAuthConnection(row pgx.Row) (oauthConnection, error) {
	var v oauthConnection
	err := row.Scan(&v.ID, &v.VaultID, &v.OwnerID, &v.ServerName, &v.Endpoint, &v.Ciphertext, &v.CredentialID, &v.Generation, &v.CreatedAt, &v.UpdatedAt, &v.Provider, &v.AccountJSON)
	return v, err
}
func (s *Server) oauthConnection(c *gin.Context) (oauthConnection, error) {
	if !s.ownVault(c, c.Param("id")) {
		return oauthConnection{}, fmt.Errorf("vault unavailable")
	}
	return scanOAuthConnection(s.db.Pool.QueryRow(c.Request.Context(), oauthConnectionSelect+` WHERE connection_id=$1 AND vault_id=$2 AND owner_id=$3`, c.Param("connectionId"), c.Param("id"), currentResourceOwner(c)))
}
func (s *Server) registerOAuth(r gin.IRouter) {
	s.registerGitHubOAuth(r)
	r.GET("/api/vaults/:id/oauth-connections", s.listOAuthConnections)
	r.POST("/api/vaults/:id/oauth-connections", s.createOAuthConnection)
	r.PATCH("/api/vaults/:id/oauth-connections/:connectionId", s.updateOAuthConnection)
	r.POST("/api/vaults/:id/oauth-connections/:connectionId/authorize", s.startOAuth)
	r.POST("/api/vaults/:id/oauth-connections/:connectionId/disconnect", s.disconnectOAuth)
	r.GET("/api/vaults/:id/oauth-connections/:connectionId/flows/:flowId", s.oauthFlowStatus)
	r.POST("/api/vaults/:id/oauth-connections/:connectionId/flows/:flowId/complete", s.completeOAuth)
	r.POST("/api/vaults/:id/oauth-connections/:connectionId/flows/:flowId/cancel", s.cancelOAuth)
	r.GET(oauthCallbackPrefix+":connectionId", s.oauthCallback)
}
func oauthRandom() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic("secure randomness unavailable")
	}
	return base64.RawURLEncoding.EncodeToString(b)
}
func oauthHash(v string) string { h := sha256.Sum256([]byte(v)); return hex.EncodeToString(h[:]) }
func validOAuthHTTPS(raw string) bool {
	u, err := url.Parse(raw)
	return err == nil && u.Scheme == "https" && u.Host != "" && u.User == nil && u.Fragment == ""
}
func validateOAuthSettings(v oauthConnectionRequest) error {
	if !mcpConnectionName.MatchString(v.ServerName) || strings.Contains(v.ServerName, "__") {
		return fmt.Errorf("invalid MCP connection name")
	}
	if !validOAuthHTTPS(v.Endpoint) || !validOAuthHTTPS(v.AuthorizationEndpoint) || !validOAuthHTTPS(v.TokenEndpoint) {
		return fmt.Errorf("MCP and OAuth endpoints must use HTTPS without userinfo or fragments")
	}
	if v.ClientID == "" {
		return fmt.Errorf("clientId is required")
	}
	if v.Resource != "" && !validOAuthHTTPS(v.Resource) {
		return fmt.Errorf("resource must be an HTTPS URI")
	}
	if v.Issuer != "" && !validOAuthHTTPS(v.Issuer) {
		return fmt.Errorf("issuer must be an HTTPS URI")
	}
	switch v.AuthMethod {
	case "none":
		if v.ClientSecret != "" {
			return fmt.Errorf("public clients must not include a client secret")
		}
	case "client_secret_basic", "client_secret_post":
		if v.ClientSecret == "" {
			return fmt.Errorf("clientSecret is required for this authentication method")
		}
	default:
		return fmt.Errorf("unsupported client authentication method")
	}
	allowed := map[string]bool{"prompt": true, "access_type": true, "audience": true, "login_hint": true, "include_granted_scopes": true}
	for k := range v.AuthorizationParams {
		if !allowed[k] {
			return fmt.Errorf("unsupported authorization parameter: %s", k)
		}
	}
	u, _ := url.Parse(v.AuthorizationEndpoint)
	for _, key := range []string{"client_id", "redirect_uri", "response_type", "state", "code_challenge", "code_challenge_method", "scope", "resource"} {
		if u.Query().Has(key) {
			return fmt.Errorf("authorizationEndpoint must not contain reserved OAuth parameters")
		}
	}
	return nil
}

// Public origin is deployment-controlled. HTTP is accepted only for loopback development.
// Never trust forwarded headers to select where authorization codes are delivered.
func (s *Server) oauthOrigin(c *gin.Context) (string, error) {
	raw := strings.TrimRight(s.cfg.OAuthPublicURL, "/")
	if raw == "" {
		raw = c.GetHeader("Origin")
		if raw == "" {
			raw = "http://" + c.Request.Host
		}
		u, err := url.Parse(raw)
		if err != nil || !oauthLoopback(u.Hostname()) {
			return "", fmt.Errorf("configure BUILDER_OAUTH_PUBLIC_URL with the public console origin")
		}
	}
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return "", fmt.Errorf("OAuth public URL must be an origin without path, query or fragment")
	}
	if u.Scheme != "https" && !(u.Scheme == "http" && oauthLoopback(u.Hostname())) {
		return "", fmt.Errorf("OAuth public URL must use HTTPS (HTTP loopback is allowed for development)")
	}
	return u.Scheme + "://" + u.Host, nil
}
func oauthLoopback(host string) bool {
	return host == "localhost" || host == "127.0.0.1" || host == "::1"
}
func (s *Server) decodeOAuth(v oauthConnection) (oauthSettings, error) {
	var cfg oauthSettings
	raw, err := decryptAESGCM(s.vaultKey, v.Ciphertext)
	if err != nil {
		return cfg, err
	}
	return cfg, json.Unmarshal([]byte(raw), &cfg)
}
func (s *Server) oauthConnectionJSON(c *gin.Context, v oauthConnection) (gin.H, error) {
	cfg, err := s.decodeOAuth(v)
	if err != nil {
		return nil, err
	}
	hasSecret := cfg.ClientSecret != ""
	cfg.ClientSecret = ""
	origin, err := s.oauthOrigin(c)
	if err != nil {
		return nil, err
	}
	connected := false
	if v.CredentialID != nil {
		err = s.db.Pool.QueryRow(c.Request.Context(), `SELECT EXISTS(SELECT 1 FROM vault_credentials WHERE credential_id=$1 AND vault_id=$2)`, *v.CredentialID, v.VaultID).Scan(&connected)
		if err != nil {
			return nil, err
		}
	}
	callback := origin + oauthCallbackPrefix + v.ID
	if v.Provider == "github" {
		callback = origin + oauthCallbackPrefix + "github"
	}
	var account map[string]any
	if connected {
		_ = json.Unmarshal([]byte(v.AccountJSON), &account)
	}
	return gin.H{"id": v.ID, "vaultId": v.VaultID, "serverName": v.ServerName, "endpoint": v.Endpoint, "settings": cfg, "provider": v.Provider, "account": account, "hasClientSecret": hasSecret, "connected": connected, "callbackUrl": callback}, nil
}
func (s *Server) listOAuthConnections(c *gin.Context) {
	if !s.ownVault(c, c.Param("id")) {
		writeErr(c, 404, "vault not found")
		return
	}
	rows, err := s.db.Pool.Query(c.Request.Context(), oauthConnectionSelect+` WHERE vault_id=$1 AND owner_id=$2 ORDER BY created_at`, c.Param("id"), currentResourceOwner(c))
	if err != nil {
		writeErr(c, 500, "Cannot load OAuth connections")
		return
	}
	var connections []oauthConnection
	for rows.Next() {
		v, err := scanOAuthConnection(rows)
		if err != nil {
			rows.Close()
			writeErr(c, 500, "Cannot load OAuth connections")
			return
		}
		connections = append(connections, v)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		writeErr(c, 500, "Cannot load OAuth connections")
		return
	}
	out := []gin.H{}
	for _, v := range connections {
		dto, err := s.oauthConnectionJSON(c, v)
		if err != nil {
			writeErr(c, 400, err.Error())
			return
		}
		out = append(out, dto)
	}
	c.Header("Cache-Control", "no-store")
	c.JSON(200, out)
}
func (s *Server) createOAuthConnection(c *gin.Context) {
	if !s.ownVault(c, c.Param("id")) {
		writeErr(c, 404, "vault not found")
		return
	}
	var req oauthConnectionRequest
	if c.ShouldBindJSON(&req) != nil {
		writeErr(c, 400, "invalid OAuth configuration")
		return
	}
	if req.Provider != "" {
		s.createGitHubConnection(c, req)
		return
	}
	if err := validateOAuthSettings(req); err != nil {
		writeErr(c, 400, err.Error())
		return
	}
	if _, err := s.oauthOrigin(c); err != nil {
		writeErr(c, 400, err.Error())
		return
	}
	ciphertext, err := encryptAESGCM(s.vaultKey, mustJSON(req.oauthSettings))
	if err != nil {
		writeErr(c, 500, "Cannot encrypt OAuth configuration")
		return
	}
	now := nowMillis()
	id := "oa_" + oauthRandom()
	_, err = s.db.Pool.Exec(c.Request.Context(), `INSERT INTO mcp_oauth_connections(connection_id,vault_id,owner_id,server_name,endpoint,settings_ciphertext,created_at,updated_at) VALUES($1,$2,$3,$4,$5,$6,$7,$7)`, id, c.Param("id"), currentResourceOwner(c), req.ServerName, req.Endpoint, ciphertext, now)
	if err != nil {
		writeErr(c, 409, "OAuth configuration already exists or could not be saved")
		return
	}
	c.Params = append(c.Params, gin.Param{Key: "connectionId", Value: id})
	v, err := s.oauthConnection(c)
	if err != nil {
		writeErr(c, 500, "Cannot load OAuth configuration")
		return
	}
	dto, err := s.oauthConnectionJSON(c, v)
	if err != nil {
		writeErr(c, 500, "Cannot load OAuth configuration")
		return
	}
	c.Header("Cache-Control", "no-store")
	c.JSON(201, dto)
}
func (s *Server) updateOAuthConnection(c *gin.Context) {
	v, err := s.oauthConnection(c)
	if err != nil {
		writeErr(c, 404, "OAuth connection not found")
		return
	}
	if v.Provider != "" {
		writeErr(c, 400, "This connection uses the administrator-managed GitHub application")
		return
	}
	var req oauthConnectionRequest
	if c.ShouldBindJSON(&req) != nil {
		writeErr(c, 400, "invalid OAuth configuration")
		return
	}
	old, err := s.decodeOAuth(v)
	if err != nil {
		writeErr(c, 500, "Cannot read OAuth configuration")
		return
	}
	if req.ClientSecret == "" && req.AuthMethod != "none" {
		if req.ClientID != old.ClientID || req.TokenEndpoint != old.TokenEndpoint || req.AuthMethod != old.AuthMethod {
			writeErr(c, 400, "Re-enter the client secret when changing the OAuth client or token endpoint")
			return
		}
		req.ClientSecret = old.ClientSecret
	}
	if req.Endpoint != v.Endpoint || req.ServerName != v.ServerName {
		writeErr(c, 400, "Create a new OAuth configuration when changing the MCP destination")
		return
	}
	if err = validateOAuthSettings(req); err != nil {
		writeErr(c, 400, err.Error())
		return
	}
	ct, err := encryptAESGCM(s.vaultKey, mustJSON(req.oauthSettings))
	if err != nil {
		writeErr(c, 500, "Cannot encrypt OAuth configuration")
		return
	}
	// Configuration changes invalidate pending authorization flows. Existing credentials remain
	// usable until successful reauthorization or an explicit disconnect.
	_, err = s.db.Pool.Exec(c.Request.Context(), `UPDATE mcp_oauth_connections SET settings_ciphertext=$1,generation=generation+1,updated_at=$2 WHERE connection_id=$3`, ct, nowMillis(), v.ID)
	if err != nil {
		writeErr(c, 500, "Cannot save OAuth configuration")
		return
	}
	v, err = s.oauthConnection(c)
	if err != nil {
		writeErr(c, 500, "Cannot load OAuth configuration")
		return
	}
	dto, err := s.oauthConnectionJSON(c, v)
	if err != nil {
		writeErr(c, 500, "Cannot load OAuth configuration")
		return
	}
	c.Header("Cache-Control", "no-store")
	c.JSON(200, dto)
}
func (s *Server) disconnectOAuth(c *gin.Context) {
	v, err := s.oauthConnection(c)
	if err != nil {
		writeErr(c, 404, "OAuth connection not found")
		return
	}
	tx, err := s.db.Pool.Begin(c.Request.Context())
	if err != nil {
		writeErr(c, 500, "Cannot disconnect OAuth")
		return
	}
	defer tx.Rollback(c.Request.Context())
	v, err = scanOAuthConnection(tx.QueryRow(c.Request.Context(), oauthConnectionSelect+` WHERE connection_id=$1 FOR UPDATE`, v.ID))
	if err == nil && v.CredentialID != nil {
		_, err = tx.Exec(c.Request.Context(), `DELETE FROM vault_credentials WHERE credential_id=$1 AND vault_id=$2`, *v.CredentialID, v.VaultID)
	}
	if err == nil {
		_, err = tx.Exec(c.Request.Context(), `UPDATE mcp_oauth_connections SET credential_id=NULL,account_json='{}',generation=generation+1,updated_at=$1 WHERE connection_id=$2`, nowMillis(), v.ID)
	}
	if err == nil {
		_, err = tx.Exec(c.Request.Context(), `UPDATE mcp_oauth_flows SET status='cancelled',request_ciphertext=NULL,token_ciphertext=NULL WHERE connection_id=$1 AND status IN ('pending','exchanging','authorized')`, v.ID)
	}
	if err != nil || tx.Commit(c.Request.Context()) != nil {
		writeErr(c, 500, "Cannot disconnect OAuth")
		return
	}
	c.JSON(200, gin.H{"disconnected": true})
}

type oauthFlowRequest struct {
	Settings    oauthSettings `json:"settings"`
	RedirectURI string        `json:"redirectUri"`
	Verifier    string        `json:"verifier"`
}

func (s *Server) startOAuth(c *gin.Context) {
	v, err := s.oauthConnection(c)
	if err != nil {
		writeErr(c, 404, "OAuth connection not found")
		return
	}
	cfg, err := s.decodeOAuth(v)
	if err != nil {
		writeErr(c, 500, "Cannot load OAuth settings")
		return
	}
	var providerRevision int64
	if v.Provider == "github" {
		app, revision, loadErr := s.loadGitHubApp(c.Request.Context())
		if loadErr != nil || !app.Enabled || !isGitHubMCPEndpoint(v.Endpoint) {
			writeErr(c, 409, "GitHub integration is not configured or is disabled; contact a platform administrator")
			return
		}
		cfg, providerRevision = app.oauthSettings(), revision
	}
	origin, err := s.oauthOrigin(c)
	if err != nil {
		writeErr(c, 400, err.Error())
		return
	}
	flowID, state, browser, verifier := "of_"+oauthRandom(), oauthRandom(), oauthRandom(), oauthRandom()
	redirect := origin + oauthCallbackPrefix + v.ID
	if v.Provider == "github" {
		redirect = origin + oauthCallbackPrefix + "github"
	}
	ct, err := encryptAESGCM(s.vaultKey, mustJSON(oauthFlowRequest{cfg, redirect, verifier}))
	if err != nil {
		writeErr(c, 500, "Cannot start OAuth")
		return
	}
	now := nowMillis()
	expires := now + oauthFlowLifetime.Milliseconds()
	tx, err := s.db.Pool.Begin(c.Request.Context())
	if err != nil {
		writeErr(c, 500, "Cannot start OAuth")
		return
	}
	defer tx.Rollback(c.Request.Context())
	if v.Provider == "github" {
		var revision int64
		if err := tx.QueryRow(c.Request.Context(), `SELECT revision FROM oauth_provider_apps WHERE provider='github' FOR SHARE`).Scan(&revision); err != nil || revision != providerRevision {
			writeErr(c, 409, "GitHub application changed; retry connecting")
			return
		}
	}
	var generation int64
	// CAS prevents starting with settings that were changed after this request loaded them.
	err = tx.QueryRow(c.Request.Context(), `UPDATE mcp_oauth_connections SET generation=generation+1 WHERE connection_id=$1 AND generation=$2 RETURNING generation`, v.ID, v.Generation).Scan(&generation)
	if err == nil {
		_, err = tx.Exec(c.Request.Context(), `DELETE FROM mcp_oauth_flows WHERE expires_at<$1`, now)
	}
	if err == nil {
		_, err = tx.Exec(c.Request.Context(), `INSERT INTO mcp_oauth_flows(flow_id,connection_id,owner_id,initiator_id,generation,state_hash,browser_hash,request_ciphertext,status,expires_at,created_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8,'pending',$9,$10)`, flowID, v.ID, v.OwnerID, currentUserID(c), generation, oauthHash(state), oauthHash(browser), ct, expires, now)
	}
	if err != nil || tx.Commit(c.Request.Context()) != nil {
		writeErr(c, 409, "OAuth settings changed; retry connecting")
		return
	}
	challenge := sha256.Sum256([]byte(verifier))
	authURL, _ := url.Parse(cfg.AuthorizationEndpoint)
	q := authURL.Query()
	for k, value := range cfg.AuthorizationParams {
		q.Set(k, value)
	}
	q.Set("response_type", "code")
	q.Set("client_id", cfg.ClientID)
	q.Set("redirect_uri", redirect)
	q.Set("state", state)
	q.Set("code_challenge", base64.RawURLEncoding.EncodeToString(challenge[:]))
	q.Set("code_challenge_method", "S256")
	if cfg.Scope != "" {
		q.Set("scope", cfg.Scope)
	}
	if cfg.Resource != "" {
		q.Set("resource", cfg.Resource)
	}
	authURL.RawQuery = q.Encode()
	cookiePath, _ := url.Parse(redirect)
	http.SetCookie(c.Writer, &http.Cookie{Name: "mcp_oauth_" + flowID, Value: browser, Path: cookiePath.Path, HttpOnly: true, Secure: strings.HasPrefix(origin, "https://"), SameSite: http.SameSiteLaxMode, MaxAge: int(oauthFlowLifetime.Seconds())})
	c.Header("Cache-Control", "no-store")
	c.JSON(200, gin.H{"flowId": flowID, "authorizationUrl": authURL.String(), "expiresAt": expires})
}
func (s *Server) oauthHTTP() *http.Client {
	client := http.Client{}
	if s.oauthHTTPClient != nil {
		client = *s.oauthHTTPClient
	}
	client.Timeout = 10 * time.Second
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	return &client
}
func exchangeOAuthCode(ctx context.Context, client *http.Client, req oauthFlowRequest, code string, now time.Time) (string, error) {
	cfg := req.Settings
	form := url.Values{"grant_type": {"authorization_code"}, "code": {code}, "redirect_uri": {req.RedirectURI}, "client_id": {cfg.ClientID}, "code_verifier": {req.Verifier}}
	if cfg.Resource != "" {
		form.Set("resource", cfg.Resource)
	}
	if cfg.AuthMethod == "client_secret_post" {
		form.Set("client_secret", cfg.ClientSecret)
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, cfg.TokenEndpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return "", fmt.Errorf("token_exchange_failed")
	}
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	request.Header.Set("Accept", "application/json")
	if cfg.AuthMethod == "client_secret_basic" {
		request.SetBasicAuth(url.QueryEscape(cfg.ClientID), url.QueryEscape(cfg.ClientSecret))
	}
	resp, err := client.Do(request)
	if err != nil {
		return "", fmt.Errorf("token_exchange_failed")
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", fmt.Errorf("token_exchange_rejected")
	}
	var token struct {
		AccessToken  string `json:"access_token"`
		RefreshToken string `json:"refresh_token"`
		TokenType    string `json:"token_type"`
		ExpiresIn    *int64 `json:"expires_in"`
		Scope        string `json:"scope"`
	}
	if json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&token) != nil || token.AccessToken == "" || strings.ContainsAny(token.AccessToken, "\r\n") || !strings.EqualFold(token.TokenType, "bearer") {
		return "", fmt.Errorf("invalid_token_response")
	}
	payload := gin.H{"access_token": token.AccessToken}
	if token.ExpiresIn != nil {
		if *token.ExpiresIn <= 0 || *token.ExpiresIn > 315360000 {
			return "", fmt.Errorf("invalid_token_expiry")
		}
		payload["expires_at"] = now.Add(time.Duration(*token.ExpiresIn) * time.Second).UTC().Format(time.RFC3339)
	}
	if token.RefreshToken != "" {
		auth := gin.H{"type": cfg.AuthMethod}
		if cfg.ClientSecret != "" {
			auth["client_secret"] = cfg.ClientSecret
		}
		refresh := gin.H{"token_endpoint": cfg.TokenEndpoint, "client_id": cfg.ClientID, "refresh_token": token.RefreshToken, "token_endpoint_auth": auth}
		scope := token.Scope
		if scope == "" {
			scope = cfg.Scope
		}
		if scope != "" {
			refresh["scope"] = scope
		}
		if cfg.Resource != "" {
			refresh["resource"] = cfg.Resource
		}
		payload["refresh"] = refresh
	}
	return mustJSON(payload), nil
}

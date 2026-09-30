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
	"crypto/subtle"
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
)

type oauthFlow struct {
	ID, ConnectionID, OwnerID, InitiatorID string
	Generation                             int64
	StateHash, BrowserHash                 string
	Request, Token                         []byte
	Status, ErrorCode                      string
	ExpiresAt                              int64
}

const oauthFlowSelect = `SELECT flow_id,connection_id,owner_id,initiator_id,generation,state_hash,browser_hash,request_ciphertext,token_ciphertext,status,error_code,expires_at FROM mcp_oauth_flows`

func scanOAuthFlow(row pgx.Row) (oauthFlow, error) {
	var f oauthFlow
	err := row.Scan(&f.ID, &f.ConnectionID, &f.OwnerID, &f.InitiatorID, &f.Generation, &f.StateHash, &f.BrowserHash, &f.Request, &f.Token, &f.Status, &f.ErrorCode, &f.ExpiresAt)
	return f, err
}
func oauthCallbackPage(c *gin.Context, ok bool) {
	c.Header("Cache-Control", "no-store")
	c.Header("Referrer-Policy", "no-referrer")
	c.Header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
	title := "Authorization could not be completed"
	status := http.StatusBadRequest
	if ok {
		title = "Authorization received"
		status = http.StatusOK
	}
	// No provider-controlled content, authorization code, state or token is rendered.
	c.Data(status, "text/html; charset=utf-8", []byte("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>AgentScope OAuth</title><body><h1>"+title+"</h1><p>Return to the AgentScope window to finish connecting and view the result. You can close this window.</p></body></html>"))
}
func (s *Server) oauthCallback(c *gin.Context) {
	query := c.Request.URL.Query()
	// Avoid retaining authorization query parameters in downstream request diagnostics.
	c.Request.URL.RawQuery = ""
	c.Request.RequestURI = c.Request.URL.Path
	if len(query["state"]) != 1 || query.Get("state") == "" {
		oauthCallbackPage(c, false)
		return
	}
	f, err := scanOAuthFlow(s.db.Pool.QueryRow(c.Request.Context(), oauthFlowSelect+` WHERE state_hash=$1 AND (connection_id=$2 OR ($2='github' AND connection_id IN (SELECT connection_id FROM mcp_oauth_connections WHERE provider='github')))`, oauthHash(query.Get("state")), c.Param("connectionId")))
	if err != nil || f.Status != "pending" || f.ExpiresAt <= nowMillis() {
		oauthCallbackPage(c, false)
		return
	}
	cookie, err := c.Cookie("mcp_oauth_" + f.ID)
	if err != nil || subtle.ConstantTimeCompare([]byte(oauthHash(cookie)), []byte(f.BrowserHash)) != 1 {
		oauthCallbackPage(c, false)
		return
	}
	raw, err := decryptAESGCM(s.vaultKey, f.Request)
	var request oauthFlowRequest
	if err != nil || json.Unmarshal([]byte(raw), &request) != nil {
		oauthCallbackPage(c, false)
		return
	}
	callbackURL, parseErr := url.Parse(request.RedirectURI)
	if parseErr != nil || callbackURL.Path != c.Request.URL.Path {
		oauthCallbackPage(c, false)
		return
	}
	tag, err := s.db.Pool.Exec(c.Request.Context(), `UPDATE mcp_oauth_flows f SET status='exchanging',request_ciphertext=NULL WHERE flow_id=$1 AND status='pending' AND expires_at>$2 AND EXISTS(SELECT 1 FROM mcp_oauth_connections o JOIN vaults v ON v.vault_id=o.vault_id WHERE o.connection_id=f.connection_id AND o.generation=f.generation AND v.archived_at IS NULL AND v.owner_id=f.owner_id)`, f.ID, nowMillis())
	if err != nil || tag.RowsAffected() != 1 {
		oauthCallbackPage(c, false)
		return
	}
	http.SetCookie(c.Writer, &http.Cookie{Name: "mcp_oauth_" + f.ID, Value: "", Path: callbackURL.Path, HttpOnly: true, Secure: strings.HasPrefix(request.RedirectURI, "https://"), SameSite: http.SameSiteLaxMode, MaxAge: -1})
	fail := func(code string) {
		_, _ = s.db.Pool.Exec(c.Request.Context(), `UPDATE mcp_oauth_flows SET status='failed',error_code=$1,request_ciphertext=NULL,token_ciphertext=NULL WHERE flow_id=$2 AND status='exchanging'`, code, f.ID)
		oauthCallbackPage(c, false)
	}
	if query.Has("error") {
		fail("authorization_denied")
		return
	}
	if len(query["code"]) != 1 || query.Get("code") == "" {
		fail("missing_authorization_code")
		return
	}
	if request.Settings.Issuer != "" && (len(query["iss"]) != 1 || query.Get("iss") != request.Settings.Issuer) {
		fail("issuer_mismatch")
		return
	}
	secret, err := exchangeOAuthCode(c.Request.Context(), s.oauthHTTP(), request, query.Get("code"), time.Now())
	if err != nil {
		fail(err.Error())
		return
	}
	ciphertext, err := encryptAESGCM(s.vaultKey, secret)
	if err != nil {
		fail("credential_storage_failed")
		return
	}
	tag, err = s.db.Pool.Exec(c.Request.Context(), `UPDATE mcp_oauth_flows f SET status='authorized',token_ciphertext=$1 WHERE flow_id=$2 AND status='exchanging' AND expires_at>$3 AND EXISTS(SELECT 1 FROM mcp_oauth_connections o WHERE o.connection_id=f.connection_id AND o.generation=f.generation)`, ciphertext, f.ID, nowMillis())
	if err != nil || tag.RowsAffected() != 1 {
		fail("authorization_expired")
		return
	}
	oauthCallbackPage(c, true)
}
func (s *Server) ownedOAuthFlow(c *gin.Context) (oauthConnection, oauthFlow, error) {
	v, err := s.oauthConnection(c)
	if err != nil {
		return v, oauthFlow{}, err
	}
	f, err := scanOAuthFlow(s.db.Pool.QueryRow(c.Request.Context(), oauthFlowSelect+` WHERE flow_id=$1 AND connection_id=$2 AND owner_id=$3 AND initiator_id=$4`, c.Param("flowId"), v.ID, currentResourceOwner(c), currentUserID(c)))
	return v, f, err
}
func oauthVisibleStatus(v oauthConnection, f oauthFlow) string {
	if f.Generation != v.Generation {
		return "cancelled"
	}
	if f.Status != "completed" && f.ExpiresAt <= nowMillis() {
		return "expired"
	}
	return f.Status
}
func (s *Server) oauthFlowStatus(c *gin.Context) {
	v, f, err := s.ownedOAuthFlow(c)
	if err != nil {
		writeErr(c, 404, "OAuth request not found")
		return
	}
	c.Header("Cache-Control", "no-store")
	c.JSON(200, gin.H{"status": oauthVisibleStatus(v, f), "errorCode": f.ErrorCode, "expiresAt": f.ExpiresAt})
}
func (s *Server) cancelOAuth(c *gin.Context) {
	_, f, err := s.ownedOAuthFlow(c)
	if err != nil {
		writeErr(c, 404, "OAuth request not found")
		return
	}
	_, err = s.db.Pool.Exec(c.Request.Context(), `UPDATE mcp_oauth_flows SET status='cancelled',request_ciphertext=NULL,token_ciphertext=NULL WHERE flow_id=$1 AND status IN ('pending','exchanging','authorized')`, f.ID)
	if err != nil {
		writeErr(c, 500, "Cannot cancel OAuth request")
		return
	}
	c.Status(204)
}
func (s *Server) completeOAuth(c *gin.Context) {
	v, f, err := s.ownedOAuthFlow(c)
	if err != nil {
		writeErr(c, 404, "OAuth request not found")
		return
	}
	tx, err := s.db.Pool.Begin(c.Request.Context())
	if err != nil {
		writeErr(c, 500, "Cannot save OAuth credential")
		return
	}
	defer tx.Rollback(c.Request.Context())
	// Lock order is connection -> flow, also used by disconnect. Network requests never hold
	// these locks. Namespace membership is rechecked by the authenticated route middleware.
	v, err = scanOAuthConnection(tx.QueryRow(c.Request.Context(), oauthConnectionSelect+` WHERE connection_id=$1 FOR UPDATE`, v.ID))
	if err != nil {
		writeErr(c, 404, "OAuth connection not found")
		return
	}
	f, err = scanOAuthFlow(tx.QueryRow(c.Request.Context(), oauthFlowSelect+` WHERE flow_id=$1 FOR UPDATE`, f.ID))
	if err != nil {
		writeErr(c, 404, "OAuth request not found")
		return
	}
	status := oauthVisibleStatus(v, f)
	if status == "completed" {
		c.JSON(200, gin.H{"connected": true, "vaultId": v.VaultID, "credentialId": v.CredentialID})
		return
	}
	if status != "authorized" || len(f.Token) == 0 {
		writeErr(c, 409, "Authorization is not ready or has expired")
		return
	}
	// Lock the Vault against concurrent archive/delete before writing any credential.
	var vaultOwner string
	err = tx.QueryRow(c.Request.Context(), `SELECT owner_id FROM vaults WHERE vault_id=$1 AND archived_at IS NULL FOR SHARE`, v.VaultID).Scan(&vaultOwner)
	if err != nil || vaultOwner != currentResourceOwner(c) {
		writeErr(c, 404, "Vault no longer available")
		return
	}
	credentialID := deref(v.CredentialID)
	var duplicate bool
	err = tx.QueryRow(c.Request.Context(), `SELECT EXISTS(SELECT 1 FROM vault_credentials WHERE vault_id=$1 AND target IN ($2,$3) AND type IN ('mcp_oauth','oauth_token','static_bearer') AND credential_id<>$4)`, v.VaultID, v.Endpoint, v.ServerName, credentialID).Scan(&duplicate)
	if err != nil {
		writeErr(c, 500, "Cannot check OAuth credential")
		return
	}
	if duplicate {
		writeErr(c, 409, "This Vault already has a credential for the MCP connection; remove that credential before finishing")
		return
	}
	if credentialID == "" {
		credentialID = shortID("cred_")
	}
	_, err = tx.Exec(c.Request.Context(), `INSERT INTO vault_credentials(credential_id,vault_id,type,label,target,ciphertext,created_at,revision) VALUES($1,$2,'mcp_oauth',$3,$4,$5,$6,1) ON CONFLICT(credential_id) DO UPDATE SET ciphertext=EXCLUDED.ciphertext,type='mcp_oauth',target=EXCLUDED.target,revision=vault_credentials.revision+1`, credentialID, v.VaultID, v.ServerName+" OAuth", v.Endpoint, f.Token, nowMillis())
	if err == nil {
		_, err = tx.Exec(c.Request.Context(), `UPDATE mcp_oauth_connections SET credential_id=$1,account_json='{}',updated_at=$2 WHERE connection_id=$3`, credentialID, nowMillis(), v.ID)
	}
	if err == nil {
		_, err = tx.Exec(c.Request.Context(), `UPDATE mcp_oauth_flows SET status='completed',token_ciphertext=NULL,request_ciphertext=NULL WHERE flow_id=$1`, f.ID)
	}
	if err != nil || tx.Commit(c.Request.Context()) != nil {
		writeErr(c, 500, "Cannot save OAuth credential")
		return
	}
	c.Header("Cache-Control", "no-store")
	c.JSON(200, gin.H{"connected": true, "vaultId": v.VaultID, "credentialId": credentialID})
}

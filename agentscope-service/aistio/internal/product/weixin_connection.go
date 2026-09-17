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
	"errors"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

func (s *Server) completeWeixinLink(c *gin.Context) (any, error) {
	tx, ch, err := s.lockWeixinChannel(c)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(c.Request.Context())
	ctx := c.Request.Context()
	f, err := s.ownedWeixinFlow(c, tx, ch)
	if err != nil {
		return nil, err
	}
	if f.Status == "COMPLETED" {
		return f.public(), nil
	}
	if f.Status != "AUTHORIZED" || len(f.Credential) == 0 {
		if err = tx.Commit(ctx); err != nil {
			return nil, err
		}
		return nil, wxError(409, "weixin_authorization_not_ready")
	}
	var result weixinLoginResult
	if err = s.decryptWeixin(f.Result, &result); err != nil {
		return nil, err
	}
	if result.AccountID == "" || result.UserID == "" || result.BaseURL == "" {
		return nil, wxError(409, "weixin_invalid_provider_response")
	}
	conn, err := scanWeixinConnection(tx.QueryRow(ctx, weixinConnectionSelect+` WHERE channel_id=$1`, ch.ChannelID))
	if err != nil {
		return nil, err
	}
	now := nowMillis()
	if conn.VaultID == "" {
		conn.VaultID = shortID("vault_")
		_, err = tx.Exec(ctx, `INSERT INTO vaults(vault_id,owner_id,display_name,created_at,updated_at) VALUES($1,$2,$3,$4,$4)`, conn.VaultID, ch.OwnerID, "Weixin "+ch.ChannelID, now)
	} else {
		var owner string
		err = tx.QueryRow(ctx, `SELECT owner_id FROM vaults WHERE vault_id=$1 AND archived_at IS NULL FOR SHARE`, conn.VaultID).Scan(&owner)
		if err != nil || owner != ch.OwnerID {
			return nil, wxError(409, "weixin_vault_unavailable")
		}
	}
	if err != nil {
		return nil, err
	}
	if conn.CredentialID == "" {
		conn.CredentialID = shortID("cred_")
	}
	err = tx.QueryRow(ctx, `INSERT INTO vault_credentials(credential_id,vault_id,type,label,target,ciphertext,created_at,revision)
 VALUES($1,$2,'weixin_bot',$3,$4,$5,$6,$7)
 ON CONFLICT(credential_id) DO UPDATE SET ciphertext=EXCLUDED.ciphertext,revision=GREATEST(vault_credentials.revision+1,EXCLUDED.revision)
 WHERE vault_credentials.vault_id=EXCLUDED.vault_id AND vault_credentials.type='weixin_bot' AND vault_credentials.target=EXCLUDED.target
 RETURNING revision`, conn.CredentialID, conn.VaultID, "Weixin "+ch.ChannelID, ch.ChannelID, f.Credential, now, conn.Revision+1).Scan(&conn.Revision)
	if err != nil {
		return nil, err
	}
	_, err = tx.Exec(ctx, `UPDATE weixin_connections SET vault_id=$2,credential_id=$3,credential_revision=$4,account_id=$5,ilink_user_id=$6,base_url=$7,status='STARTING',updated_at=$8,runtime_lease_generation=0,runtime_lease_holder='',runtime_report_sequence=0 WHERE channel_id=$1`, ch.ChannelID, conn.VaultID, conn.CredentialID, conn.Revision, result.AccountID, result.UserID, result.BaseURL, now)
	if err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" {
			return nil, wxError(409, "weixin_account_in_use")
		}
		return nil, err
	}
	props := gin.H{"accountId": result.AccountID, "ilinkUserId": result.UserID, "baseUrl": result.BaseURL, "credentialRef": conn.CredentialID, "credentialRevision": conn.Revision}
	_, err = tx.Exec(ctx, `UPDATE channels SET properties_json=$2,disabled=false,runtime_started=false,runtime_error=NULL,updated_at=$3 WHERE channel_id=$1`, ch.ChannelID, mustJSON(props), now)
	if err != nil {
		return nil, err
	}
	_, err = tx.Exec(ctx, `UPDATE weixin_link_flows SET status='COMPLETED',session_ciphertext=NULL,credential_ciphertext=NULL,result_ciphertext=NULL,operation_id='',busy_until=0,updated_at=$2 WHERE flow_id=$1`, f.ID, now)
	if err != nil {
		return nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	f.Status = "COMPLETED"
	return f.public(), nil
}
func (s *Server) weixinStatus(c *gin.Context) (any, error) {
	tx, ch, err := s.lockWeixinChannel(c)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(c.Request.Context())
	conn, err := scanWeixinConnection(tx.QueryRow(c.Request.Context(), weixinConnectionSelect+` WHERE channel_id=$1`, ch.ChannelID))
	if errors.Is(err, pgx.ErrNoRows) {
		return gin.H{"status": "PENDING_LINK", "connected": false, "accountId": nil}, nil
	}
	if err != nil {
		return nil, err
	}
	status := conn.Status
	connected := conn.CredentialID != ""
	if connected {
		var available bool
		err = tx.QueryRow(c.Request.Context(), `SELECT EXISTS(SELECT 1 FROM vault_credentials cr JOIN vaults v USING(vault_id) WHERE cr.credential_id=$1 AND cr.type='weixin_bot' AND cr.target=$2 AND v.owner_id=$3 AND v.archived_at IS NULL)`, conn.CredentialID, ch.ChannelID, ch.OwnerID).Scan(&available)
		if err != nil {
			return nil, err
		}
		switch {
		case !available:
			status = "REAUTH_REQUIRED"
			connected = false
		case ch.RuntimeError != nil && strings.TrimSpace(*ch.RuntimeError) == "CREDENTIAL_REJECTED":
			status = "REAUTH_REQUIRED"
			connected = false
		case ch.Disabled:
			status = "DISABLED"
		case ch.RuntimeError != nil:
			status = "FAILED"
		case ch.RuntimeStarted:
			status = "RUNNING"
		default:
			status = "STARTING"
		}
	}
	var accountID any
	if connected && conn.AccountID != "" {
		accountID = conn.AccountID
	}
	return gin.H{"status": status, "connected": connected, "disabled": ch.Disabled, "accountId": accountID}, nil
}
func (s *Server) disconnectWeixin(c *gin.Context) (any, error) {
	tx, ch, err := s.lockWeixinChannel(c)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(c.Request.Context())
	if err = disconnectWeixinTx(c.Request.Context(), tx, ch); err != nil {
		return nil, err
	}
	return gin.H{"status": "DISCONNECTED", "connected": false, "remoteRevoked": false}, tx.Commit(c.Request.Context())
}
func disconnectWeixinTx(ctx context.Context, tx pgx.Tx, ch channelRow) error {
	if _, err := tx.Exec(ctx, `INSERT INTO weixin_connections(channel_id,owner_id,updated_at) VALUES($1,$2,$3) ON CONFLICT(channel_id) DO NOTHING`, ch.ChannelID, ch.OwnerID, nowMillis()); err != nil {
		return err
	}
	if err := cancelWeixinFlows(ctx, tx, ch.ChannelID); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `DELETE FROM vault_credentials cr USING weixin_connections w,vaults v WHERE w.channel_id=$1 AND cr.credential_id=w.credential_id AND cr.vault_id=w.vault_id AND v.vault_id=cr.vault_id AND v.owner_id=$2 AND cr.type='weixin_bot' AND cr.target=$1`, ch.ChannelID, ch.OwnerID)
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `UPDATE weixin_connections SET credential_id=NULL,account_id=NULL,ilink_user_id='',base_url='',generation=generation+1,status='DISCONNECTED',updated_at=$2 WHERE channel_id=$1`, ch.ChannelID, nowMillis())
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `UPDATE channels SET properties_json='{}',disabled=true,runtime_started=false,runtime_error=NULL,updated_at=$2 WHERE channel_id=$1`, ch.ChannelID, nowMillis())
	return err
}

// Desired runtime properties are reconstructed from the managed connection and
// current Vault metadata; raw channel properties cannot pick another credential.
func (s *Server) weixinRuntimeProperties(ctx context.Context, channelID string) (gin.H, error) {
	var account, user, base, credential string
	var revision int64
	err := s.db.Pool.QueryRow(ctx, `SELECT w.account_id,w.ilink_user_id,w.base_url,w.credential_id,cr.revision
 FROM weixin_connections w JOIN channels ch ON ch.channel_id=w.channel_id AND ch.owner_id=w.owner_id
 JOIN vault_credentials cr ON cr.credential_id=w.credential_id AND cr.vault_id=w.vault_id AND cr.type='weixin_bot' AND cr.target=w.channel_id
 JOIN vaults v ON v.vault_id=w.vault_id AND v.owner_id=w.owner_id AND v.archived_at IS NULL
 WHERE w.channel_id=$1 AND ch.type='weixin' AND ch.disabled=false AND w.account_id IS NOT NULL`, channelID).Scan(&account, &user, &base, &credential, &revision)
	if err != nil {
		return nil, err
	}
	return gin.H{"accountId": account, "ilinkUserId": user, "baseUrl": base, "credentialRef": credential, "credentialRevision": revision}, nil
}
func (s *Server) internalWeixinCredential(c *gin.Context) {
	c.Header("Cache-Control", "no-store")
	var request struct {
		Revision int64 `json:"revision"`
	}
	if c.ShouldBindJSON(&request) != nil || request.Revision <= 0 {
		writeErr(c, 400, "credential revision required")
		return
	}
	var ciphertext []byte
	var revision int64
	err := s.db.Pool.QueryRow(c.Request.Context(), `SELECT cr.ciphertext,cr.revision FROM weixin_connections w
 JOIN channels ch ON ch.channel_id=w.channel_id AND ch.owner_id=w.owner_id AND ch.type='weixin' AND ch.disabled=false
 JOIN vault_credentials cr ON cr.credential_id=w.credential_id AND cr.vault_id=w.vault_id AND cr.type='weixin_bot' AND cr.target=w.channel_id
 JOIN vaults v ON v.vault_id=w.vault_id AND v.owner_id=w.owner_id AND v.archived_at IS NULL
 WHERE w.channel_id=$1 AND w.account_id IS NOT NULL AND cr.revision=$2`, c.Param("channelId"), request.Revision).Scan(&ciphertext, &revision)
	if err != nil {
		writeErr(c, 404, "Weixin credential unavailable")
		return
	}
	secret, err := decryptAESGCM(s.vaultKey, ciphertext)
	if err != nil || strings.TrimSpace(secret) == "" {
		writeErr(c, 500, "Weixin credential unavailable")
		return
	}
	c.JSON(200, gin.H{"botToken": secret, "revision": revision})
}

func canEnableWeixin(ctx context.Context, tx pgx.Tx, ch channelRow) error {
	var ready bool
	err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM weixin_connections w
 JOIN vault_credentials cr ON cr.credential_id=w.credential_id AND cr.vault_id=w.vault_id AND cr.type='weixin_bot' AND cr.target=w.channel_id
 JOIN vaults v ON v.vault_id=w.vault_id AND v.owner_id=w.owner_id AND v.archived_at IS NULL
 WHERE w.channel_id=$1 AND w.owner_id=$2 AND w.account_id IS NOT NULL)`, ch.ChannelID, ch.OwnerID).Scan(&ready)
	if err != nil {
		return err
	}
	if !ready {
		return wxError(409, "weixin_authorization_required")
	}
	return nil
}
func invalidateWeixinFlows(ctx context.Context, tx pgx.Tx, channelID string) error {
	if err := cancelWeixinFlows(ctx, tx, channelID); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `UPDATE weixin_connections SET generation=generation+1,updated_at=$2 WHERE channel_id=$1`, channelID, nowMillis())
	return err
}

func emptyWeixinProperties(value any) bool {
	if value == nil {
		return true
	}
	props, ok := value.(map[string]any)
	return ok && len(props) == 0
}

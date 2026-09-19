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
	"errors"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
)

const weixinMigrationSQL = `
CREATE TABLE IF NOT EXISTS weixin_connections (
 channel_id TEXT PRIMARY KEY REFERENCES channels(channel_id) ON DELETE CASCADE,
 owner_id TEXT NOT NULL,
 vault_id TEXT,
 credential_id TEXT,
 credential_revision BIGINT NOT NULL DEFAULT 0,
 account_id TEXT UNIQUE,
 ilink_user_id TEXT NOT NULL DEFAULT '',
 base_url TEXT NOT NULL DEFAULT '',
 generation BIGINT NOT NULL DEFAULT 0,
 status TEXT NOT NULL DEFAULT 'PENDING_LINK',
 next_start_at BIGINT NOT NULL DEFAULT 0,
 updated_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS weixin_link_flows (
 flow_id TEXT PRIMARY KEY,
 channel_id TEXT NOT NULL REFERENCES weixin_connections(channel_id) ON DELETE CASCADE,
 owner_id TEXT NOT NULL,
 initiator_id TEXT NOT NULL,
 generation BIGINT NOT NULL,
 session_ciphertext BYTEA,
 credential_ciphertext BYTEA,
 result_ciphertext BYTEA,
 status TEXT NOT NULL,
 error_code TEXT NOT NULL DEFAULT '',
 operation_id TEXT NOT NULL DEFAULT '',
 busy_until BIGINT NOT NULL DEFAULT 0,
 next_operation_at BIGINT NOT NULL DEFAULT 0,
 expires_at BIGINT NOT NULL,
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS weixin_link_flows_channel ON weixin_link_flows(channel_id, generation);
CREATE INDEX IF NOT EXISTS weixin_link_flows_expiry ON weixin_link_flows(expires_at);
ALTER TABLE weixin_connections ADD COLUMN IF NOT EXISTS runtime_lease_generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE weixin_connections ADD COLUMN IF NOT EXISTS runtime_lease_holder TEXT NOT NULL DEFAULT '';
ALTER TABLE weixin_connections ADD COLUMN IF NOT EXISTS runtime_report_sequence BIGINT NOT NULL DEFAULT 0;
`

const weixinActiveFlows = `('STARTING','WAITING_SCAN','SCANNED','NEED_VERIFY_CODE','AUTHORIZED')`
const weixinFlowSelect = `SELECT flow_id,channel_id,owner_id,initiator_id,generation,session_ciphertext,credential_ciphertext,result_ciphertext,status,error_code,operation_id,busy_until,next_operation_at,expires_at FROM weixin_link_flows`
const weixinConnectionSelect = `SELECT channel_id,owner_id,COALESCE(vault_id,''),COALESCE(credential_id,''),credential_revision,COALESCE(account_id,''),ilink_user_id,base_url,generation,status,next_start_at FROM weixin_connections`

type weixinConnection struct {
	ChannelID, OwnerID, VaultID, CredentialID string
	Revision                                  int64
	AccountID, UserID, BaseURL                string
	Generation                                int64
	Status                                    string
	NextStartAt                               int64
}

func scanWeixinConnection(row pgx.Row) (v weixinConnection, err error) {
	err = row.Scan(&v.ChannelID, &v.OwnerID, &v.VaultID, &v.CredentialID, &v.Revision, &v.AccountID, &v.UserID, &v.BaseURL, &v.Generation, &v.Status, &v.NextStartAt)
	return
}

type weixinFlow struct {
	ID, ChannelID, OwnerID, InitiatorID   string
	Generation                            int64
	Session, Credential, Result           []byte
	Status, ErrorCode, OperationID        string
	BusyUntil, NextOperationAt, ExpiresAt int64
}

func scanWeixinFlow(row pgx.Row) (f weixinFlow, err error) {
	err = row.Scan(&f.ID, &f.ChannelID, &f.OwnerID, &f.InitiatorID, &f.Generation, &f.Session, &f.Credential, &f.Result, &f.Status, &f.ErrorCode, &f.OperationID, &f.BusyUntil, &f.NextOperationAt, &f.ExpiresAt)
	return
}
func (f weixinFlow) public() gin.H {
	return gin.H{"flowId": f.ID, "status": f.Status, "errorCode": f.ErrorCode, "expiresAt": f.ExpiresAt, "pollAfterMs": 2000}
}

type weixinError struct {
	status int
	code   string
}

func (e *weixinError) Error() string        { return e.code }
func wxError(status int, code string) error { return &weixinError{status, code} }
func weixinHandler(fn func(*gin.Context) (any, error)) gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Header("Cache-Control", "no-store")
		body, err := fn(c)
		if err != nil {
			var public *weixinError
			if errors.As(err, &public) {
				c.JSON(public.status, gin.H{"errorCode": public.code})
				return
			}
			c.JSON(500, gin.H{"errorCode": "weixin_storage_unavailable"})
			return
		}
		c.JSON(200, body)
	}
}

// Lock order for all resource mutations is Channel -> flow -> Vault. No provider
// request holds a transaction; generation and operation id fence late responses.
func (s *Server) lockWeixinChannel(c *gin.Context) (pgx.Tx, channelRow, error) {
	tx, err := s.db.Pool.Begin(c.Request.Context())
	if err != nil {
		return nil, channelRow{}, err
	}
	ch, err := s.scanChannel(tx.QueryRow(c.Request.Context(), channelSelect+` WHERE channel_id=$1 AND owner_id=$2 FOR UPDATE`, c.Param("channelId"), currentResourceOwner(c)))
	if err != nil || ch.Type != "weixin" {
		tx.Rollback(c.Request.Context())
		return nil, ch, wxError(404, "weixin_channel_not_found")
	}
	return tx, ch, nil
}
func (s *Server) ownedWeixinFlow(c *gin.Context, tx pgx.Tx, ch channelRow) (weixinFlow, error) {
	f, err := scanWeixinFlow(tx.QueryRow(c.Request.Context(), weixinFlowSelect+` WHERE flow_id=$1 AND channel_id=$2 AND owner_id=$3 AND initiator_id=$4 FOR UPDATE`, c.Param("flowId"), ch.ChannelID, ch.OwnerID, currentUserID(c)))
	if err != nil {
		return f, wxError(404, "weixin_flow_not_found")
	}
	var generation int64
	if err = tx.QueryRow(c.Request.Context(), `SELECT generation FROM weixin_connections WHERE channel_id=$1`, ch.ChannelID).Scan(&generation); err != nil {
		return f, err
	}
	if f.Generation != generation {
		return f, wxError(409, "weixin_flow_superseded")
	}
	if f.ExpiresAt <= nowMillis() && (f.Status == "STARTING" || f.Status == "WAITING_SCAN" || f.Status == "SCANNED" || f.Status == "NEED_VERIFY_CODE" || f.Status == "AUTHORIZED") {
		f.Status = "EXPIRED"
		f.Session = nil
		f.Credential = nil
		f.Result = nil
		f.OperationID = ""
		_, err = tx.Exec(c.Request.Context(), `UPDATE weixin_link_flows SET status='EXPIRED',session_ciphertext=NULL,credential_ciphertext=NULL,result_ciphertext=NULL,operation_id='',busy_until=0,updated_at=$2 WHERE flow_id=$1`, f.ID, nowMillis())
	}
	return f, err
}
func cancelWeixinFlows(ctx context.Context, tx pgx.Tx, channelID string) error {
	_, err := tx.Exec(ctx, `UPDATE weixin_link_flows SET status='CANCELLED',session_ciphertext=NULL,credential_ciphertext=NULL,result_ciphertext=NULL,operation_id='',busy_until=0,updated_at=$2 WHERE channel_id=$1 AND status IN `+weixinActiveFlows, channelID, nowMillis())
	return err
}
func (s *Server) encryptWeixin(value any) ([]byte, error) {
	data, err := json.Marshal(value)
	if err != nil {
		return nil, err
	}
	return encryptAESGCM(s.vaultKey, string(data))
}
func (s *Server) decryptWeixin(data []byte, value any) error {
	plain, err := decryptAESGCM(s.vaultKey, data)
	if err != nil {
		return err
	}
	return json.Unmarshal([]byte(plain), value)
}
func (s *Server) expireWeixinFlows(ctx context.Context) error {
	_, err := s.db.Pool.Exec(ctx, `UPDATE weixin_link_flows SET status='EXPIRED',session_ciphertext=NULL,credential_ciphertext=NULL,result_ciphertext=NULL,operation_id='',busy_until=0,updated_at=$1 WHERE expires_at<=$1 AND status IN `+weixinActiveFlows, nowMillis())
	return err
}
func (s *Server) startWeixinExpiryWorker() {
	ctx, cancel := context.WithCancel(context.Background())
	s.weixinCleanupCancel = cancel
	s.weixinCleanupDone = make(chan struct{})
	go func() {
		defer close(s.weixinCleanupDone)
		ticker := time.NewTicker(time.Minute)
		defer ticker.Stop()
		for {
			cleanupCtx, stop := context.WithTimeout(ctx, 10*time.Second)
			_ = s.expireWeixinFlows(cleanupCtx)
			stop()
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
			}
		}
	}()
}

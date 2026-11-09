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
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// These DTOs describe the Scheduler interface, not a second iLink implementation.
type weixinSession struct {
	QRCode         string `json:"qrcode"`
	PollingBaseURL string `json:"pollingBaseUrl"`
}
type weixinLoginResult struct {
	Session     weixinSession `json:"session"`
	QRCodeImage string        `json:"qrcodeImage"`
	Status      string        `json:"status"`
	BotToken    string        `json:"botToken"`
	AccountID   string        `json:"accountId"`
	UserID      string        `json:"userId"`
	BaseURL     string        `json:"baseUrl"`
}

var weixinSchedulerHTTP = &http.Client{
	Timeout:       55 * time.Second,
	CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
}

func (s *Server) callWeixinLogin(ctx context.Context, operation string, request any) (result weixinLoginResult, err error) {
	if strings.TrimSpace(s.cfg.SchedulerURL) == "" || s.cfg.InternalToken == "" {
		return result, wxError(503, "weixin_scheduler_not_configured")
	}
	data, err := json.Marshal(request)
	if err != nil {
		return result, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(s.cfg.SchedulerURL, "/")+"/api/internal/channel-providers/weixin/login/"+operation, bytes.NewReader(data))
	if err != nil {
		return result, wxError(503, "weixin_scheduler_unavailable")
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Builder-Internal-Token", s.cfg.InternalToken)
	response, err := weixinSchedulerHTTP.Do(req)
	if err != nil {
		return result, wxError(502, "weixin_provider_unavailable")
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return result, wxError(502, "weixin_provider_unavailable")
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, (1<<20)+1))
	if err != nil || len(body) > 1<<20 || json.Unmarshal(body, &result) != nil {
		return weixinLoginResult{}, wxError(502, "weixin_invalid_provider_response")
	}
	return result, nil
}
func (s *Server) registerWeixin(r gin.IRouter) {
	root := "/api/channels/:channelId/weixin"
	r.POST(root+"/link-flows", weixinHandler(s.startWeixinLink))
	r.POST(root+"/relink", weixinHandler(s.startWeixinLink))
	r.POST(root+"/link-flows/:flowId/poll", weixinHandler(s.pollWeixinLink))
	r.POST(root+"/link-flows/:flowId/verify", weixinHandler(s.verifyWeixinLink))
	r.POST(root+"/link-flows/:flowId/complete", weixinHandler(s.completeWeixinLink))
	r.POST(root+"/link-flows/:flowId/cancel", weixinHandler(s.cancelWeixinLink))
	r.GET(root+"/link-flows/:flowId", weixinHandler(s.weixinLinkStatus))
	r.GET(root+"/status", weixinHandler(s.weixinStatus))
	r.POST(root+"/disconnect", weixinHandler(s.disconnectWeixin))
}
func (s *Server) startWeixinLink(c *gin.Context) (any, error) {
	if s.cfg.SchedulerURL == "" || s.cfg.InternalToken == "" {
		return nil, wxError(503, "weixin_scheduler_not_configured")
	}
	tx, ch, err := s.lockWeixinChannel(c)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(c.Request.Context())
	ctx := c.Request.Context()
	now := nowMillis()
	_, err = tx.Exec(ctx, `INSERT INTO weixin_connections(channel_id,owner_id,updated_at) VALUES($1,$2,$3) ON CONFLICT(channel_id) DO NOTHING`, ch.ChannelID, ch.OwnerID, now)
	if err != nil {
		return nil, err
	}
	conn, err := scanWeixinConnection(tx.QueryRow(ctx, weixinConnectionSelect+` WHERE channel_id=$1`, ch.ChannelID))
	if err != nil {
		return nil, err
	}
	if conn.NextStartAt > now {
		return nil, wxError(429, "weixin_retry_later")
	}
	if err = cancelWeixinFlows(ctx, tx, ch.ChannelID); err != nil {
		return nil, err
	}
	f := weixinFlow{ID: uuid.NewString(), ChannelID: ch.ChannelID, OwnerID: ch.OwnerID, InitiatorID: currentUserID(c), Generation: conn.Generation + 1, Status: "STARTING", OperationID: uuid.NewString(), ExpiresAt: now + int64(5*time.Minute/time.Millisecond)}
	_, err = tx.Exec(ctx, `UPDATE weixin_connections SET generation=$2,next_start_at=$3,updated_at=$4 WHERE channel_id=$1`, ch.ChannelID, f.Generation, now+5000, now)
	if err != nil {
		return nil, err
	}
	_, err = tx.Exec(ctx, `INSERT INTO weixin_link_flows(flow_id,channel_id,owner_id,initiator_id,generation,status,operation_id,busy_until,expires_at,created_at,updated_at) VALUES($1,$2,$3,$4,$5,'STARTING',$6,$7,$8,$9,$9)`, f.ID, ch.ChannelID, ch.OwnerID, f.InitiatorID, f.Generation, f.OperationID, now+60000, f.ExpiresAt, now)
	if err != nil {
		return nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	result, providerErr := s.callWeixinLogin(ctx, "start", gin.H{"botType": "3"})
	return s.finishWeixinOperation(c, f, result, providerErr, true)
}
func (s *Server) pollWeixinLink(c *gin.Context) (any, error) { return s.advanceWeixinLink(c, "") }
func (s *Server) verifyWeixinLink(c *gin.Context) (any, error) {
	var request struct {
		Code string `json:"verifyCode"`
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 4096)
	if c.ShouldBindJSON(&request) != nil || strings.TrimSpace(request.Code) == "" || len(request.Code) > 64 {
		return nil, wxError(400, "weixin_invalid_verification_code")
	}
	return s.advanceWeixinLink(c, strings.TrimSpace(request.Code))
}
func (s *Server) advanceWeixinLink(c *gin.Context, code string) (any, error) {
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
	if f.Status != "WAITING_SCAN" && f.Status != "SCANNED" && f.Status != "NEED_VERIFY_CODE" {
		return f.public(), tx.Commit(ctx)
	}
	now := nowMillis()
	if f.BusyUntil > now || f.NextOperationAt > now {
		return nil, wxError(429, "weixin_retry_later")
	}
	if code != "" && f.Status != "NEED_VERIFY_CODE" {
		return nil, wxError(409, "weixin_verification_not_requested")
	}
	var session weixinSession
	if err = s.decryptWeixin(f.Session, &session); err != nil {
		return nil, err
	}
	f.OperationID = uuid.NewString()
	_, err = tx.Exec(ctx, `UPDATE weixin_link_flows SET operation_id=$2,busy_until=$3,next_operation_at=$4,updated_at=$5 WHERE flow_id=$1`, f.ID, f.OperationID, now+60000, now+2000, now)
	if err != nil {
		return nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	operation := "poll"
	body := gin.H{"qrcode": session.QRCode, "pollingBaseUrl": session.PollingBaseURL}
	if code != "" {
		operation = "verify"
		body["verifyCode"] = code
	}
	result, providerErr := s.callWeixinLogin(ctx, operation, body)
	return s.finishWeixinOperation(c, f, result, providerErr, false)
}
func (s *Server) finishWeixinOperation(c *gin.Context, original weixinFlow, result weixinLoginResult, providerErr error, starting bool) (any, error) {
	tx, ch, err := s.lockWeixinChannel(c)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(c.Request.Context())
	ctx := c.Request.Context()
	// Start has no flowId route parameter, so use the id generated before the network call.
	f, err := scanWeixinFlow(tx.QueryRow(ctx, weixinFlowSelect+` WHERE flow_id=$1 AND channel_id=$2 FOR UPDATE`, original.ID, ch.ChannelID))
	if err != nil {
		return nil, wxError(409, "weixin_flow_superseded")
	}
	conn, err := scanWeixinConnection(tx.QueryRow(ctx, weixinConnectionSelect+` WHERE channel_id=$1`, ch.ChannelID))
	if err != nil {
		return nil, err
	}
	if f.Generation != conn.Generation || f.OperationID != original.OperationID {
		return nil, wxError(409, "weixin_flow_superseded")
	}
	f.OperationID = ""
	f.ErrorCode = ""
	if f.ExpiresAt <= nowMillis() {
		f.Status = "EXPIRED"
	} else if providerErr != nil {
		f.ErrorCode = "weixin_provider_unavailable"
		if starting {
			f.Status = "FAILED"
		}
	} else if starting {
		if result.Session.QRCode == "" || result.Session.PollingBaseURL == "" || result.QRCodeImage == "" {
			f.Status = "FAILED"
			f.ErrorCode = "weixin_invalid_provider_response"
		} else {
			f.Status = "WAITING_SCAN"
			f.Session, err = s.encryptWeixin(result.Session)
		}
	} else {
		switch strings.ToLower(result.Status) {
		case "wait":
			// A delayed waiting observation must not undo scan/verification progress.
		case "scaned", "scanned", "scaned_but_redirect":
			f.Status = "SCANNED"
		case "need_verifycode":
			f.Status = "NEED_VERIFY_CODE"
		case "expired":
			f.Status = "EXPIRED"
		case "binded_redirect":
			f.Status = "FAILED"
			f.ErrorCode = "weixin_already_linked"
		case "confirmed":
			if strings.TrimSpace(result.BotToken) == "" || strings.TrimSpace(result.AccountID) == "" || strings.TrimSpace(result.UserID) == "" {
				f.Status = "FAILED"
				f.ErrorCode = "weixin_invalid_provider_response"
			} else {
				f.Status = "AUTHORIZED"
				f.Credential, err = encryptAESGCM(s.vaultKey, result.BotToken)
				result.BotToken = ""
				result.QRCodeImage = ""
				if result.BaseURL == "" {
					result.BaseURL = result.Session.PollingBaseURL
				}
				result.Session = weixinSession{}
				if err == nil {
					f.Result, err = s.encryptWeixin(result)
				}
			}
		default:
			f.Status = "FAILED"
			f.ErrorCode = "weixin_invalid_provider_response"
		}
		if f.Status == "WAITING_SCAN" || f.Status == "SCANNED" || f.Status == "NEED_VERIFY_CODE" {
			if result.Session.QRCode == "" || result.Session.PollingBaseURL == "" {
				f.Status = "FAILED"
				f.ErrorCode = "weixin_invalid_provider_response"
			} else {
				f.Session, err = s.encryptWeixin(result.Session)
			}
		}
	}
	if err != nil {
		return nil, err
	}
	if f.Status == "AUTHORIZED" {
		f.Session = nil
	}
	if f.Status == "FAILED" || f.Status == "EXPIRED" {
		f.Session = nil
		f.Credential = nil
		f.Result = nil
	}
	_, err = tx.Exec(ctx, `UPDATE weixin_link_flows SET status=$2,error_code=$3,session_ciphertext=$4,credential_ciphertext=$5,result_ciphertext=$6,operation_id='',busy_until=0,next_operation_at=$7,updated_at=$8 WHERE flow_id=$1`, f.ID, f.Status, f.ErrorCode, f.Session, f.Credential, f.Result, nowMillis()+2000, nowMillis())
	if err != nil {
		return nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	out := f.public()
	if starting && f.Status == "WAITING_SCAN" {
		out["qrcodeImage"] = result.QRCodeImage
	}
	return out, nil
}
func (s *Server) weixinLinkStatus(c *gin.Context) (any, error) {
	tx, ch, err := s.lockWeixinChannel(c)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(c.Request.Context())
	f, err := s.ownedWeixinFlow(c, tx, ch)
	if err != nil {
		return nil, err
	}
	return f.public(), tx.Commit(c.Request.Context())
}
func (s *Server) cancelWeixinLink(c *gin.Context) (any, error) {
	tx, ch, err := s.lockWeixinChannel(c)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(c.Request.Context())
	f, err := s.ownedWeixinFlow(c, tx, ch)
	if err != nil {
		return nil, err
	}
	if f.Status == "COMPLETED" {
		return nil, wxError(409, "weixin_already_completed")
	}
	if f.Status != "FAILED" && f.Status != "EXPIRED" {
		_, err = tx.Exec(c.Request.Context(), `UPDATE weixin_link_flows SET status='CANCELLED',session_ciphertext=NULL,credential_ciphertext=NULL,result_ciphertext=NULL,operation_id='',busy_until=0,updated_at=$2 WHERE flow_id=$1`, f.ID, nowMillis())
		if err != nil {
			return nil, err
		}
		f.Status = "CANCELLED"
	}
	return f.public(), tx.Commit(c.Request.Context())
}

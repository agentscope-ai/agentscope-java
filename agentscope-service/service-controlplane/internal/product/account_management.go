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
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

const accountManagementSQL = `
ALTER TABLE users ADD COLUMN IF NOT EXISTS display_name TEXT NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN IF NOT EXISTS disabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE users ADD COLUMN IF NOT EXISTS auth_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS legacy_sessions_closed BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN IF NOT EXISTS preferences JSONB NOT NULL DEFAULT '{}';
CREATE TABLE IF NOT EXISTS account_access_audit (
 id BIGSERIAL PRIMARY KEY, user_id TEXT NOT NULL, actor TEXT NOT NULL,
 action TEXT NOT NULL, details JSONB NOT NULL DEFAULT '{}', created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS account_login_sessions (
 id TEXT PRIMARY KEY, user_id TEXT NOT NULL, user_agent TEXT NOT NULL DEFAULT '',
 created_at TIMESTAMPTZ NOT NULL, last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS account_login_sessions_user ON account_login_sessions(user_id);
`

type AccountSummary struct {
	UserID      string   `json:"userId"`
	Username    string   `json:"username"`
	DisplayName string   `json:"displayName"`
	Roles       []string `json:"roles"`
	Disabled    bool     `json:"disabled"`
	Version     int64    `json:"version"`
	CreatedAt   int64    `json:"createdAt"`
}

// LookupAccounts is an internal directory; callers must authorize discovery.
func (s *Server) LookupAccounts(ctx context.Context, query string, ids []string, limit int) ([]AccountSummary, error) {
	if s == nil || s.db == nil {
		return nil, fmt.Errorf("account directory unavailable")
	}
	if limit <= 0 || limit > 1000 {
		limit = 50
	}
	rows, err := s.db.Pool.Query(ctx, `SELECT user_id,username,display_name,roles_csv,disabled,version,created_at FROM users WHERE ($1='' OR username ILIKE '%'||$1||'%' OR display_name ILIKE '%'||$1||'%' OR user_id=$1) AND (COALESCE(cardinality($2::text[]),0)=0 OR user_id=ANY($2::text[])) ORDER BY lower(username),user_id LIMIT $3`, strings.TrimSpace(query), ids, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []AccountSummary{}
	for rows.Next() {
		var a AccountSummary
		var roles string
		if err := rows.Scan(&a.UserID, &a.Username, &a.DisplayName, &roles, &a.Disabled, &a.Version, &a.CreatedAt); err != nil {
			return nil, err
		}
		a.Roles = splitRoles(roles)
		items = append(items, a)
	}
	return items, rows.Err()
}

func validPlatformRoles(roles []string) bool {
	if len(roles) == 0 || len(roles) > 4 {
		return false
	}
	seen := map[string]bool{}
	for _, role := range roles {
		if seen[role] || !slices.Contains([]string{"user", "admin", "agent_developer", "operator"}, role) {
			return false
		}
		seen[role] = true
	}
	return true
}

func sessionFingerprint(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}
func requestSession(c *gin.Context) string {
	return sessionFingerprint(strings.TrimPrefix(c.GetHeader("Authorization"), "Bearer "))
}

func (s *Server) registerAccountManagement(r gin.IRouter) {
	r.GET("/api/user/channel-connections", s.myChannelConnections)
	r.DELETE("/api/user/channel-connections", s.removeMyChannelIdentity)
	r.DELETE("/api/user/channel-subscriptions/:subscriptionId", s.unsubscribeMyChannel)
	r.PATCH("/api/admin/users/:id/status", s.adminAccountStatus)
	r.GET("/api/admin/access-audit", s.accountAudit)
	r.PUT("/api/user/profile", s.updateProfile)
	r.GET("/api/user/login-sessions", s.listLoginSessions)
	r.DELETE("/api/user/login-sessions/:sessionId", s.revokeLoginSession)
	r.POST("/api/user/login-sessions/revoke-others", s.revokeOtherLoginSessions)
	r.POST("/api/auth/logout", s.logoutAccount)
}

func (s *Server) adminAccountStatus(c *gin.Context) {
	if !s.requireAdmin(c) {
		return
	}
	var in struct {
		Disabled *bool `json:"disabled"`
		Version  int64 `json:"version"`
	}
	if c.ShouldBindJSON(&in) != nil || in.Disabled == nil || in.Version <= 0 {
		writeErr(c, 400, "disabled and expected version are required")
		return
	}
	s.mutateAccount(c, in.Version, nil, in.Disabled)
}

// All privilege/status mutations serialize the last-admin check with the write.
func (s *Server) mutateAccount(c *gin.Context, version int64, roles []string, disabled *bool) {
	id := c.Param("id")
	if disabled != nil && *disabled {
		if id == currentUserID(c) {
			writeErr(c, 400, "You cannot disable your own account")
			return
		}
		if s.accountDisableGuard != nil {
			if err := s.accountDisableGuard(c.Request.Context(), id); err != nil {
				writeErr(c, 409, err.Error())
				return
			}
		}
	}
	tx, err := s.db.Pool.Begin(c.Request.Context())
	if err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	defer tx.Rollback(c.Request.Context())
	if _, err = tx.Exec(c.Request.Context(), `SELECT pg_advisory_xact_lock(hashtext('account-access-administration'))`); err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	var csv string
	var oldDisabled bool
	var oldVersion int64
	if err = tx.QueryRow(c.Request.Context(), `SELECT roles_csv,disabled,version FROM users WHERE user_id=$1 FOR UPDATE`, id).Scan(&csv, &oldDisabled, &oldVersion); err != nil {
		writeErr(c, 404, "Account not found")
		return
	}
	if version != oldVersion {
		writeErr(c, 409, "Account changed; refresh before saving")
		return
	}
	if roles == nil {
		roles = splitRoles(csv)
	}
	nextDisabled := oldDisabled
	if disabled != nil {
		nextDisabled = *disabled
	}
	if hasRole(splitRoles(csv), "admin") && !oldDisabled && (nextDisabled || !hasRole(roles, "admin")) {
		var count int
		if err = tx.QueryRow(c.Request.Context(), `SELECT count(*) FROM users WHERE disabled=false AND 'admin'=ANY(string_to_array(lower(roles_csv),','))`).Scan(&count); err != nil {
			writeErr(c, 500, err.Error())
			return
		}
		if count <= 1 {
			writeErr(c, 409, "Keep at least one active platform administrator")
			return
		}
	}
	_, err = tx.Exec(c.Request.Context(), `UPDATE users SET roles_csv=$2,disabled=$3,version=version+1,auth_version=auth_version+CASE WHEN disabled<>$3 THEN 1 ELSE 0 END WHERE user_id=$1`, id, joinRoles(roles), nextDisabled)
	if err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	action := "roles.updated"
	if disabled != nil {
		action = "account.enabled"
		if nextDisabled {
			action = "account.disabled"
		}
	}
	details, _ := json.Marshal(gin.H{"roles": roles, "disabled": nextDisabled, "previousRoles": splitRoles(csv), "previousDisabled": oldDisabled})
	_, err = tx.Exec(c.Request.Context(), `INSERT INTO account_access_audit(user_id,actor,action,details) VALUES($1,$2,$3,$4)`, id, currentUserID(c), action, details)
	if err == nil && disabled != nil && nextDisabled {
		_, err = tx.Exec(c.Request.Context(), `UPDATE account_login_sessions SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL`, id)
	}
	if err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	if err = tx.Commit(c.Request.Context()); err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	s.respondAccount(c, id)
}

func (s *Server) respondAccount(c *gin.Context, id string) {
	items, err := s.LookupAccounts(c.Request.Context(), "", []string{id}, 1)
	if err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	if len(items) == 0 {
		writeErr(c, 404, "Account not found")
		return
	}
	c.JSON(200, items[0])
}

func (s *Server) accountAudit(c *gin.Context) {
	if !s.requireAdmin(c) {
		return
	}
	rows, err := s.db.Pool.Query(c.Request.Context(), `SELECT id,user_id,actor,action,details,created_at FROM account_access_audit WHERE ($1='' OR user_id=$1) ORDER BY id DESC LIMIT 50 OFFSET $2`, c.Query("userId"), accountAuditOffset(c))
	if err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	defer rows.Close()
	items := []gin.H{}
	for rows.Next() {
		var id int64
		var user, actor, action string
		var details json.RawMessage
		var at time.Time
		if err = rows.Scan(&id, &user, &actor, &action, &details, &at); err != nil {
			writeErr(c, 500, err.Error())
			return
		}
		items = append(items, gin.H{"id": id, "userId": user, "actor": actor, "action": action, "details": details, "createdAt": at})
	}
	if rows.Err() != nil {
		writeErr(c, 500, rows.Err().Error())
		return
	}
	c.JSON(200, gin.H{"items": items})
}

func (s *Server) updateProfile(c *gin.Context) {
	var in struct {
		DisplayName string `json:"displayName"`
	}
	if c.ShouldBindJSON(&in) != nil || len(strings.TrimSpace(in.DisplayName)) > 100 {
		writeErr(c, 400, "Display name must be at most 100 characters")
		return
	}
	_, err := s.db.Pool.Exec(c.Request.Context(), `UPDATE users SET display_name=$2,version=version+1 WHERE user_id=$1`, currentUserID(c), strings.TrimSpace(in.DisplayName))
	if err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	s.respondAccount(c, currentUserID(c))
}

func (s *Server) listLoginSessions(c *gin.Context) {
	rows, err := s.db.Pool.Query(c.Request.Context(), `SELECT id,user_agent,created_at,last_seen_at,expires_at FROM account_login_sessions WHERE user_id=$1 AND revoked_at IS NULL AND expires_at>now() ORDER BY last_seen_at DESC LIMIT 100`, currentUserID(c))
	if err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	defer rows.Close()
	items := []gin.H{}
	for rows.Next() {
		var id, agent string
		var created, seen, expires time.Time
		if err = rows.Scan(&id, &agent, &created, &seen, &expires); err != nil {
			writeErr(c, 500, err.Error())
			return
		}
		items = append(items, gin.H{"id": id, "userAgent": agent, "createdAt": created, "lastSeenAt": seen, "expiresAt": expires, "current": id == requestSession(c)})
	}
	if rows.Err() != nil {
		writeErr(c, 500, rows.Err().Error())
		return
	}
	c.JSON(200, gin.H{"items": items})
}
func (s *Server) revokeLoginSession(c *gin.Context) {
	tag, err := s.db.Pool.Exec(c.Request.Context(), `UPDATE account_login_sessions SET revoked_at=now() WHERE id=$1 AND user_id=$2`, c.Param("sessionId"), currentUserID(c))
	if err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, 404, "Login session not found")
		return
	}
	c.Status(204)
}
func (s *Server) revokeOtherLoginSessions(c *gin.Context) {
	tx, err := s.db.Pool.Begin(c.Request.Context())
	if err != nil {
		writeErr(c, 500, "Unable to revoke login sessions")
		return
	}
	defer tx.Rollback(c.Request.Context())
	_, err = tx.Exec(c.Request.Context(), `UPDATE users SET legacy_sessions_closed=true WHERE user_id=$1`, currentUserID(c))
	if err == nil {
		_, err = tx.Exec(c.Request.Context(), `UPDATE account_login_sessions SET revoked_at=now() WHERE user_id=$1 AND id<>$2 AND revoked_at IS NULL`, currentUserID(c), requestSession(c))
	}
	if err == nil {
		err = tx.Commit(c.Request.Context())
	}
	if err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	c.Status(204)
}
func (s *Server) logoutAccount(c *gin.Context) {
	_, err := s.db.Pool.Exec(c.Request.Context(), `UPDATE account_login_sessions SET revoked_at=now() WHERE user_id=$1 AND id=$2`, currentUserID(c), requestSession(c))
	if err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	c.Status(204)
}

func (s *Server) GetAccountPreferences(ctx context.Context, user string) (map[string]string, error) {
	var raw []byte
	if err := s.db.Pool.QueryRow(ctx, `SELECT preferences FROM users WHERE user_id=$1`, user).Scan(&raw); err != nil {
		return nil, err
	}
	var values map[string]string
	err := json.Unmarshal(raw, &values)
	return values, err
}
func (s *Server) SetAccountPreferences(ctx context.Context, user string, values map[string]string) error {
	raw, err := json.Marshal(values)
	if err != nil {
		return err
	}
	_, err = s.db.Pool.Exec(ctx, `UPDATE users SET preferences=$2 WHERE user_id=$1`, user, raw)
	return err
}

func accountAuditOffset(c *gin.Context) int {
	value, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	if value < 0 {
		return 0
	}
	return value
}

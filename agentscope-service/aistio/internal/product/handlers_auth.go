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
	"net/http"

	"github.com/gin-gonic/gin"
)

func (s *Server) registerAuth(r gin.IRouter) {
	s.registerAccountManagement(r)
	r.POST("/api/auth/login", s.login)
	r.GET("/api/auth/me", s.me)
	r.GET("/api/user/profile", s.profile)
	r.POST("/api/user/change-password", s.changePassword)
}

type loginReq struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

func (s *Server) login(c *gin.Context) {
	var req loginReq
	if err := c.ShouldBindJSON(&req); err != nil || req.Username == "" || req.Password == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "username and password required"})
		return
	}
	var userID, hash, rolesCSV, username string
	var disabled bool
	var version int64
	err := s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT user_id, password_hash, roles_csv, username, disabled, auth_version FROM users WHERE LOWER(username)=LOWER($1)`,
		req.Username).Scan(&userID, &hash, &rolesCSV, &username, &disabled, &version)
	if err != nil || disabled || !checkPassword(hash, req.Password) {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid credentials"})
		return
	}
	roles := splitRoles(rolesCSV)
	token, err := issueAccountToken(s.cfg.JWTSecret, userID, username, roles, version)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	claims, err := s.VerifyToken(token)
	if err == nil {
		_, err = s.db.Pool.Exec(c.Request.Context(), `INSERT INTO account_login_sessions(id,user_id,created_at,expires_at,user_agent) VALUES($1,$2,$3,$4,$5)`, sessionFingerprint(token), userID, claims.IssuedAt.Time, claims.ExpiresAt.Time, c.Request.UserAgent())
	}
	if err == nil {
		_, err = s.VerifyAccountToken(c.Request.Context(), token)
	}
	if err != nil {
		writeErr(c, 500, "Unable to create login session")
		return
	}
	_, err = s.db.Pool.Exec(c.Request.Context(), `UPDATE account_login_sessions SET user_agent=$2 WHERE id=$1`, sessionFingerprint(token), c.Request.UserAgent())
	if err != nil {
		writeErr(c, 500, "Unable to create login session")
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"token":    token,
		"userId":   userID,
		"username": username,
		"roles":    roles,
	})
}

func (s *Server) me(c *gin.Context) {
	roles := currentRoles(c)
	c.JSON(http.StatusOK, gin.H{
		"userId":      currentUserID(c),
		"username":    currentUsername(c),
		"roles":       roles,
		"isAdmin":     hasRole(roles, "admin"),
		"aiAvailable": false,
	})
}

func (s *Server) profile(c *gin.Context) { s.respondAccount(c, currentUserID(c)) }

type changePasswordReq struct {
	CurrentPassword string `json:"currentPassword"`
	NewPassword     string `json:"newPassword"`
}

func (s *Server) changePassword(c *gin.Context) {
	var req changePasswordReq
	if err := c.ShouldBindJSON(&req); err != nil || len(req.NewPassword) < 6 {
		c.String(http.StatusBadRequest, "currentPassword and newPassword required")
		return
	}
	userID := currentUserID(c)
	var hash string
	err := s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT password_hash FROM users WHERE user_id=$1`, userID).Scan(&hash)
	if err != nil || !checkPassword(hash, req.CurrentPassword) {
		c.String(http.StatusBadRequest, "current password incorrect")
		return
	}
	newHash, err := hashPassword(req.NewPassword)
	if err != nil {
		c.String(http.StatusInternalServerError, err.Error())
		return
	}
	tx, err := s.db.Pool.Begin(c.Request.Context())
	if err != nil {
		writeErr(c, 500, "Unable to change password")
		return
	}
	defer tx.Rollback(c.Request.Context())
	_, err = tx.Exec(c.Request.Context(), `UPDATE users SET password_hash=$1,version=version+1,legacy_sessions_closed=true WHERE user_id=$2`, newHash, userID)
	if err == nil {
		_, err = tx.Exec(c.Request.Context(), `UPDATE account_login_sessions SET revoked_at=now() WHERE user_id=$1 AND id<>$2 AND revoked_at IS NULL`, userID, requestSession(c))
	}
	if err == nil {
		_, err = tx.Exec(c.Request.Context(), `INSERT INTO account_access_audit(user_id,actor,action) VALUES($1,$1,'password.changed')`, userID)
	}
	if err != nil {
		writeErr(c, 500, "Unable to change password")
		return
	}
	if err = tx.Commit(c.Request.Context()); err != nil {
		writeErr(c, 500, "Unable to change password")
		return
	}
	c.Status(http.StatusNoContent)
}

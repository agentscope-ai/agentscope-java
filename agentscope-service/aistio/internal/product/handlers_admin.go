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
	"crypto/rand"
	"math/big"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

func (s *Server) registerAdmin(r gin.IRouter) {
	r.GET("/api/admin/users", s.adminListUsers)
	r.POST("/api/admin/users", s.adminCreateUser)
	r.PATCH("/api/admin/users/:id/password", s.adminResetPassword)
	r.PATCH("/api/admin/users/:id/roles", s.adminUpdateRoles)
	r.DELETE("/api/admin/users/:id", s.adminDeleteUser)
}

func (s *Server) requireAdmin(c *gin.Context) bool {
	if !hasRole(currentRoles(c), "admin") {
		writeErr(c, http.StatusForbidden, "Admin role required")
		return false
	}
	return true
}

func adminUserView(userID, username, rolesCSV string) gin.H {
	return gin.H{
		"userId":   userID,
		"username": username,
		"roles":    splitRoles(rolesCSV),
	}
}

func (s *Server) adminListUsers(c *gin.Context) {
	if !s.requireAdmin(c) {
		return
	}
	items, err := s.LookupAccounts(c.Request.Context(), c.Query("q"), nil, 1000)
	if err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	c.JSON(200, items)
}

func (s *Server) adminCreateUser(c *gin.Context) {
	if !s.requireAdmin(c) {
		return
	}
	var req struct {
		Username        string   `json:"username"`
		InitialPassword string   `json:"initialPassword"`
		Roles           []string `json:"roles"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || strings.TrimSpace(req.Username) == "" {
		writeTextErr(c, http.StatusBadRequest, "username is required")
		return
	}
	username := strings.TrimSpace(req.Username)
	if len(username) > 100 {
		writeErr(c, 400, "Username must be at most 100 characters")
		return
	}
	roles := req.Roles
	if len(roles) == 0 {
		roles = []string{"user"}
	}
	if !validPlatformRoles(roles) {
		writeErr(c, 400, "Unknown or duplicate platform role")
		return
	}
	generated := strings.TrimSpace(req.InitialPassword) == ""
	password := req.InitialPassword
	if !generated && len(password) < 6 {
		writeErr(c, 400, "Password must be at least six characters")
		return
	}
	if generated {
		password = generateTempPassword()
	}
	hash, err := hashPassword(password)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	userID := makeUserID(username)
	now := nowMillis()
	tx, err := s.db.Pool.Begin(c.Request.Context())
	if err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	defer tx.Rollback(c.Request.Context())
	_, err = tx.Exec(c.Request.Context(),
		`INSERT INTO users (user_id, username, password_hash, roles_csv, created_at)
		 VALUES ($1,$2,$3,$4,$5)`,
		userID, username, hash, strings.Join(roles, ","), now)
	if err != nil {
		if strings.Contains(err.Error(), "duplicate") || strings.Contains(err.Error(), "unique") {
			writeTextErr(c, http.StatusConflict, "username already exists")
			return
		}
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	_, err = tx.Exec(c.Request.Context(), `INSERT INTO account_access_audit(user_id,actor,action,details) VALUES($1,$2,'account.created',$3)`, userID, currentUserID(c), mustJSON(gin.H{"roles": roles}))
	if err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	if err = tx.Commit(c.Request.Context()); err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	accounts, err := s.LookupAccounts(c.Request.Context(), "", []string{userID}, 1)
	if err != nil || len(accounts) == 0 {
		writeErr(c, 500, "Unable to load the created account")
		return
	}
	out := gin.H{"user": accounts[0]}
	if generated {
		out["generatedPassword"] = password
	}
	c.JSON(http.StatusOK, out)
}

func (s *Server) adminResetPassword(c *gin.Context) {
	if !s.requireAdmin(c) {
		return
	}
	var in struct {
		NewPassword string `json:"newPassword"`
	}
	if c.ShouldBindJSON(&in) != nil || len(in.NewPassword) < 6 {
		writeErr(c, 400, "Password must be at least six characters")
		return
	}
	hash, err := hashPassword(in.NewPassword)
	if err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	tx, err := s.db.Pool.Begin(c.Request.Context())
	if err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	defer tx.Rollback(c.Request.Context())
	tag, err := tx.Exec(c.Request.Context(), `UPDATE users SET password_hash=$1,auth_version=auth_version+1,version=version+1 WHERE user_id=$2`, hash, c.Param("id"))
	if err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, 404, "Account not found")
		return
	}
	_, err = tx.Exec(c.Request.Context(), `UPDATE account_login_sessions SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL`, c.Param("id"))
	if err == nil {
		_, err = tx.Exec(c.Request.Context(), `INSERT INTO account_access_audit(user_id,actor,action) VALUES($1,$2,'password.reset')`, c.Param("id"), currentUserID(c))
	}
	if err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	if err = tx.Commit(c.Request.Context()); err != nil {
		writeErr(c, 500, err.Error())
		return
	}
	s.respondAccount(c, c.Param("id"))
}

func (s *Server) adminUpdateRoles(c *gin.Context) {
	if !s.requireAdmin(c) {
		return
	}
	var in struct {
		Roles   []string `json:"roles"`
		Version int64    `json:"version"`
	}
	if c.ShouldBindJSON(&in) != nil || in.Version <= 0 || !validPlatformRoles(in.Roles) {
		writeErr(c, 400, "Valid platform roles and expected version are required")
		return
	}
	s.mutateAccount(c, in.Version, in.Roles, nil)
}

// Keep account IDs in historical work; the legacy delete route now deactivates.
func (s *Server) adminDeleteUser(c *gin.Context) {
	if !s.requireAdmin(c) {
		return
	}
	var in struct {
		Version int64 `json:"version"`
	}
	if c.ShouldBindJSON(&in) != nil || in.Version <= 0 {
		writeErr(c, 400, "Expected version is required to deactivate an account")
		return
	}
	disabled := true
	s.mutateAccount(c, in.Version, nil, &disabled)
}

func makeUserID(username string) string {
	sanitised := strings.ToLower(strings.TrimSpace(username))
	var b strings.Builder
	for _, r := range sanitised {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '_' || r == '-' {
			b.WriteRune(r)
		} else {
			b.WriteByte('-')
		}
	}
	suffix := strings.ReplaceAll(uuid.New().String(), "-", "")
	if len(suffix) > 6 {
		suffix = suffix[:6]
	}
	return b.String() + "-" + suffix
}

func generateTempPassword() string {
	const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
	out := make([]byte, 12)
	for i := range out {
		n, err := rand.Int(rand.Reader, big.NewInt(int64(len(alphabet))))
		if err != nil {
			out[i] = alphabet[i%len(alphabet)]
			continue
		}
		out[i] = alphabet[n.Int64()]
	}
	return string(out)
}

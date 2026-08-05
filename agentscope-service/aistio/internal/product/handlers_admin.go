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
	rows, err := s.db.Pool.Query(c.Request.Context(),
		`SELECT user_id, username, roles_csv FROM users ORDER BY username`)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []gin.H{}
	for rows.Next() {
		var id, username, rolesCSV string
		if err := rows.Scan(&id, &username, &rolesCSV); err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		list = append(list, adminUserView(id, username, rolesCSV))
	}
	c.JSON(http.StatusOK, list)
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
	roles := req.Roles
	if len(roles) == 0 {
		roles = []string{"user"}
	}
	generated := strings.TrimSpace(req.InitialPassword) == ""
	password := strings.TrimSpace(req.InitialPassword)
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
	_, err = s.db.Pool.Exec(c.Request.Context(),
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
	out := gin.H{"user": adminUserView(userID, username, strings.Join(roles, ","))}
	if generated {
		out["generatedPassword"] = password
	}
	c.JSON(http.StatusOK, out)
}

func (s *Server) adminResetPassword(c *gin.Context) {
	if !s.requireAdmin(c) {
		return
	}
	var req struct {
		NewPassword string `json:"newPassword"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || strings.TrimSpace(req.NewPassword) == "" {
		writeTextErr(c, http.StatusBadRequest, "newPassword is required")
		return
	}
	hash, err := hashPassword(req.NewPassword)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE users SET password_hash=$1 WHERE user_id=$2`, hash, c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "User not found: "+c.Param("id"))
		return
	}
	var username, rolesCSV string
	_ = s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT username, roles_csv FROM users WHERE user_id=$1`, c.Param("id")).Scan(&username, &rolesCSV)
	c.JSON(http.StatusOK, adminUserView(c.Param("id"), username, rolesCSV))
}

func (s *Server) adminUpdateRoles(c *gin.Context) {
	if !s.requireAdmin(c) {
		return
	}
	var req struct {
		Roles []string `json:"roles"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || len(req.Roles) == 0 {
		writeTextErr(c, http.StatusBadRequest, "roles must contain at least one entry")
		return
	}
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE users SET roles_csv=$1 WHERE user_id=$2`, strings.Join(req.Roles, ","), c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "User not found: "+c.Param("id"))
		return
	}
	var username, rolesCSV string
	_ = s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT username, roles_csv FROM users WHERE user_id=$1`, c.Param("id")).Scan(&username, &rolesCSV)
	c.JSON(http.StatusOK, adminUserView(c.Param("id"), username, rolesCSV))
}

func (s *Server) adminDeleteUser(c *gin.Context) {
	if !s.requireAdmin(c) {
		return
	}
	userID := c.Param("id")
	if userID == currentUserID(c) {
		writeTextErr(c, http.StatusConflict, "Cannot delete yourself")
		return
	}
	tag, err := s.db.Pool.Exec(c.Request.Context(), `DELETE FROM users WHERE user_id=$1`, userID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "User not found: "+userID)
		return
	}
	_, _ = s.db.Pool.Exec(c.Request.Context(),
		`DELETE FROM agent_shares WHERE grantee_type='USER' AND grantee_id=$1`, userID)
	c.Status(http.StatusNoContent)
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

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
	var userID, hash, rolesCSV string
	err := s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT user_id, password_hash, roles_csv FROM users WHERE LOWER(username)=LOWER($1)`,
		req.Username).Scan(&userID, &hash, &rolesCSV)
	if err != nil || !checkPassword(hash, req.Password) {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid credentials"})
		return
	}
	roles := splitRoles(rolesCSV)
	token, err := issueToken(s.cfg.JWTSecret, userID, req.Username, roles)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"token":    token,
		"userId":   userID,
		"username": req.Username,
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

func (s *Server) profile(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"userId":   currentUserID(c),
		"username": currentUsername(c),
		"roles":    currentRoles(c),
	})
}

type changePasswordReq struct {
	CurrentPassword string `json:"currentPassword"`
	NewPassword     string `json:"newPassword"`
}

func (s *Server) changePassword(c *gin.Context) {
	var req changePasswordReq
	if err := c.ShouldBindJSON(&req); err != nil || req.NewPassword == "" {
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
	_, err = s.db.Pool.Exec(c.Request.Context(),
		`UPDATE users SET password_hash=$1 WHERE user_id=$2`, newHash, userID)
	if err != nil {
		c.String(http.StatusInternalServerError, err.Error())
		return
	}
	c.Status(http.StatusNoContent)
}

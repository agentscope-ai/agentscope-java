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

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

const LocalDeveloperID = "local-developer"

func LocalDeveloperClaims() *Claims {
	return &Claims{Username: LocalDeveloperID, Roles: []string{"user", "admin"},
		RegisteredClaims: jwt.RegisteredClaims{Subject: LocalDeveloperID}}
}

func seedLocalDeveloper(ctx context.Context, db *DB) error {
	// No usable password or login session is created. Turning local mode off
	// must not make the automatically issued development token a production login.
	_, err := db.Pool.Exec(ctx, `INSERT INTO users(user_id,username,password_hash,roles_csv,created_at,legacy_sessions_closed)
		VALUES($1,$1,'','user,admin',$2,true) ON CONFLICT(user_id) DO NOTHING`, LocalDeveloperID, nowMillis())
	return err
}

func (s *Server) localDevSession(c *gin.Context) {
	if !s.cfg.LocalDev {
		c.Status(404)
		return
	}
	claims := LocalDeveloperClaims()
	token, err := issueToken(s.cfg.JWTSecret, claims.Subject, claims.Username, claims.Roles)
	if err != nil {
		writeErr(c, 500, "Unable to create development session")
		return
	}
	c.Header("Cache-Control", "no-store")
	c.JSON(200, gin.H{"token": token, "userId": claims.Subject, "username": claims.Username, "roles": claims.Roles})
}

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
	"net/http"

	"github.com/gin-gonic/gin"
)

func (s *Server) registerEnvironments(r gin.IRouter) {
	r.GET("/api/environments", s.listEnvironments)
	r.POST("/api/environments", s.createEnvironment)
	r.GET("/api/environments/:id", s.getEnvironment)
	r.PATCH("/api/environments/:id", s.updateEnvironment)
	r.DELETE("/api/environments/:id", s.deleteEnvironment)
	r.POST("/api/environments/:id/archive", s.archiveEnvironment)
	r.POST("/api/environments/:id/rotate-key", s.rotateEnvironmentKey)
}

type envCreateReq struct {
	Name   string `json:"name"`
	Type   string `json:"type"`
	Config any    `json:"config"`
}

type envUpdateReq struct {
	Name   *string `json:"name"`
	Config any     `json:"config"`
}

type envRow struct {
	EnvironmentID string
	OwnerID       string
	Name          string
	Type          string
	ConfigJSON    *string
	ArchivedAt    *int64
	CreatedAt     int64
	UpdatedAt     int64
}

func (e envRow) toJSON() gin.H {
	return gin.H{
		"id":         e.EnvironmentID,
		"name":       e.Name,
		"type":       e.Type,
		"config":     parseJSONRaw(deref(e.ConfigJSON)),
		"ownerId":    e.OwnerID,
		"archivedAt": nullMillis(e.ArchivedAt),
		"createdAt":  e.CreatedAt,
		"updatedAt":  e.UpdatedAt,
	}
}

const envSelect = `SELECT environment_id, owner_id, name, type, config_json, archived_at, created_at, updated_at FROM environments`

func (s *Server) loadEnv(ctx context.Context, id string) (envRow, error) {
	var e envRow
	err := s.db.Pool.QueryRow(ctx, envSelect+` WHERE environment_id=$1`, id).Scan(
		&e.EnvironmentID, &e.OwnerID, &e.Name, &e.Type, &e.ConfigJSON, &e.ArchivedAt, &e.CreatedAt, &e.UpdatedAt)
	return e, err
}

func (s *Server) listEnvironments(c *gin.Context) {
	owner := currentUserID(c)
	limit, offset, ok := pageParams(c)
	if !ok {
		writeErr(c, http.StatusBadRequest, "invalid limit/offset")
		return
	}
	var total int64
	if err := s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT COUNT(*) FROM environments WHERE owner_id=$1 AND archived_at IS NULL`, owner).Scan(&total); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	writeTotalCount(c, total)
	q := envSelect + ` WHERE owner_id=$1 AND archived_at IS NULL ORDER BY updated_at DESC`
	args := []any{owner}
	q, args = appendPage(q, limit, offset, args)
	rows, err := s.db.Pool.Query(c.Request.Context(), q, args...)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []gin.H{}
	for rows.Next() {
		var e envRow
		if err := rows.Scan(&e.EnvironmentID, &e.OwnerID, &e.Name, &e.Type, &e.ConfigJSON, &e.ArchivedAt, &e.CreatedAt, &e.UpdatedAt); err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		list = append(list, e.toJSON())
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) createEnvironment(c *gin.Context) {
	var req envCreateReq
	if err := c.ShouldBindJSON(&req); err != nil || req.Name == "" {
		writeTextErr(c, http.StatusBadRequest, "name required")
		return
	}
	typ := req.Type
	if typ == "" {
		typ = "local"
	}
	id := shortID("env_")
	now := nowMillis()
	owner := currentUserID(c)
	plainKey := shortID("ek_")
	keyHash := sha256Hex(plainKey)
	_, err := s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO environments (environment_id, owner_id, name, type, config_json, api_key_hash, created_at, updated_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$7)`,
		id, owner, req.Name, typ, mustJSON(req.Config), keyHash, now)
	if err != nil {
		writeTextErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	e, _ := s.loadEnv(c.Request.Context(), id)
	out := e.toJSON()
	out["apiKey"] = plainKey // returned once at create
	c.JSON(http.StatusOK, out)
}

func (s *Server) getEnvironment(c *gin.Context) {
	e, err := s.loadEnv(c.Request.Context(), c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "environment not found")
		return
	}
	if e.OwnerID != currentUserID(c) {
		writeErr(c, http.StatusNotFound, "environment not found")
		return
	}
	c.JSON(http.StatusOK, e.toJSON())
}

// updateEnvironment changes the mutable parts of an environment. The type is
// immutable because running sessions resolve their sandbox from it.
func (s *Server) updateEnvironment(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	e, err := s.loadEnv(c.Request.Context(), id)
	if err != nil || e.OwnerID != owner {
		writeErr(c, http.StatusNotFound, "environment not found")
		return
	}
	if e.ArchivedAt != nil {
		writeErr(c, http.StatusConflict, "environment is archived")
		return
	}
	var req envUpdateReq
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	name := e.Name
	if req.Name != nil {
		if *req.Name == "" {
			writeErr(c, http.StatusBadRequest, "name cannot be empty")
			return
		}
		name = *req.Name
	}
	configJSON := deref(e.ConfigJSON)
	if req.Config != nil {
		configJSON = mustJSON(req.Config)
	}
	now := nowMillis()
	if _, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE environments SET name=$1, config_json=$2, updated_at=$3
		 WHERE environment_id=$4 AND owner_id=$5`, name, configJSON, now, id, owner); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	out, _ := s.loadEnv(c.Request.Context(), id)
	c.JSON(http.StatusOK, out.toJSON())
}

func (s *Server) deleteEnvironment(c *gin.Context) {
	owner := currentUserID(c)
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`DELETE FROM environments WHERE environment_id=$1 AND owner_id=$2`, c.Param("id"), owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "environment not found")
		return
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) archiveEnvironment(c *gin.Context) {
	owner := currentUserID(c)
	now := nowMillis()
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE environments SET archived_at=$1, updated_at=$1
		 WHERE environment_id=$2 AND owner_id=$3 AND archived_at IS NULL`,
		now, c.Param("id"), owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "environment not found")
		return
	}
	e, _ := s.loadEnv(c.Request.Context(), c.Param("id"))
	c.JSON(http.StatusOK, e.toJSON())
}

func (s *Server) rotateEnvironmentKey(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	plainKey := shortID("ek_")
	keyHash := sha256Hex(plainKey)
	now := nowMillis()
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE environments SET api_key_hash=$1, updated_at=$2
		 WHERE environment_id=$3 AND owner_id=$4 AND archived_at IS NULL`,
		keyHash, now, id, owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "environment not found")
		return
	}
	e, _ := s.loadEnv(c.Request.Context(), id)
	out := e.toJSON()
	out["apiKey"] = plainKey
	c.JSON(http.StatusOK, out)
}

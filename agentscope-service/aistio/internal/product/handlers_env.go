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
	r.DELETE("/api/environments/:id", s.deleteEnvironment)
	r.POST("/api/environments/:id/archive", s.archiveEnvironment)
	r.POST("/api/environments/:id/rotate-key", s.rotateEnvironmentKey)
}

type envCreateReq struct {
	Name   string `json:"name"`
	Type   string `json:"type"`
	Config any    `json:"config"`
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
	rows, err := s.db.Pool.Query(c.Request.Context(),
		envSelect+` WHERE owner_id=$1 AND archived_at IS NULL ORDER BY updated_at DESC`, owner)
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

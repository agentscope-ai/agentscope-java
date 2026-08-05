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
	"fmt"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

func (s *Server) registerMemory(r gin.IRouter) {
	r.GET("/api/memory-stores", s.listMemoryStores)
	r.POST("/api/memory-stores", s.createMemoryStore)
	r.GET("/api/memory-stores/:id", s.getMemoryStore)
	r.DELETE("/api/memory-stores/:id", s.deleteMemoryStore)
	r.POST("/api/memory-stores/:id/archive", s.archiveMemoryStore)
	r.POST("/api/memory-stores/:id/redact", s.redactMemory)
	r.GET("/api/memory-stores/:id/memories", s.listMemories)
	// Memory paths are catch-alls, so the version history route cannot be a
	// sibling literal segment; it is dispatched from the same wildcard.
	r.GET("/api/memory-stores/:id/memories/*path", s.readMemory)
	r.PUT("/api/memory-stores/:id/memories/*path", s.putMemory)
	r.DELETE("/api/memory-stores/:id/memories/*path", s.deleteMemory)
}

const memoryVersionsPrefix = "versions/"

// readMemory serves both the memory content and, under the `versions/`
// prefix, its version history.
func (s *Server) readMemory(c *gin.Context) {
	if strings.HasPrefix(memoryPath(c), memoryVersionsPrefix) {
		s.listMemoryVersions(c)
		return
	}
	s.getMemory(c)
}

type memStoreReq struct {
	Name        string `json:"name"`
	Description string `json:"description"`
}

func memoryPath(c *gin.Context) string {
	p := c.Param("path")
	return strings.TrimPrefix(p, "/")
}

func (s *Server) listMemoryStores(c *gin.Context) {
	owner := currentUserID(c)
	limit, offset, ok := pageParams(c)
	if !ok {
		writeErr(c, http.StatusBadRequest, "invalid limit/offset")
		return
	}
	var total int64
	if err := s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT COUNT(*) FROM memory_stores WHERE owner_id=$1 AND archived_at IS NULL`, owner).Scan(&total); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	writeTotalCount(c, total)
	q := `SELECT store_id, owner_id, name, description, created_at, updated_at
		 FROM memory_stores WHERE owner_id=$1 AND archived_at IS NULL ORDER BY updated_at DESC`
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
		var id, oid, name string
		var desc *string
		var created, updated int64
		if err := rows.Scan(&id, &oid, &name, &desc, &created, &updated); err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		d := ""
		if desc != nil {
			d = *desc
		}
		list = append(list, gin.H{
			"id": id, "ownerId": oid, "name": name, "description": d,
			"createdAt": created, "updatedAt": updated,
		})
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) createMemoryStore(c *gin.Context) {
	var req memStoreReq
	if err := c.ShouldBindJSON(&req); err != nil || req.Name == "" {
		writeTextErr(c, http.StatusBadRequest, "name required")
		return
	}
	id := shortID("ms_")
	now := nowMillis()
	owner := currentUserID(c)
	_, err := s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO memory_stores (store_id, owner_id, name, description, created_at, updated_at)
		 VALUES ($1,$2,$3,$4,$5,$5)`, id, owner, req.Name, nullStr(req.Description), now)
	if err != nil {
		writeTextErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"id": id, "ownerId": owner, "name": req.Name, "description": req.Description,
		"createdAt": now, "updatedAt": now,
	})
}

func (s *Server) getMemoryStore(c *gin.Context) {
	out, err := s.loadMemoryStore(c.Request.Context(), c.Param("id"), currentUserID(c))
	if err != nil {
		writeErr(c, http.StatusNotFound, "memory store not found")
		return
	}
	c.JSON(http.StatusOK, out)
}

func (s *Server) loadMemoryStore(ctx context.Context, id, owner string) (gin.H, error) {
	var oid, name string
	var desc *string
	var created, updated int64
	err := s.db.Pool.QueryRow(ctx,
		`SELECT owner_id, name, description, created_at, updated_at FROM memory_stores
		 WHERE store_id=$1 AND archived_at IS NULL`, id).Scan(&oid, &name, &desc, &created, &updated)
	if err != nil {
		return nil, err
	}
	if owner != "" && oid != owner {
		return nil, fmt.Errorf("not found")
	}
	d := ""
	if desc != nil {
		d = *desc
	}
	return gin.H{"id": id, "ownerId": oid, "name": name, "description": d, "createdAt": created, "updatedAt": updated}, nil
}

func (s *Server) archiveMemoryStore(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	now := nowMillis()
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE memory_stores SET archived_at=$1, updated_at=$1
		 WHERE store_id=$2 AND owner_id=$3 AND archived_at IS NULL`,
		now, id, owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "memory store not found")
		return
	}
	c.JSON(http.StatusOK, gin.H{"id": id, "archivedAt": now})
}

// redactMemory irreversibly replaces memory content and clears version history.
func (s *Server) redactMemory(c *gin.Context) {
	storeID := c.Param("id")
	if !s.ownStore(c, storeID) {
		writeErr(c, http.StatusNotFound, "not found")
		return
	}
	var req struct {
		Path        string `json:"path"`
		Replacement string `json:"replacement"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Path == "" {
		writeErr(c, http.StatusBadRequest, "path required")
		return
	}
	replacement := req.Replacement
	if replacement == "" {
		replacement = "[REDACTED]"
	}
	var mid string
	var hv int
	err := s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT memory_id, head_version FROM memories WHERE store_id=$1 AND path=$2`,
		storeID, req.Path).Scan(&mid, &hv)
	if err != nil {
		writeErr(c, http.StatusNotFound, "memory not found")
		return
	}
	hv++
	now := nowMillis()
	if _, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE memories SET content=$1, head_version=$2, updated_at=$3 WHERE memory_id=$4`,
		replacement, hv, now, mid); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	_, _ = s.db.Pool.Exec(c.Request.Context(), `DELETE FROM memory_versions WHERE memory_id=$1`, mid)
	_, _ = s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO memory_versions (memory_id, version, content, created_at) VALUES ($1,$2,$3,$4)`,
		mid, hv, replacement, now)
	out, _ := s.loadMemory(c.Request.Context(), storeID, req.Path)
	c.JSON(http.StatusOK, out)
}

func (s *Server) deleteMemoryStore(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`DELETE FROM memory_stores WHERE store_id=$1 AND owner_id=$2`, id, owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "memory store not found")
		return
	}
	_, _ = s.db.Pool.Exec(c.Request.Context(), `DELETE FROM memories WHERE store_id=$1`, id)
	c.Status(http.StatusNoContent)
}

func (s *Server) ownStore(c *gin.Context, storeID string) bool {
	var owner string
	err := s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT owner_id FROM memory_stores WHERE store_id=$1`, storeID).Scan(&owner)
	return err == nil && owner == currentUserID(c)
}

func (s *Server) listMemories(c *gin.Context) {
	storeID := c.Param("id")
	if !s.ownStore(c, storeID) {
		writeErr(c, http.StatusNotFound, "memory store not found")
		return
	}
	rows, err := s.db.Pool.Query(c.Request.Context(),
		`SELECT memory_id, store_id, path, content, head_version, created_at, updated_at
		 FROM memories WHERE store_id=$1 ORDER BY path`, storeID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []gin.H{}
	for rows.Next() {
		var mid, sid, path, content string
		var hv int
		var created, updated int64
		if err := rows.Scan(&mid, &sid, &path, &content, &hv, &created, &updated); err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		list = append(list, gin.H{
			"id": mid, "storeId": sid, "path": path, "content": content,
			"headVersion": hv, "createdAt": created, "updatedAt": updated,
		})
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) getMemory(c *gin.Context) {
	storeID := c.Param("id")
	path := memoryPath(c)
	if !s.ownStore(c, storeID) {
		writeErr(c, http.StatusNotFound, "not found")
		return
	}
	out, err := s.loadMemory(c.Request.Context(), storeID, path)
	if err != nil {
		writeErr(c, http.StatusNotFound, "memory not found")
		return
	}
	c.JSON(http.StatusOK, out)
}

func (s *Server) loadMemory(ctx context.Context, storeID, path string) (gin.H, error) {
	var mid, content string
	var hv int
	var created, updated int64
	err := s.db.Pool.QueryRow(ctx,
		`SELECT memory_id, content, head_version, created_at, updated_at FROM memories
		 WHERE store_id=$1 AND path=$2`, storeID, path).Scan(&mid, &content, &hv, &created, &updated)
	if err != nil {
		return nil, err
	}
	return gin.H{
		"id": mid, "storeId": storeID, "path": path, "content": content,
		"headVersion": hv, "createdAt": created, "updatedAt": updated,
	}, nil
}

type putMemoryReq struct {
	Content string `json:"content"`
}

func (s *Server) putMemory(c *gin.Context) {
	storeID := c.Param("id")
	path := memoryPath(c)
	if !s.ownStore(c, storeID) {
		writeErr(c, http.StatusNotFound, "not found")
		return
	}
	var req putMemoryReq
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "content required")
		return
	}
	now := nowMillis()
	var mid string
	var hv int
	err := s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT memory_id, head_version FROM memories WHERE store_id=$1 AND path=$2`, storeID, path).Scan(&mid, &hv)
	if err != nil {
		mid = shortID("mem_")
		hv = 1
		_, err = s.db.Pool.Exec(c.Request.Context(),
			`INSERT INTO memories (memory_id, store_id, path, content, head_version, created_at, updated_at)
			 VALUES ($1,$2,$3,$4,1,$5,$5)`, mid, storeID, path, req.Content, now)
		if err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		_, _ = s.db.Pool.Exec(c.Request.Context(),
			`INSERT INTO memory_versions (memory_id, version, content, created_at) VALUES ($1,1,$2,$3)`,
			mid, req.Content, now)
	} else {
		hv++
		_, err = s.db.Pool.Exec(c.Request.Context(),
			`UPDATE memories SET content=$1, head_version=$2, updated_at=$3 WHERE memory_id=$4`,
			req.Content, hv, now, mid)
		if err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		_, _ = s.db.Pool.Exec(c.Request.Context(),
			`INSERT INTO memory_versions (memory_id, version, content, created_at) VALUES ($1,$2,$3,$4)`,
			mid, hv, req.Content, now)
	}
	out, _ := s.loadMemory(c.Request.Context(), storeID, path)
	c.JSON(http.StatusOK, out)
}

func (s *Server) deleteMemory(c *gin.Context) {
	storeID := c.Param("id")
	path := memoryPath(c)
	if !s.ownStore(c, storeID) {
		writeErr(c, http.StatusNotFound, "not found")
		return
	}
	var mid string
	err := s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT memory_id FROM memories WHERE store_id=$1 AND path=$2`, storeID, path).Scan(&mid)
	if err != nil {
		writeErr(c, http.StatusNotFound, "memory not found")
		return
	}
	_, _ = s.db.Pool.Exec(c.Request.Context(), `DELETE FROM memory_versions WHERE memory_id=$1`, mid)
	_, _ = s.db.Pool.Exec(c.Request.Context(), `DELETE FROM memories WHERE memory_id=$1`, mid)
	c.Status(http.StatusNoContent)
}

func (s *Server) listMemoryVersions(c *gin.Context) {
	storeID := c.Param("id")
	path := strings.TrimPrefix(memoryPath(c), memoryVersionsPrefix)
	if !s.ownStore(c, storeID) {
		writeErr(c, http.StatusNotFound, "not found")
		return
	}
	var mid string
	err := s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT memory_id FROM memories WHERE store_id=$1 AND path=$2`, storeID, path).Scan(&mid)
	if err != nil {
		writeErr(c, http.StatusNotFound, "memory not found")
		return
	}
	rows, err := s.db.Pool.Query(c.Request.Context(),
		`SELECT version, content, created_at FROM memory_versions WHERE memory_id=$1 ORDER BY version DESC`, mid)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []gin.H{}
	for rows.Next() {
		var ver int
		var content string
		var created int64
		if err := rows.Scan(&ver, &content, &created); err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		list = append(list, gin.H{"memoryId": mid, "version": ver, "content": content, "createdAt": created})
	}
	c.JSON(http.StatusOK, list)
}

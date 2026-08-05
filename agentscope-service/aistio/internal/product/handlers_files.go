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
	"strings"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
)

const maxFileBytes = 1 << 20 // 1 MiB

func (s *Server) registerFiles(r gin.IRouter) {
	r.GET("/api/files", s.listFiles)
	r.POST("/api/files", s.createFile)
	r.GET("/api/files/:id", s.getFile)
	r.GET("/api/files/:id/content", s.getFileContent)
	r.DELETE("/api/files/:id", s.deleteFile)
}

type createFileReq struct {
	Filename    string `json:"filename"`
	Content     string `json:"content"`
	ContentType string `json:"contentType"`
}

type fileRow struct {
	FileID      string
	OwnerID     string
	Filename    string
	ContentType string
	SizeBytes   int64
	Content     string
	CreatedAt   int64
}

func (f fileRow) metaJSON() gin.H {
	return gin.H{
		"id":          f.FileID,
		"filename":    f.Filename,
		"contentType": f.ContentType,
		"sizeBytes":   f.SizeBytes,
		"createdAt":   f.CreatedAt,
	}
}

func validFilename(name string) bool {
	if name == "" || strings.Contains(name, "/") || strings.Contains(name, `\`) ||
		strings.Contains(name, "..") {
		return false
	}
	return true
}

func (s *Server) listFiles(c *gin.Context) {
	owner := currentUserID(c)
	rows, err := s.db.Pool.Query(c.Request.Context(),
		`SELECT file_id, owner_id, filename, content_type, size_bytes, created_at
		 FROM files WHERE owner_id=$1 ORDER BY created_at DESC`, owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []gin.H{}
	for rows.Next() {
		var f fileRow
		if err := rows.Scan(&f.FileID, &f.OwnerID, &f.Filename, &f.ContentType, &f.SizeBytes, &f.CreatedAt); err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		list = append(list, f.metaJSON())
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) createFile(c *gin.Context) {
	var req createFileReq
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	if !validFilename(req.Filename) {
		writeErr(c, http.StatusBadRequest, "invalid filename")
		return
	}
	if !utf8.ValidString(req.Content) {
		writeErr(c, http.StatusBadRequest, "content must be valid UTF-8 text")
		return
	}
	if len(req.Content) > maxFileBytes {
		writeErr(c, http.StatusBadRequest, "file too large")
		return
	}
	ct := req.ContentType
	if ct == "" {
		ct = "text/plain"
	}
	id := shortID("file_")
	now := nowMillis()
	owner := currentUserID(c)
	size := int64(len(req.Content))
	if _, err := s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO files (file_id, owner_id, filename, content_type, size_bytes, content, created_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7)`,
		id, owner, req.Filename, ct, size, req.Content, now); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"id": id, "filename": req.Filename, "contentType": ct,
		"sizeBytes": size, "createdAt": now,
	})
}

func (s *Server) loadFile(ctx context.Context, owner, id string) (fileRow, error) {
	var f fileRow
	err := s.db.Pool.QueryRow(ctx,
		`SELECT file_id, owner_id, filename, content_type, size_bytes, content, created_at
		 FROM files WHERE file_id=$1 AND owner_id=$2`, id, owner).
		Scan(&f.FileID, &f.OwnerID, &f.Filename, &f.ContentType, &f.SizeBytes, &f.Content, &f.CreatedAt)
	return f, err
}

func (s *Server) getFile(c *gin.Context) {
	f, err := s.loadFile(c.Request.Context(), currentUserID(c), c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "file not found")
		return
	}
	c.JSON(http.StatusOK, f.metaJSON())
}

func (s *Server) getFileContent(c *gin.Context) {
	f, err := s.loadFile(c.Request.Context(), currentUserID(c), c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "file not found")
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"id": f.FileID, "filename": f.Filename, "content": f.Content,
	})
}

func (s *Server) deleteFile(c *gin.Context) {
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`DELETE FROM files WHERE file_id=$1 AND owner_id=$2`, c.Param("id"), currentUserID(c))
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "file not found")
		return
	}
	c.Status(http.StatusNoContent)
}

// expandFileResources resolves fileId references into inline content so the
// data plane can mount them without a separate fetch.
func (s *Server) expandFileResources(ctx context.Context, ownerID string, resources any) any {
	arr, ok := resources.([]any)
	if !ok || len(arr) == 0 {
		return resources
	}
	out := make([]any, 0, len(arr))
	for _, item := range arr {
		m, ok := item.(map[string]any)
		if !ok {
			out = append(out, item)
			continue
		}
		typ, _ := m["type"].(string)
		fileID, _ := m["fileId"].(string)
		if _, hasContent := m["content"]; typ != "file" || fileID == "" || hasContent {
			out = append(out, item)
			continue
		}
		f, err := s.loadFile(ctx, ownerID, fileID)
		if err != nil {
			out = append(out, item)
			continue
		}
		expanded := make(map[string]any, len(m)+2)
		for k, v := range m {
			expanded[k] = v
		}
		expanded["content"] = f.Content
		if _, hasName := expanded["filename"]; !hasName {
			expanded["filename"] = f.Filename
		}
		out = append(out, expanded)
	}
	return out
}

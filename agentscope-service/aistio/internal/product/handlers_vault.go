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
	"time"

	"github.com/gin-gonic/gin"
)

func (s *Server) registerVaults(r gin.IRouter) {
	r.GET("/api/vaults", s.listVaults)
	r.POST("/api/vaults", s.createVault)
	r.GET("/api/vaults/:id", s.getVault)
	r.PATCH("/api/vaults/:id", s.updateVault)
	r.DELETE("/api/vaults/:id", s.deleteVault)
	r.POST("/api/vaults/:id/archive", s.archiveVault)
	r.GET("/api/vaults/:id/credentials", s.listCredentials)
	r.POST("/api/vaults/:id/credentials", s.addCredential)
	r.PATCH("/api/vaults/:id/credentials/:cid", s.updateCredential)
	r.DELETE("/api/vaults/:id/credentials/:cid", s.deleteCredential)
	r.POST("/api/vaults/:id/credentials/:cid/validate", s.validateCredential)
}

type vaultCreateReq struct {
	DisplayName string `json:"displayName"`
	Metadata    any    `json:"metadata"`
}

type vaultUpdateReq struct {
	DisplayName *string `json:"displayName"`
	Metadata    any     `json:"metadata"`
}

type addCredReq struct {
	Type   string `json:"type"`
	Label  string `json:"label"`
	Target string `json:"target"`
	Secret string `json:"secret"`
}

type updateCredReq struct {
	Label  *string `json:"label"`
	Target *string `json:"target"`
	Secret *string `json:"secret"`
}

func (s *Server) listVaults(c *gin.Context) {
	owner := currentUserID(c)
	limit, offset, ok := pageParams(c)
	if !ok {
		writeErr(c, http.StatusBadRequest, "invalid limit/offset")
		return
	}
	var total int64
	if err := s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT COUNT(*) FROM vaults WHERE owner_id=$1 AND archived_at IS NULL`, owner).Scan(&total); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	writeTotalCount(c, total)
	q := `SELECT vault_id, owner_id, display_name, metadata_json, created_at, updated_at
		 FROM vaults WHERE owner_id=$1 AND archived_at IS NULL ORDER BY updated_at DESC`
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
		var meta *string
		var created, updated int64
		if err := rows.Scan(&id, &oid, &name, &meta, &created, &updated); err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		list = append(list, gin.H{
			"id": id, "ownerId": oid, "displayName": name,
			"metadata": parseJSONRaw(deref(meta)), "createdAt": created, "updatedAt": updated,
		})
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) createVault(c *gin.Context) {
	var req vaultCreateReq
	if err := c.ShouldBindJSON(&req); err != nil || req.DisplayName == "" {
		writeTextErr(c, http.StatusBadRequest, "displayName required")
		return
	}
	id := shortID("vault_")
	now := nowMillis()
	owner := currentUserID(c)
	_, err := s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO vaults (vault_id, owner_id, display_name, metadata_json, created_at, updated_at)
		 VALUES ($1,$2,$3,$4,$5,$5)`, id, owner, req.DisplayName, mustJSON(req.Metadata), now)
	if err != nil {
		writeTextErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"id": id, "ownerId": owner, "displayName": req.DisplayName,
		"metadata": req.Metadata, "createdAt": now, "updatedAt": now,
	})
}

func (s *Server) getVault(c *gin.Context) {
	out, err := s.loadVault(c.Request.Context(), c.Param("id"), currentUserID(c))
	if err != nil {
		writeErr(c, http.StatusNotFound, "vault not found")
		return
	}
	c.JSON(http.StatusOK, out)
}

func (s *Server) loadVault(ctx context.Context, id, owner string) (gin.H, error) {
	var oid, name string
	var meta *string
	var created, updated int64
	err := s.db.Pool.QueryRow(ctx,
		`SELECT owner_id, display_name, metadata_json, created_at, updated_at FROM vaults
		 WHERE vault_id=$1 AND archived_at IS NULL`, id).Scan(&oid, &name, &meta, &created, &updated)
	if err != nil {
		return nil, err
	}
	if owner != "" && oid != owner {
		return nil, err
	}
	return gin.H{
		"id": id, "ownerId": oid, "displayName": name,
		"metadata": parseJSONRaw(deref(meta)), "createdAt": created, "updatedAt": updated,
	}, nil
}

func (s *Server) updateVault(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	out, err := s.loadVault(c.Request.Context(), id, owner)
	if err != nil {
		writeErr(c, http.StatusNotFound, "vault not found")
		return
	}
	var req vaultUpdateReq
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	displayName, _ := out["displayName"].(string)
	if req.DisplayName != nil {
		if *req.DisplayName == "" {
			writeErr(c, http.StatusBadRequest, "displayName cannot be empty")
			return
		}
		displayName = *req.DisplayName
	}
	metaJSON := mustJSON(out["metadata"])
	if req.Metadata != nil {
		metaJSON = mustJSON(req.Metadata)
	}
	now := nowMillis()
	if _, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE vaults SET display_name=$1, metadata_json=$2, updated_at=$3
		 WHERE vault_id=$4 AND owner_id=$5 AND archived_at IS NULL`,
		displayName, metaJSON, now, id, owner); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	updated, err := s.loadVault(c.Request.Context(), id, owner)
	if err != nil {
		writeErr(c, http.StatusNotFound, "vault not found")
		return
	}
	c.JSON(http.StatusOK, updated)
}

func (s *Server) archiveVault(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	now := nowMillis()
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE vaults SET archived_at=$1, updated_at=$1
		 WHERE vault_id=$2 AND owner_id=$3 AND archived_at IS NULL`,
		now, id, owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "vault not found")
		return
	}
	c.JSON(http.StatusOK, gin.H{"id": id, "archivedAt": now})
}

func (s *Server) deleteVault(c *gin.Context) {
	owner := currentUserID(c)
	id := c.Param("id")
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`DELETE FROM vaults WHERE vault_id=$1 AND owner_id=$2`, id, owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "vault not found")
		return
	}
	_, _ = s.db.Pool.Exec(c.Request.Context(), `DELETE FROM vault_credentials WHERE vault_id=$1`, id)
	c.Status(http.StatusNoContent)
}

func (s *Server) ownVault(c *gin.Context, vaultID string) bool {
	var owner string
	err := s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT owner_id FROM vaults WHERE vault_id=$1 AND archived_at IS NULL`, vaultID).Scan(&owner)
	return err == nil && owner == currentUserID(c)
}

func (s *Server) listCredentials(c *gin.Context) {
	vaultID := c.Param("id")
	if !s.ownVault(c, vaultID) {
		writeErr(c, http.StatusNotFound, "vault not found")
		return
	}
	rows, err := s.db.Pool.Query(c.Request.Context(),
		`SELECT credential_id, type, label, target, created_at FROM vault_credentials
		 WHERE vault_id=$1 ORDER BY created_at`, vaultID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []gin.H{}
	for rows.Next() {
		var id, typ, label, target string
		var created int64
		if err := rows.Scan(&id, &typ, &label, &target, &created); err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		list = append(list, gin.H{
			"id": id, "type": typ, "label": label, "target": target, "createdAt": created,
		})
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) addCredential(c *gin.Context) {
	vaultID := c.Param("id")
	if !s.ownVault(c, vaultID) {
		writeErr(c, http.StatusNotFound, "vault not found")
		return
	}
	var req addCredReq
	if err := c.ShouldBindJSON(&req); err != nil || req.Secret == "" {
		writeTextErr(c, http.StatusBadRequest, "type, label, target, secret required")
		return
	}
	ct, err := encryptAESGCM(s.vaultKey, req.Secret)
	if err != nil {
		writeTextErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	id := shortID("cred_")
	now := nowMillis()
	_, err = s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO vault_credentials (credential_id, vault_id, type, label, target, ciphertext, created_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7)`, id, vaultID, req.Type, req.Label, req.Target, ct, now)
	if err != nil {
		writeTextErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"id": id, "type": req.Type, "label": req.Label, "target": req.Target, "createdAt": now,
	})
}

func (s *Server) updateCredential(c *gin.Context) {
	vaultID := c.Param("id")
	if !s.ownVault(c, vaultID) {
		writeErr(c, http.StatusNotFound, "vault not found")
		return
	}
	cid := c.Param("cid")
	var typ, label, target string
	var created int64
	err := s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT type, label, target, created_at FROM vault_credentials
		 WHERE vault_id=$1 AND credential_id=$2`, vaultID, cid).Scan(&typ, &label, &target, &created)
	if err != nil {
		writeErr(c, http.StatusNotFound, "credential not found")
		return
	}
	var req updateCredReq
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	if req.Label != nil {
		label = *req.Label
	}
	if req.Target != nil {
		target = *req.Target
	}
	if req.Secret != nil && *req.Secret != "" {
		ct, err := encryptAESGCM(s.vaultKey, *req.Secret)
		if err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		_, err = s.db.Pool.Exec(c.Request.Context(),
			`UPDATE vault_credentials SET label=$1, target=$2, ciphertext=$3
			 WHERE vault_id=$4 AND credential_id=$5`, label, target, ct, vaultID, cid)
		if err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
	} else {
		_, err = s.db.Pool.Exec(c.Request.Context(),
			`UPDATE vault_credentials SET label=$1, target=$2
			 WHERE vault_id=$3 AND credential_id=$4`, label, target, vaultID, cid)
		if err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
	}
	c.JSON(http.StatusOK, gin.H{
		"id": cid, "type": typ, "label": label, "target": target, "createdAt": created,
	})
}

// validateCredential performs a local decrypt check and, when the credential
// targets an http(s) endpoint, a bounded reachability probe. It never returns
// the secret.
func (s *Server) validateCredential(c *gin.Context) {
	vaultID := c.Param("id")
	if !s.ownVault(c, vaultID) {
		writeErr(c, http.StatusNotFound, "vault not found")
		return
	}
	cid := c.Param("cid")
	var target string
	var ct []byte
	err := s.db.Pool.QueryRow(c.Request.Context(),
		`SELECT target, ciphertext FROM vault_credentials
		 WHERE vault_id=$1 AND credential_id=$2`, vaultID, cid).Scan(&target, &ct)
	if err != nil {
		writeErr(c, http.StatusNotFound, "credential not found")
		return
	}
	checks := gin.H{}
	ok := true
	secret, err := decryptAESGCM(s.vaultKey, ct)
	if err != nil || strings.TrimSpace(secret) == "" {
		checks["decrypt"] = "failed"
		ok = false
	} else {
		checks["decrypt"] = "ok"
	}
	if ok && (strings.HasPrefix(target, "http://") || strings.HasPrefix(target, "https://")) {
		client := &http.Client{Timeout: 5 * time.Second}
		req, err := http.NewRequestWithContext(c.Request.Context(), http.MethodGet, target, nil)
		if err != nil {
			checks["reachability"] = "invalid_url"
		} else {
			resp, err := client.Do(req)
			if err != nil {
				checks["reachability"] = "unreachable"
			} else {
				_ = resp.Body.Close()
				checks["reachability"] = fmt.Sprintf("http_%d", resp.StatusCode)
			}
		}
	}
	c.JSON(http.StatusOK, gin.H{
		"ok": ok, "checks": checks, "checkedAt": nowMillis(),
	})
}

func (s *Server) deleteCredential(c *gin.Context) {
	vaultID := c.Param("id")
	if !s.ownVault(c, vaultID) {
		writeErr(c, http.StatusNotFound, "vault not found")
		return
	}
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`DELETE FROM vault_credentials WHERE vault_id=$1 AND credential_id=$2`, vaultID, c.Param("cid"))
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "credential not found")
		return
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) resolveVaultCredentials(ctx context.Context, vaultIDs []string, ownerID string) ([]gin.H, error) {
	out := []gin.H{}
	for _, vid := range vaultIDs {
		var oid string
		err := s.db.Pool.QueryRow(ctx,
			`SELECT owner_id FROM vaults WHERE vault_id=$1 AND archived_at IS NULL`, vid).Scan(&oid)
		if err != nil {
			continue
		}
		if ownerID != "" && oid != ownerID {
			continue
		}
		rows, err := s.db.Pool.Query(ctx,
			`SELECT credential_id, type, label, target, ciphertext FROM vault_credentials WHERE vault_id=$1`, vid)
		if err != nil {
			continue
		}
		for rows.Next() {
			var id, typ, label, target string
			var ct []byte
			if err := rows.Scan(&id, &typ, &label, &target, &ct); err != nil {
				continue
			}
			secret, err := decryptAESGCM(s.vaultKey, ct)
			if err != nil {
				continue
			}
			out = append(out, gin.H{
				"id": id, "vaultId": vid, "type": typ, "label": label, "target": target, "secret": secret,
			})
		}
		rows.Close()
	}
	return out, nil
}

package product

import (
	"context"
	"net/http"

	"github.com/gin-gonic/gin"
)

func (s *Server) registerVaults(r gin.IRouter) {
	r.GET("/api/vaults", s.listVaults)
	r.POST("/api/vaults", s.createVault)
	r.GET("/api/vaults/:id", s.getVault)
	r.DELETE("/api/vaults/:id", s.deleteVault)
	r.GET("/api/vaults/:id/credentials", s.listCredentials)
	r.POST("/api/vaults/:id/credentials", s.addCredential)
	r.DELETE("/api/vaults/:id/credentials/:cid", s.deleteCredential)
}

type vaultCreateReq struct {
	DisplayName string `json:"displayName"`
	Metadata    any    `json:"metadata"`
}

type addCredReq struct {
	Type   string `json:"type"`
	Label  string `json:"label"`
	Target string `json:"target"`
	Secret string `json:"secret"`
}

func (s *Server) listVaults(c *gin.Context) {
	owner := currentUserID(c)
	rows, err := s.db.Pool.Query(c.Request.Context(),
		`SELECT vault_id, owner_id, display_name, metadata_json, created_at, updated_at
		 FROM vaults WHERE owner_id=$1 AND archived_at IS NULL ORDER BY updated_at DESC`, owner)
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
		`SELECT owner_id FROM vaults WHERE vault_id=$1`, vaultID).Scan(&owner)
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
		err := s.db.Pool.QueryRow(ctx, `SELECT owner_id FROM vaults WHERE vault_id=$1`, vid).Scan(&oid)
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

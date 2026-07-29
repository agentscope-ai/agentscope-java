package product

import (
	"bytes"
	"context"
	"encoding/json"
	"log"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

func (s *Server) registerDeployments(r gin.IRouter) {
	r.GET("/api/deployments", s.listDeployments)
	r.POST("/api/deployments", s.createDeployment)
	r.GET("/api/deployments/:id", s.getDeployment)
	r.PATCH("/api/deployments/:id", s.updateDeployment)
	r.DELETE("/api/deployments/:id", s.deleteDeployment)
	r.POST("/api/deployments/:id/archive", s.archiveDeployment)
	r.POST("/api/deployments/:id/run", s.runDeployment)
}

type createDeployReq struct {
	Name          string `json:"name"`
	AgentID       string `json:"agentId"`
	AgentVersion  *int   `json:"agentVersion"`
	EnvironmentID string `json:"environmentId"`
	TriggerType   string `json:"triggerType"`
	CronExpression string `json:"cronExpression"`
}

type updateDeployReq struct {
	Name           *string `json:"name"`
	Enabled        *bool   `json:"enabled"`
	CronExpression *string `json:"cronExpression"`
	EnvironmentID  *string `json:"environmentId"`
	AgentVersion   *int    `json:"agentVersion"`
}

type deployRow struct {
	DeploymentID      string
	OwnerID           string
	Name              string
	AgentID           string
	AgentVersion      *int
	EnvironmentID     string
	TriggerType       string
	CronExpression    *string
	WebhookToken      *string
	Enabled           bool
	LastRunAt         *int64
	LastSessionID     *string
	LastStatus        *string
	LastHandsStatsJSON *string
	ArchivedAt        *int64
	CreatedAt         int64
	UpdatedAt         int64
}

func (d deployRow) toJSON() gin.H {
	var hands any
	if d.LastHandsStatsJSON != nil {
		hands = parseJSONRaw(*d.LastHandsStatsJSON)
	}
	return gin.H{
		"id":              d.DeploymentID,
		"ownerId":         d.OwnerID,
		"name":            d.Name,
		"agentId":         d.AgentID,
		"agentVersion":    d.AgentVersion,
		"environmentId":   d.EnvironmentID,
		"triggerType":     d.TriggerType,
		"cronExpression":  nullStrPtr(d.CronExpression),
		"webhookToken":    nullStrPtr(d.WebhookToken),
		"enabled":         d.Enabled,
		"lastRunAt":       nullMillis(d.LastRunAt),
		"lastSessionId":   nullStrPtr(d.LastSessionID),
		"lastStatus":      nullStrPtr(d.LastStatus),
		"lastHandsStats":  hands,
		"createdAt":       d.CreatedAt,
		"updatedAt":       d.UpdatedAt,
		"archivedAt":      nullMillis(d.ArchivedAt),
	}
}

const deploySelect = `SELECT deployment_id, owner_id, name, agent_id, agent_version, environment_id,
	trigger_type, cron_expression, webhook_token, enabled, last_run_at, last_session_id,
	last_status, last_hands_stats_json, archived_at, created_at, updated_at FROM deployments`

func (s *Server) scanDeploy(sc interface{ Scan(dest ...any) error }) (deployRow, error) {
	var d deployRow
	err := sc.Scan(
		&d.DeploymentID, &d.OwnerID, &d.Name, &d.AgentID, &d.AgentVersion, &d.EnvironmentID,
		&d.TriggerType, &d.CronExpression, &d.WebhookToken, &d.Enabled, &d.LastRunAt, &d.LastSessionID,
		&d.LastStatus, &d.LastHandsStatsJSON, &d.ArchivedAt, &d.CreatedAt, &d.UpdatedAt,
	)
	return d, err
}

func (s *Server) loadDeploy(ctx context.Context, id string) (deployRow, error) {
	return s.scanDeploy(s.db.Pool.QueryRow(ctx, deploySelect+` WHERE deployment_id=$1`, id))
}

func (s *Server) listDeployments(c *gin.Context) {
	owner := currentUserID(c)
	rows, err := s.db.Pool.Query(c.Request.Context(),
		deploySelect+` WHERE owner_id=$1 AND archived_at IS NULL ORDER BY updated_at DESC`, owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []gin.H{}
	for rows.Next() {
		d, err := s.scanDeploy(rows)
		if err != nil {
			writeErr(c, http.StatusInternalServerError, err.Error())
			return
		}
		list = append(list, d.toJSON())
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) createDeployment(c *gin.Context) {
	var req createDeployReq
	if err := c.ShouldBindJSON(&req); err != nil || req.Name == "" || req.AgentID == "" || req.TriggerType == "" {
		writeTextErr(c, http.StatusBadRequest, "name, agentId, triggerType required")
		return
	}
	owner := currentUserID(c)
	envID := req.EnvironmentID
	if envID == "" {
		var e envRow
		err := s.db.Pool.QueryRow(c.Request.Context(),
			envSelect+` WHERE owner_id=$1 AND archived_at IS NULL ORDER BY created_at LIMIT 1`, owner).Scan(
			&e.EnvironmentID, &e.OwnerID, &e.Name, &e.Type, &e.ConfigJSON, &e.ArchivedAt, &e.CreatedAt, &e.UpdatedAt)
		if err != nil {
			writeTextErr(c, http.StatusBadRequest, "environmentId required (no default found)")
			return
		}
		envID = e.EnvironmentID
	}
	id := shortID("dep_")
	now := nowMillis()
	var webhook any
	if req.TriggerType == "webhook" {
		webhook = uuid.New().String()
	}
	_, err := s.db.Pool.Exec(c.Request.Context(),
		`INSERT INTO deployments (deployment_id, owner_id, name, agent_id, agent_version, environment_id,
		 trigger_type, cron_expression, webhook_token, enabled, created_at, updated_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,TRUE,$10,$10)`,
		id, owner, req.Name, req.AgentID, req.AgentVersion, envID, req.TriggerType,
		nullStr(req.CronExpression), webhook, now)
	if err != nil {
		writeTextErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	d, _ := s.loadDeploy(c.Request.Context(), id)
	c.JSON(http.StatusOK, d.toJSON())
}

func (s *Server) getDeployment(c *gin.Context) {
	d, err := s.loadDeploy(c.Request.Context(), c.Param("id"))
	if err != nil || d.OwnerID != currentUserID(c) {
		writeErr(c, http.StatusNotFound, "deployment not found")
		return
	}
	c.JSON(http.StatusOK, d.toJSON())
}

func (s *Server) updateDeployment(c *gin.Context) {
	d, err := s.loadDeploy(c.Request.Context(), c.Param("id"))
	if err != nil || d.OwnerID != currentUserID(c) {
		writeErr(c, http.StatusNotFound, "deployment not found")
		return
	}
	var req updateDeployReq
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	name := d.Name
	if req.Name != nil {
		name = *req.Name
	}
	enabled := d.Enabled
	if req.Enabled != nil {
		enabled = *req.Enabled
	}
	cron := d.CronExpression
	if req.CronExpression != nil {
		cron = req.CronExpression
	}
	envID := d.EnvironmentID
	if req.EnvironmentID != nil {
		envID = *req.EnvironmentID
	}
	agentVer := d.AgentVersion
	if req.AgentVersion != nil {
		agentVer = req.AgentVersion
	}
	now := nowMillis()
	_, err = s.db.Pool.Exec(c.Request.Context(),
		`UPDATE deployments SET name=$1, enabled=$2, cron_expression=$3, environment_id=$4,
		 agent_version=$5, updated_at=$6 WHERE deployment_id=$7`,
		name, enabled, cron, envID, agentVer, now, d.DeploymentID)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	out, _ := s.loadDeploy(c.Request.Context(), d.DeploymentID)
	c.JSON(http.StatusOK, out.toJSON())
}

func (s *Server) archiveDeployment(c *gin.Context) {
	owner := currentUserID(c)
	now := nowMillis()
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`UPDATE deployments SET archived_at=$1, updated_at=$1, enabled=FALSE
		 WHERE deployment_id=$2 AND owner_id=$3 AND archived_at IS NULL`,
		now, c.Param("id"), owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "deployment not found")
		return
	}
	d, _ := s.loadDeploy(c.Request.Context(), c.Param("id"))
	c.JSON(http.StatusOK, d.toJSON())
}

func (s *Server) deleteDeployment(c *gin.Context) {
	owner := currentUserID(c)
	tag, err := s.db.Pool.Exec(c.Request.Context(),
		`DELETE FROM deployments WHERE deployment_id=$1 AND owner_id=$2`, c.Param("id"), owner)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, http.StatusNotFound, "deployment not found")
		return
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) runDeployment(c *gin.Context) {
	d, err := s.loadDeploy(c.Request.Context(), c.Param("id"))
	if err != nil || d.OwnerID != currentUserID(c) {
		writeErr(c, http.StatusNotFound, "deployment not found")
		return
	}
	var body struct {
		Text string `json:"text"`
	}
	_ = c.ShouldBindJSON(&body)
	out, err := s.fireDeployment(c.Request.Context(), d, body.Text)
	if err != nil {
		writeTextErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	c.JSON(http.StatusOK, out.toJSON())
}

func (s *Server) fireDeployment(ctx context.Context, d deployRow, message string) (deployRow, error) {
	refType := "latest"
	ver := 0
	a, err := s.loadAgent(ctx, d.OwnerID, d.AgentID)
	if err != nil {
		return d, err
	}
	ver = a.HeadVersion
	if d.AgentVersion != nil {
		ver = *d.AgentVersion
		refType = "version"
	}
	sess, err := s.insertSession(ctx, d.OwnerID, d.AgentID, d.OwnerID, ver, refType,
		d.EnvironmentID, "", nil, nil, nil, nil)
	if err != nil {
		return d, err
	}
	now := nowMillis()
	status := "ok"
	if s.cfg.DataURL != "" {
		payload := map[string]any{
			"events": []map[string]any{
				{"type": "user.message", "payload": map[string]any{"text": message}},
			},
		}
		b, _ := json.Marshal(payload)
		url := s.cfg.DataURL + "/api/sessions/" + sess.SessionID + "/events"
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(b))
		if err == nil {
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("X-Builder-Internal-Token", s.cfg.InternalToken)
			client := &http.Client{Timeout: 15 * time.Second}
			resp, err := client.Do(req)
			if err != nil {
				log.Printf("fire deployment event post failed: %v", err)
				status = "error"
			} else {
				_ = resp.Body.Close()
				if resp.StatusCode >= 300 {
					log.Printf("fire deployment event status=%d", resp.StatusCode)
					status = "error"
				}
			}
		}
	}
	_, _ = s.db.Pool.Exec(ctx,
		`UPDATE deployments SET last_run_at=$1, last_session_id=$2, last_status=$3, updated_at=$1
		 WHERE deployment_id=$4`, now, sess.SessionID, status, d.DeploymentID)
	return s.loadDeploy(ctx, d.DeploymentID)
}

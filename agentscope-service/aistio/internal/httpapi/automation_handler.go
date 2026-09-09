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

package httpapi

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/spring-ai-alibaba/aistio/internal/automation"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"io"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

type automationRequest struct {
	Tenant          string                             `json:"tenant"`
	Namespace       string                             `json:"namespace"`
	Name            *string                            `json:"name"`
	Description     *string                            `json:"description"`
	Enabled         *bool                              `json:"enabled"`
	TriggerType     controlmodel.AutomationTriggerType `json:"triggerType"`
	TriggerConfig   json.RawMessage                    `json:"triggerConfig"`
	ActionType      controlmodel.AutomationActionType  `json:"actionType"`
	ActionConfig    json.RawMessage                    `json:"actionConfig"`
	Execution       *controlmodel.AutomationExecution  `json:"execution"`
	Triggers        *[]controlmodel.AutomationTrigger  `json:"triggers"`
	WebhookSecret   *string                            `json:"webhookSecret"`
	ExpectedVersion int64                              `json:"expectedVersion"`
}

func (s *Server) writeAutomationError(c *gin.Context, err error) {
	var invalid automation.ValidationError
	switch {
	case errors.Is(err, store.ErrConflict):
		c.JSON(409, ErrorResponse{Error: "The resource changed, or the idempotency key was reused with different input. Refresh and retry."})
	case errors.Is(err, store.ErrNotFound):
		c.JSON(404, ErrorResponse{Error: "Automation resource not found"})
	case errors.As(err, &invalid) || strings.Contains(err.Error(), "required") || strings.Contains(err.Error(), "disabled") || strings.Contains(err.Error(), "cannot") || strings.Contains(err.Error(), "dispatch is in progress") || strings.Contains(err.Error(), "archived"):
		c.JSON(400, ErrorResponse{Error: err.Error()})
	default:
		s.writeControlPlaneError(c, err)
	}
}
func applyAutomationRequest(item *controlmodel.Automation, req automationRequest) {
	if req.Name != nil {
		item.Name = strings.TrimSpace(*req.Name)
	}
	if req.Description != nil {
		item.Description = *req.Description
	}
	if req.Enabled != nil {
		item.Enabled = *req.Enabled
	}
	if req.ActionType != "" {
		item.ActionType = req.ActionType
	}
	if len(req.ActionConfig) > 0 {
		item.ActionConfig = req.ActionConfig
	}
	if req.Execution != nil {
		item.Execution = req.Execution
	}
	if req.Triggers != nil {
		item.Triggers = *req.Triggers
		if item.Triggers == nil {
			item.Triggers = []controlmodel.AutomationTrigger{}
		}
	} else if req.TriggerType != "" || len(req.TriggerConfig) > 0 {
		if req.TriggerType != "" {
			item.TriggerType = req.TriggerType
		}
		if len(req.TriggerConfig) > 0 {
			item.TriggerConfig = req.TriggerConfig
		}
		item.Triggers = nil
	}
	if req.WebhookSecret != nil && *req.WebhookSecret != "" {
		sum := sha256.Sum256([]byte(*req.WebhookSecret))
		item.WebhookSecretHash = hex.EncodeToString(sum[:])
	}
}
func automationSecret(item *controlmodel.Automation) (string, error) {
	hasWebhook := item.TriggerType == controlmodel.AutomationTriggerWebhook
	for _, t := range item.Triggers {
		hasWebhook = hasWebhook || t.Type == controlmodel.AutomationTriggerWebhook
	}
	if !hasWebhook || item.WebhookSecretHash != "" {
		return "", nil
	}
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	secret := hex.EncodeToString(raw)
	sum := sha256.Sum256([]byte(secret))
	item.WebhookSecretHash = hex.EncodeToString(sum[:])
	item.WebhookConfigured = true
	return secret, nil
}
func (s *Server) createAutomation(c *gin.Context) {
	var req automationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	if strings.TrimSpace(req.Tenant) == "" || strings.TrimSpace(req.Namespace) == "" {
		c.JSON(400, ErrorResponse{Error: "tenant and namespace are required"})
		return
	}
	rule := &controlmodel.Automation{Tenant: req.Tenant, Namespace: req.Namespace, Enabled: true, CreatedBy: humanActor(c, s)}
	applyAutomationRequest(rule, req)
	secret, err := automationSecret(rule)
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	created, err := s.automationService().Create(c.Request.Context(), rule)
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	c.JSON(201, gin.H{"automation": created, "webhookSecret": secret})
}
func (s *Server) listAutomations(c *gin.Context) {
	tenant, namespace, ok := requireCollaborationScope(c)
	if !ok {
		return
	}
	limit, offset := collaborationPagination(c)
	var enabled *bool
	if raw := c.Query("enabled"); raw != "" {
		v, e := strconv.ParseBool(raw)
		if e != nil {
			c.JSON(400, ErrorResponse{Error: "invalid enabled"})
			return
		}
		enabled = &v
	}
	items, err := s.store.Collaboration().ListAutomations(c.Request.Context(), store.AutomationFilter{Tenant: tenant, Namespace: namespace, Enabled: enabled, Limit: limit, Offset: offset})
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	for _, item := range items {
		automation.NormalizeLegacy(item)
	}
	c.JSON(200, gin.H{"items": items})
}
func (s *Server) getAutomation(c *gin.Context) {
	id, ok := parseUUIDParam(c, "automationId")
	if !ok {
		return
	}
	item, err := s.store.Collaboration().GetAutomation(c, id)
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	automation.NormalizeLegacy(item)
	c.JSON(200, gin.H{"automation": item})
}
func (s *Server) updateAutomation(c *gin.Context) {
	id, ok := parseUUIDParam(c, "automationId")
	if !ok {
		return
	}
	current, err := s.store.Collaboration().GetAutomation(c, id)
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	var req automationRequest
	if err = c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	applyAutomationRequest(current, req)
	secret, err := automationSecret(current)
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	updated, err := s.automationService().Update(c, current, req.ExpectedVersion)
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	c.JSON(200, gin.H{"automation": updated, "webhookSecret": secret})
}
func (s *Server) archiveAutomation(c *gin.Context) {
	id, ok := parseUUIDParam(c, "automationId")
	if !ok {
		return
	}
	version, _ := strconv.ParseInt(c.Query("expectedVersion"), 10, 64)
	if version <= 0 {
		c.JSON(400, ErrorResponse{Error: "positive expectedVersion is required"})
		return
	}
	item, err := s.store.Collaboration().ArchiveAutomation(c, id, version)
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	c.JSON(200, gin.H{"automation": item})
}
func automationKey(c *gin.Context) string {
	key := c.GetHeader("Idempotency-Key")
	if key == "" {
		key = c.GetHeader("X-Idempotency-Key")
	}
	return key
}
func automationBody(c *gin.Context) (json.RawMessage, bool) {
	body, err := io.ReadAll(http.MaxBytesReader(c.Writer, c.Request.Body, 256*1024))
	if err != nil {
		c.JSON(413, ErrorResponse{Error: "Request body exceeds 256 KiB"})
		return nil, false
	}
	if len(body) == 0 {
		return nil, true
	}
	if !json.Valid(body) {
		c.JSON(400, ErrorResponse{Error: "Valid JSON is required"})
		return nil, false
	}
	return body, true
}
func (s *Server) triggerAutomation(c *gin.Context) {
	if internal, _ := c.Get(ctxInternalAuth); internal == true {
		c.JSON(403, ErrorResponse{Error: "Use the dedicated webhook endpoint for machine triggers"})
		return
	}

	id, ok := parseUUIDParam(c, "automationId")
	if !ok {
		return
	}
	input, ok := automationBody(c)
	if !ok {
		return
	}
	source := "manual"
	if c.Query("test") == "true" {
		source = "test"
	}
	run, err := s.automationService().TriggerSource(c, id, uuid.Nil, source, s.operatorFromContext(c), automationKey(c), input, nil)
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	c.JSON(202, gin.H{"run": run})
}
func (s *Server) listAutomationRuns(c *gin.Context) {
	id, ok := parseUUIDParam(c, "automationId")
	if !ok {
		return
	}
	limit, offset := collaborationPagination(c)
	items, err := s.store.Collaboration().ListAutomationRuns(c, id, limit, offset)
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	for _, item := range items {
		item.Snapshot = nil
	}
	c.JSON(200, gin.H{"items": items})
}
func (s *Server) scopedAutomationRun(c *gin.Context) (*controlmodel.AutomationRun, bool) {
	id, ok := parseUUIDParam(c, "automationId")
	if !ok {
		return nil, false
	}
	runID, ok := parseUUIDParam(c, "automationRunId")
	if !ok {
		return nil, false
	}
	run, err := s.store.Collaboration().GetAutomationRun(c, runID)
	if err != nil {
		s.writeAutomationError(c, err)
		return nil, false
	}
	if run.AutomationID != id {
		c.JSON(404, ErrorResponse{Error: "Run not found"})
		return nil, false
	}
	return run, true
}
func (s *Server) getAutomationRun(c *gin.Context) {
	run, ok := s.scopedAutomationRun(c)
	if !ok {
		return
	}
	result := gin.H{"run": run}
	if run.IssueID != nil {
		issue, err := s.store.Collaboration().GetIssue(c, *run.IssueID)
		if err != nil {
			s.writeAutomationError(c, err)
			return
		}
		result["issue"] = issue
		filter := store.AgentTaskFilter{IssueID: issue.ID, Limit: 200}
		if run.OrchestrationRunID != nil {
			filter.IssueID = uuid.Nil
			filter.RunID = *run.OrchestrationRunID
		}
		tasks, err := s.store.Collaboration().ListAgentTasks(c, filter)
		if err != nil {
			s.writeAutomationError(c, err)
			return
		}
		result["tasks"] = tasks
		artifacts, err := s.store.Collaboration().ListArtifacts(c, run.Tenant, run.Namespace, "issue", issue.ID.String())
		if err != nil {
			s.writeAutomationError(c, err)
			return
		}
		result["artifacts"] = artifacts
	}
	c.JSON(200, result)
}
func (s *Server) cancelAutomationRun(c *gin.Context) {
	run, ok := s.scopedAutomationRun(c)
	if !ok {
		return
	}
	updated, err := s.automationService().Cancel(c, run.ID)
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	c.JSON(200, gin.H{"run": updated})
}
func (s *Server) rerunAutomationRun(c *gin.Context) {
	old, ok := s.scopedAutomationRun(c)
	if !ok {
		return
	}
	if !controlmodel.IsAutomationRunTerminal(old.Status) {
		c.JSON(409, ErrorResponse{Error: "Finish or cancel this run before running it again"})
		return
	}
	run, err := s.automationService().TriggerSource(c, old.AutomationID, uuid.Nil, "manual", s.operatorFromContext(c), automationKey(c), old.Input, &old.ID)
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	c.JSON(202, gin.H{"run": run})
}
func (s *Server) previewAutomationSchedule(c *gin.Context) {
	var req struct {
		Schedule string `json:"schedule"`
		Timezone string `json:"timezone"`
	}
	if c.ShouldBindJSON(&req) != nil {
		c.JSON(400, ErrorResponse{Error: "schedule and timezone are required"})
		return
	}
	times, err := automation.Preview(req.Schedule, req.Timezone, time.Now().UTC(), 5)
	if err != nil {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(200, gin.H{"nextRuns": times})
}
func (s *Server) rotateAutomationSecret(c *gin.Context) {
	id, ok := parseUUIDParam(c, "automationId")
	if !ok {
		return
	}
	var req struct {
		ExpectedVersion int64 `json:"expectedVersion"`
	}
	if c.ShouldBindJSON(&req) != nil || req.ExpectedVersion <= 0 {
		c.JSON(400, ErrorResponse{Error: "positive expectedVersion is required"})
		return
	}
	rule, err := s.store.Collaboration().GetAutomation(c, id)
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	automation.NormalizeLegacy(rule)
	rule.WebhookSecretHash = ""
	secret, err := automationSecret(rule)
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	if secret == "" {
		c.JSON(400, ErrorResponse{Error: "Add a webhook trigger first"})
		return
	}
	rule, err = s.automationService().Update(c, rule, req.ExpectedVersion)
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	c.JSON(200, gin.H{"automation": rule, "webhookSecret": secret})
}
func (s *Server) listAutomationDeliveries(c *gin.Context) {
	id, ok := parseUUIDParam(c, "automationId")
	if !ok {
		return
	}
	limit, offset := collaborationPagination(c)
	items, err := s.store.Collaboration().ListAutomationDeliveries(c, id, limit, offset)
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	c.JSON(200, gin.H{"items": items})
}
func (s *Server) replayAutomationDelivery(c *gin.Context) {
	id, ok := parseUUIDParam(c, "automationId")
	if !ok {
		return
	}
	deliveryID, ok := parseUUIDParam(c, "deliveryId")
	if !ok {
		return
	}
	key := automationKey(c)
	if key == "" || len(key) > 180 {
		c.JSON(400, ErrorResponse{Error: "valid Idempotency-Key is required"})
		return
	}
	d, err := s.automationService().ReplayDelivery(c, id, deliveryID, key)
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	c.JSON(202, gin.H{"delivery": d})
}

type automationRateBucket struct {
	start time.Time
	count int
}

var automationRateMu sync.Mutex
var automationRates = map[string]automationRateBucket{}

func allowAutomationWebhook(key string) bool {
	automationRateMu.Lock()
	defer automationRateMu.Unlock()
	now := time.Now()
	v := automationRates[key]
	if now.Sub(v.start) >= time.Minute {
		v = automationRateBucket{start: now}
	}
	if v.count >= 60 {
		return false
	}
	if len(automationRates) >= 10000 {
		for k, b := range automationRates {
			if now.Sub(b.start) >= time.Minute {
				delete(automationRates, k)
			}
		}
		if len(automationRates) >= 10000 {
			return false
		}
	}
	v.count++
	automationRates[key] = v
	return true
}
func (s *Server) automationWebhook(c *gin.Context) {
	id, ok := parseUUIDParam(c, "automationId")
	if !ok {
		return
	}
	triggerID, ok := parseUUIDParam(c, "triggerId")
	if !ok {
		return
	}
	rule, err := s.store.Collaboration().GetAutomation(c, id)
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	automation.NormalizeLegacy(rule)
	exists := false
	for _, t := range rule.Triggers {
		if t.ID == triggerID && t.Type == controlmodel.AutomationTriggerWebhook {
			exists = true
		}
	}
	if !exists {
		c.JSON(404, ErrorResponse{Error: "Webhook not found"})
		return
	}
	if !allowAutomationWebhook(id.String() + ":" + c.ClientIP()) {
		c.Header("Retry-After", "60")
		c.JSON(429, ErrorResponse{Error: "Webhook rate limit exceeded"})
		return
	}
	provided := c.GetHeader("X-Automation-Secret")
	sum := sha256.Sum256([]byte(provided))
	expected, _ := hex.DecodeString(rule.WebhookSecretHash)
	if len(expected) != len(sum) || subtle.ConstantTimeCompare(expected, sum[:]) != 1 {
		c.JSON(401, ErrorResponse{Error: "Invalid webhook credential"})
		return
	}
	body, ok := automationBody(c)
	if !ok {
		return
	}
	trimmed := bytesTrim(body)
	if len(trimmed) == 0 || (trimmed[0] != '{' && trimmed[0] != '[') {
		c.JSON(400, ErrorResponse{Error: "Webhook body must be a JSON object or array"})
		return
	}
	key := automationKey(c)
	if key == "" {
		key = c.GetHeader("X-GitHub-Delivery")
	}
	if key == "" {
		c.JSON(400, ErrorResponse{Error: "Idempotency-Key is required"})
		return
	}
	if len(key) > 200 {
		c.JSON(400, ErrorResponse{Error: "Idempotency-Key is too long"})
		return
	}
	event := c.GetHeader("X-GitHub-Event")
	if event == "" {
		event = c.GetHeader("X-Event-Type")
	}
	var envelope struct {
		Event  string `json:"event"`
		Action string `json:"action"`
	}
	_ = json.Unmarshal(body, &envelope)
	if envelope.Event != "" {
		event = envelope.Event
	}
	if event == "" {
		event = "webhook.received"
	}
	d, fresh, err := s.store.Collaboration().SaveAutomationDelivery(c, &controlmodel.AutomationDelivery{ID: uuid.New(), AutomationID: id, TriggerID: triggerID, IdempotencyKey: key, Input: body, Event: event, Status: "queued"})
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	d, err = s.automationService().ProcessDelivery(c, d)
	if err != nil {
		s.writeAutomationError(c, err)
		return
	}
	status := d.Status
	if !fresh {
		status = "duplicate"
	}
	c.JSON(200, gin.H{"status": status, "delivery": d})
}
func bytesTrim(b []byte) []byte { return []byte(strings.TrimSpace(string(b))) }

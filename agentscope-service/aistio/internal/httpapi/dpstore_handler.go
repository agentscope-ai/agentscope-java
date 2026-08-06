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
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

const (
	maxKVValueBytes      = 1 << 20  // 1 MiB
	maxSnapshotBodyBytes = 32 << 20 // 32 MiB
)

// tenantOf normalizes agentName + namespace into the hosted-store tenant key.
func tenantOf(agentName, namespace string) string {
	agentName = strings.TrimSpace(agentName)
	namespace = strings.TrimSpace(namespace)
	if namespace == "" {
		namespace = defaultNamespace
	}
	return namespace + "/" + agentName
}

func (s *Server) registerHostedStoreRoutes(g *gin.RouterGroup) {
	g.GET("/healthz", s.dpStoreHealthz)

	g.GET("/kv/item", s.dpKVGet)
	g.PUT("/kv/item", s.dpKVPut)
	g.DELETE("/kv/item", s.dpKVDelete)
	g.GET("/kv/search", s.dpKVSearch)

	g.POST("/locks/acquire", s.dpLockAcquire)
	g.POST("/locks/renew", s.dpLockRenew)
	g.POST("/locks/release", s.dpLockRelease)
	g.GET("/locks", s.dpLockPeek)

	g.GET("/tasks/pending-deliveries", s.dpTaskPendingDeliveries)
	g.POST("/tasks/heartbeat", s.dpTaskHeartbeat)
	g.PUT("/tasks/:id", s.dpTaskPut)
	g.GET("/tasks/:id", s.dpTaskGet)
	g.GET("/tasks", s.dpTaskList)
	g.POST("/tasks/:id/cancel", s.dpTaskCancel)
	g.POST("/tasks/:id/delivered", s.dpTaskDelivered)
	g.DELETE("/tasks/:id", s.dpTaskDelete)

	g.PUT("/snapshots/:id", s.dpSnapshotPut)
	g.GET("/snapshots/:id", s.dpSnapshotGet)
	g.HEAD("/snapshots/:id", s.dpSnapshotHead)
	g.POST("/snapshots/:id/upload-url", s.dpSnapshotUploadURL)

	g.POST("/bus/queue/push", s.dpBusQueuePush)
	g.POST("/bus/queue/drain", s.dpBusQueueDrain)
	g.POST("/bus/queue/delete", s.dpBusQueueDelete)
	g.GET("/bus/queue/peek", s.dpBusQueuePeek)
	g.POST("/bus/log/append", s.dpBusLogAppend)
	g.GET("/bus/log/read", s.dpBusLogRead)
	g.POST("/bus/log/trim", s.dpBusLogTrim)

	g.POST("/async-tools", s.dpAsyncToolRegister)
	g.POST("/async-tools/:id/complete", s.dpAsyncToolComplete)
	g.POST("/async-tools/:id/fail", s.dpAsyncToolFail)
	g.POST("/async-tools/:id/timeout", s.dpAsyncToolTimeout)
	g.GET("/async-tools/stale", s.dpAsyncToolStale)
}

func (s *Server) requireHostedStore(c *gin.Context) bool {
	if s.store == nil {
		c.JSON(http.StatusServiceUnavailable, ErrorResponse{Error: "hosted store unavailable"})
		return false
	}
	return true
}

// dpTenantQuery resolves tenant from query parameters. agentName is required.
func dpTenantQuery(c *gin.Context) (tenant string, ok bool) {
	agentName := strings.TrimSpace(c.Query("agentName"))
	if agentName == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "agentName is required"})
		return "", false
	}
	namespace := c.DefaultQuery("namespace", defaultNamespace)
	return tenantOf(agentName, namespace), true
}

type dpTenantBody struct {
	AgentName string `json:"agentName"`
	Namespace string `json:"namespace"`
}

func (b dpTenantBody) tenant(c *gin.Context) (string, bool) {
	agentName := strings.TrimSpace(b.AgentName)
	if agentName == "" {
		agentName = strings.TrimSpace(c.Query("agentName"))
	}
	if agentName == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "agentName is required"})
		return "", false
	}
	namespace := strings.TrimSpace(b.Namespace)
	if namespace == "" {
		namespace = c.DefaultQuery("namespace", defaultNamespace)
	}
	return tenantOf(agentName, namespace), true
}

func (s *Server) dpStoreHealthz(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

// --- KV -------------------------------------------------------------------

type kvPutRequest struct {
	dpTenantBody
	NamespaceSegments []string        `json:"namespaceSegments"`
	Ns                []string        `json:"ns"`
	Key               string          `json:"key"`
	Value             json.RawMessage `json:"value"`
	ExpectedVersion   *int64          `json:"expectedVersion"`
}

func nsPathFromQuery(c *gin.Context) string {
	return store.JoinNamespacePath(c.QueryArray("ns"))
}

func nsPathFromBody(segments, ns []string) string {
	if len(segments) > 0 {
		return store.JoinNamespacePath(segments)
	}
	return store.JoinNamespacePath(ns)
}

func (s *Server) dpKVGet(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("kv", "error", start)
		return
	}
	tenant, ok := dpTenantQuery(c)
	if !ok {
		observeDPStore("kv", "error", start)
		return
	}
	key := c.Query("key")
	if key == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "key is required"})
		observeDPStore("kv", "error", start)
		return
	}
	item, err := s.store.KV().Get(c.Request.Context(), tenant, nsPathFromQuery(c), key)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "not found"})
			observeDPStore("kv", "not_found", start)
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("kv", "error", start)
		return
	}
	c.JSON(http.StatusOK, item)
	observeDPStore("kv", "ok", start)
}

func (s *Server) dpKVPut(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("kv", "error", start)
		return
	}
	var req kvPutRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body"})
		observeDPStore("kv", "error", start)
		return
	}
	tenant, ok := req.tenant(c)
	if !ok {
		observeDPStore("kv", "error", start)
		return
	}
	if strings.TrimSpace(req.Key) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "key is required"})
		observeDPStore("kv", "error", start)
		return
	}
	if len(req.Value) > maxKVValueBytes {
		c.JSON(http.StatusRequestEntityTooLarge, ErrorResponse{Error: "value exceeds 1 MiB limit"})
		observeDPStore("kv", "error", start)
		return
	}
	if req.Value == nil {
		req.Value = json.RawMessage("{}")
	}
	nsPath := nsPathFromBody(req.NamespaceSegments, req.Ns)
	ctx := c.Request.Context()

	if req.ExpectedVersion != nil {
		newVer, written, err := s.store.KV().PutIfVersion(ctx, tenant, nsPath, req.Key, req.Value, *req.ExpectedVersion)
		if err != nil {
			if errors.Is(err, store.ErrNotFound) {
				c.JSON(http.StatusNotFound, ErrorResponse{Error: "not found"})
				observeDPStore("kv", "not_found", start)
				return
			}
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
			observeDPStore("kv", "error", start)
			return
		}
		if !written {
			c.JSON(http.StatusConflict, gin.H{"error": "conflict", "currentVersion": newVer})
			observeDPStore("kv", "conflict", start)
			return
		}
		c.JSON(http.StatusOK, gin.H{"version": newVer})
		observeDPStore("kv", "ok", start)
		return
	}

	ver, err := s.store.KV().Put(ctx, tenant, nsPath, req.Key, req.Value)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("kv", "error", start)
		return
	}
	c.JSON(http.StatusOK, gin.H{"version": ver})
	observeDPStore("kv", "ok", start)
}

func (s *Server) dpKVDelete(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("kv", "error", start)
		return
	}
	tenant, ok := dpTenantQuery(c)
	if !ok {
		observeDPStore("kv", "error", start)
		return
	}
	key := c.Query("key")
	if key == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "key is required"})
		observeDPStore("kv", "error", start)
		return
	}
	if err := s.store.KV().Delete(c.Request.Context(), tenant, nsPathFromQuery(c), key); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("kv", "error", start)
		return
	}
	c.Status(http.StatusNoContent)
	observeDPStore("kv", "ok", start)
}

func (s *Server) dpKVSearch(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("kv", "error", start)
		return
	}
	tenant, ok := dpTenantQuery(c)
	if !ok {
		observeDPStore("kv", "error", start)
		return
	}
	items, err := s.store.KV().Search(c.Request.Context(), tenant, nsPathFromQuery(c), parseLimit(c, 100), parseOffset(c))
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("kv", "error", start)
		return
	}
	if items == nil {
		items = []*store.KVItem{}
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
	observeDPStore("kv", "ok", start)
}

// --- Locks ----------------------------------------------------------------

type lockAcquireRequest struct {
	dpTenantBody
	Name       string `json:"name"`
	TTLSeconds int64  `json:"ttlSeconds"`
	Holder     string `json:"holder"`
}

type lockRenewRequest struct {
	dpTenantBody
	Name       string `json:"name"`
	OwnerToken string `json:"ownerToken"`
	TTLSeconds int64  `json:"ttlSeconds"`
}

type lockReleaseRequest struct {
	dpTenantBody
	Name       string `json:"name"`
	OwnerToken string `json:"ownerToken"`
}

func (s *Server) dpLockAcquire(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("locks", "error", start)
		return
	}
	var req lockAcquireRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body"})
		observeDPStore("locks", "error", start)
		return
	}
	tenant, ok := req.tenant(c)
	if !ok {
		observeDPStore("locks", "error", start)
		return
	}
	if strings.TrimSpace(req.Name) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "name is required"})
		observeDPStore("locks", "error", start)
		return
	}
	ttl := time.Duration(req.TTLSeconds) * time.Second
	if ttl <= 0 {
		ttl = 60 * time.Second
	}
	ownerToken := uuid.New().String()
	lk, err := s.store.Locks().Acquire(c.Request.Context(), tenant, req.Name, ownerToken, req.Holder, ttl)
	if errors.Is(err, store.ErrConflict) {
		resp := gin.H{"error": "conflict"}
		if lk != nil {
			resp["holder"] = lk.Holder
			resp["expiresAt"] = lk.ExpiresAt
		}
		c.JSON(http.StatusConflict, resp)
		observeDPStore("locks", "conflict", start)
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("locks", "error", start)
		return
	}
	dpLocksHeld.Inc()
	c.JSON(http.StatusOK, gin.H{
		"ownerToken":   lk.OwnerToken,
		"fencingToken": lk.FencingToken,
		"expiresAt":    lk.ExpiresAt,
		"holder":       lk.Holder,
		"name":         lk.Name,
	})
	observeDPStore("locks", "ok", start)
}

func (s *Server) dpLockRenew(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("locks", "error", start)
		return
	}
	var req lockRenewRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body"})
		observeDPStore("locks", "error", start)
		return
	}
	tenant, ok := req.tenant(c)
	if !ok {
		observeDPStore("locks", "error", start)
		return
	}
	if strings.TrimSpace(req.Name) == "" || strings.TrimSpace(req.OwnerToken) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "name and ownerToken are required"})
		observeDPStore("locks", "error", start)
		return
	}
	ttl := time.Duration(req.TTLSeconds) * time.Second
	if ttl <= 0 {
		ttl = 60 * time.Second
	}
	lk, err := s.store.Locks().Renew(c.Request.Context(), tenant, req.Name, req.OwnerToken, ttl)
	if errors.Is(err, store.ErrConflict) {
		c.JSON(http.StatusConflict, ErrorResponse{Error: "conflict"})
		observeDPStore("locks", "conflict", start)
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("locks", "error", start)
		return
	}
	c.JSON(http.StatusOK, gin.H{"expiresAt": lk.ExpiresAt})
	observeDPStore("locks", "ok", start)
}

func (s *Server) dpLockRelease(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("locks", "error", start)
		return
	}
	var req lockReleaseRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body"})
		observeDPStore("locks", "error", start)
		return
	}
	tenant, ok := req.tenant(c)
	if !ok {
		observeDPStore("locks", "error", start)
		return
	}
	if strings.TrimSpace(req.Name) == "" || strings.TrimSpace(req.OwnerToken) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "name and ownerToken are required"})
		observeDPStore("locks", "error", start)
		return
	}
	if err := s.store.Locks().Release(c.Request.Context(), tenant, req.Name, req.OwnerToken); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("locks", "error", start)
		return
	}
	dpLocksHeld.Dec()
	c.Status(http.StatusNoContent)
	observeDPStore("locks", "ok", start)
}

func (s *Server) dpLockPeek(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("locks", "error", start)
		return
	}
	tenant, ok := dpTenantQuery(c)
	if !ok {
		observeDPStore("locks", "error", start)
		return
	}
	name := c.Query("name")
	if name == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "name is required"})
		observeDPStore("locks", "error", start)
		return
	}
	lk, err := s.store.Locks().Peek(c.Request.Context(), tenant, name)
	if errors.Is(err, store.ErrNotFound) {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "not held"})
		observeDPStore("locks", "not_found", start)
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("locks", "error", start)
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"name":         lk.Name,
		"ownerToken":   lk.OwnerToken,
		"fencingToken": lk.FencingToken,
		"holder":       lk.Holder,
		"expiresAt":    lk.ExpiresAt,
	})
	observeDPStore("locks", "ok", start)
}

// --- Snapshots ------------------------------------------------------------

type snapshotUploadURLRequest struct {
	dpTenantBody
}

func (s *Server) dpSnapshotPut(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("snapshots", "error", start)
		return
	}
	tenant, ok := dpTenantQuery(c)
	if !ok {
		observeDPStore("snapshots", "error", start)
		return
	}
	id := c.Param("id")
	if id == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "snapshot id is required"})
		observeDPStore("snapshots", "error", start)
		return
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxSnapshotBodyBytes+1)
	payload, err := io.ReadAll(c.Request.Body)
	if err != nil {
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) || strings.Contains(err.Error(), "http: request body too large") {
			c.JSON(http.StatusRequestEntityTooLarge, ErrorResponse{Error: "snapshot exceeds 32 MiB limit"})
			observeDPStore("snapshots", "error", start)
			return
		}
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "failed to read body"})
		observeDPStore("snapshots", "error", start)
		return
	}
	if len(payload) > maxSnapshotBodyBytes {
		c.JSON(http.StatusRequestEntityTooLarge, ErrorResponse{Error: "snapshot exceeds 32 MiB limit"})
		observeDPStore("snapshots", "error", start)
		return
	}
	meta, err := s.store.Snapshots().Put(c.Request.Context(), tenant, id, payload, store.SnapshotModeInline)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("snapshots", "error", start)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"sizeBytes": meta.SizeBytes, "storageMode": meta.StorageMode})
	observeDPStore("snapshots", "ok", start)
}

func (s *Server) dpSnapshotGet(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("snapshots", "error", start)
		return
	}
	tenant, ok := dpTenantQuery(c)
	if !ok {
		observeDPStore("snapshots", "error", start)
		return
	}
	payload, _, err := s.store.Snapshots().Get(c.Request.Context(), tenant, c.Param("id"))
	if errors.Is(err, store.ErrNotFound) {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "not found"})
		observeDPStore("snapshots", "not_found", start)
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("snapshots", "error", start)
		return
	}
	_ = s.store.Snapshots().Touch(c.Request.Context(), tenant, c.Param("id"))
	c.Data(http.StatusOK, "application/octet-stream", payload)
	observeDPStore("snapshots", "ok", start)
}

func (s *Server) dpSnapshotHead(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("snapshots", "error", start)
		return
	}
	tenant, ok := dpTenantQuery(c)
	if !ok {
		observeDPStore("snapshots", "error", start)
		return
	}
	exists, err := s.store.Snapshots().Exists(c.Request.Context(), tenant, c.Param("id"))
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("snapshots", "error", start)
		return
	}
	if !exists {
		c.Status(http.StatusNotFound)
		observeDPStore("snapshots", "not_found", start)
		return
	}
	c.Status(http.StatusOK)
	observeDPStore("snapshots", "ok", start)
}

func (s *Server) dpSnapshotUploadURL(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("snapshots", "error", start)
		return
	}
	var req snapshotUploadURLRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body"})
		observeDPStore("snapshots", "error", start)
		return
	}
	if _, ok := req.tenant(c); !ok {
		observeDPStore("snapshots", "error", start)
		return
	}
	c.JSON(http.StatusOK, gin.H{"mode": "inline"})
	observeDPStore("snapshots", "ok", start)
}

// --- Bus ------------------------------------------------------------------

type busKeyRequest struct {
	dpTenantBody
	Key      string          `json:"key"`
	Payload  json.RawMessage `json:"payload"`
	MaxCount int             `json:"maxCount"`
	MaxLen   int             `json:"maxLen"`
}

func (s *Server) dpBusQueuePush(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("bus", "error", start)
		return
	}
	var req busKeyRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body"})
		observeDPStore("bus", "error", start)
		return
	}
	tenant, ok := req.tenant(c)
	if !ok {
		observeDPStore("bus", "error", start)
		return
	}
	if strings.TrimSpace(req.Key) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "key is required"})
		observeDPStore("bus", "error", start)
		return
	}
	if req.Payload == nil {
		req.Payload = json.RawMessage("{}")
	}
	entryID, err := s.store.Bus().QueuePush(c.Request.Context(), tenant, req.Key, req.Payload)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("bus", "error", start)
		return
	}
	c.JSON(http.StatusOK, gin.H{"entryId": entryID})
	observeDPStore("bus", "ok", start)
}

func (s *Server) dpBusQueueDrain(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("bus", "error", start)
		return
	}
	var req busKeyRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body"})
		observeDPStore("bus", "error", start)
		return
	}
	tenant, ok := req.tenant(c)
	if !ok {
		observeDPStore("bus", "error", start)
		return
	}
	if strings.TrimSpace(req.Key) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "key is required"})
		observeDPStore("bus", "error", start)
		return
	}
	maxCount := req.MaxCount
	if maxCount <= 0 {
		maxCount = 10
	}
	entries, err := s.store.Bus().QueueDrain(c.Request.Context(), tenant, req.Key, maxCount)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("bus", "error", start)
		return
	}
	if entries == nil {
		entries = []*store.BusEntry{}
	}
	c.JSON(http.StatusOK, gin.H{"entries": entries})
	observeDPStore("bus", "ok", start)
}

func (s *Server) dpBusQueueDelete(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("bus", "error", start)
		return
	}
	var req busKeyRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body"})
		observeDPStore("bus", "error", start)
		return
	}
	tenant, ok := req.tenant(c)
	if !ok {
		observeDPStore("bus", "error", start)
		return
	}
	if strings.TrimSpace(req.Key) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "key is required"})
		observeDPStore("bus", "error", start)
		return
	}
	if err := s.store.Bus().QueueDelete(c.Request.Context(), tenant, req.Key); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("bus", "error", start)
		return
	}
	c.Status(http.StatusNoContent)
	observeDPStore("bus", "ok", start)
}

func (s *Server) dpBusQueuePeek(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("bus", "error", start)
		return
	}
	tenant, ok := dpTenantQuery(c)
	if !ok {
		observeDPStore("bus", "error", start)
		return
	}
	key := c.Query("key")
	if key == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "key is required"})
		observeDPStore("bus", "error", start)
		return
	}
	exists, err := s.store.Bus().QueuePeek(c.Request.Context(), tenant, key)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("bus", "error", start)
		return
	}
	c.JSON(http.StatusOK, gin.H{"exists": exists})
	observeDPStore("bus", "ok", start)
}

func (s *Server) dpBusLogAppend(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("bus", "error", start)
		return
	}
	var req busKeyRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body"})
		observeDPStore("bus", "error", start)
		return
	}
	tenant, ok := req.tenant(c)
	if !ok {
		observeDPStore("bus", "error", start)
		return
	}
	if strings.TrimSpace(req.Key) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "key is required"})
		observeDPStore("bus", "error", start)
		return
	}
	if req.Payload == nil {
		req.Payload = json.RawMessage("{}")
	}
	entryID, err := s.store.Bus().LogAppend(c.Request.Context(), tenant, req.Key, req.Payload, req.MaxLen)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("bus", "error", start)
		return
	}
	c.JSON(http.StatusOK, gin.H{"entryId": entryID})
	observeDPStore("bus", "ok", start)
}

func (s *Server) dpBusLogRead(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("bus", "error", start)
		return
	}
	tenant, ok := dpTenantQuery(c)
	if !ok {
		observeDPStore("bus", "error", start)
		return
	}
	key := c.Query("key")
	if key == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "key is required"})
		observeDPStore("bus", "error", start)
		return
	}
	maxCount := 50
	if v := c.Query("maxCount"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			maxCount = n
		}
	}
	entries, err := s.store.Bus().LogRead(c.Request.Context(), tenant, key, c.Query("since"), maxCount)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("bus", "error", start)
		return
	}
	if entries == nil {
		entries = []*store.BusEntry{}
	}
	c.JSON(http.StatusOK, gin.H{"entries": entries})
	observeDPStore("bus", "ok", start)
}

func (s *Server) dpBusLogTrim(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("bus", "error", start)
		return
	}
	var req busKeyRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body"})
		observeDPStore("bus", "error", start)
		return
	}
	tenant, ok := req.tenant(c)
	if !ok {
		observeDPStore("bus", "error", start)
		return
	}
	if strings.TrimSpace(req.Key) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "key is required"})
		observeDPStore("bus", "error", start)
		return
	}
	if err := s.store.Bus().LogTrim(c.Request.Context(), tenant, req.Key); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("bus", "error", start)
		return
	}
	c.Status(http.StatusNoContent)
	observeDPStore("bus", "ok", start)
}

// --- Async tools ----------------------------------------------------------

type asyncToolRegisterRequest struct {
	dpTenantBody
	ID         string     `json:"id"`
	SessionID  string     `json:"sessionId"`
	ToolName   string     `json:"toolName"`
	ToolCallID string     `json:"toolCallId"`
	Status     string     `json:"status"`
	Result     string     `json:"result"`
	Error      string     `json:"error"`
	CreatedAt  *time.Time `json:"createdAt"`
}

type asyncToolCompleteRequest struct {
	dpTenantBody
	Result string `json:"result"`
}

type asyncToolFailRequest struct {
	dpTenantBody
	Error string `json:"error"`
}

type asyncToolTimeoutRequest struct {
	dpTenantBody
}

func (s *Server) dpAsyncToolRegister(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("async_tools", "error", start)
		return
	}
	var req asyncToolRegisterRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body"})
		observeDPStore("async_tools", "error", start)
		return
	}
	tenant, ok := req.tenant(c)
	if !ok {
		observeDPStore("async_tools", "error", start)
		return
	}
	if strings.TrimSpace(req.ID) == "" || strings.TrimSpace(req.SessionID) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "id and sessionId are required"})
		observeDPStore("async_tools", "error", start)
		return
	}
	status := req.Status
	if status == "" {
		status = store.AsyncToolRunning
	}
	now := time.Now().UTC()
	created := now
	if req.CreatedAt != nil {
		created = req.CreatedAt.UTC()
	}
	rec := &store.AsyncToolRecord{
		ID:         req.ID,
		Tenant:     tenant,
		SessionID:  req.SessionID,
		ToolName:   req.ToolName,
		ToolCallID: req.ToolCallID,
		Status:     status,
		Result:     req.Result,
		Error:      req.Error,
		CreatedAt:  created,
		UpdatedAt:  now,
	}
	if err := s.store.AsyncTools().Register(c.Request.Context(), rec); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("async_tools", "error", start)
		return
	}
	c.JSON(http.StatusOK, gin.H{"id": rec.ID})
	observeDPStore("async_tools", "ok", start)
}

func (s *Server) dpAsyncToolComplete(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("async_tools", "error", start)
		return
	}
	var req asyncToolCompleteRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body"})
		observeDPStore("async_tools", "error", start)
		return
	}
	tenant, ok := req.tenant(c)
	if !ok {
		observeDPStore("async_tools", "error", start)
		return
	}
	if err := s.store.AsyncTools().Complete(c.Request.Context(), tenant, c.Param("id"), req.Result); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "not found"})
			observeDPStore("async_tools", "not_found", start)
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("async_tools", "error", start)
		return
	}
	c.Status(http.StatusNoContent)
	observeDPStore("async_tools", "ok", start)
}

func (s *Server) dpAsyncToolFail(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("async_tools", "error", start)
		return
	}
	var req asyncToolFailRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body"})
		observeDPStore("async_tools", "error", start)
		return
	}
	tenant, ok := req.tenant(c)
	if !ok {
		observeDPStore("async_tools", "error", start)
		return
	}
	if err := s.store.AsyncTools().Fail(c.Request.Context(), tenant, c.Param("id"), req.Error); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "not found"})
			observeDPStore("async_tools", "not_found", start)
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("async_tools", "error", start)
		return
	}
	c.Status(http.StatusNoContent)
	observeDPStore("async_tools", "ok", start)
}

func (s *Server) dpAsyncToolTimeout(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("async_tools", "error", start)
		return
	}
	var req asyncToolTimeoutRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body"})
		observeDPStore("async_tools", "error", start)
		return
	}
	tenant, ok := req.tenant(c)
	if !ok {
		observeDPStore("async_tools", "error", start)
		return
	}
	if err := s.store.AsyncTools().MarkTimeout(c.Request.Context(), tenant, c.Param("id")); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "not found"})
			observeDPStore("async_tools", "not_found", start)
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("async_tools", "error", start)
		return
	}
	c.Status(http.StatusNoContent)
	observeDPStore("async_tools", "ok", start)
}

func (s *Server) dpAsyncToolStale(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("async_tools", "error", start)
		return
	}
	tenant, ok := dpTenantQuery(c)
	if !ok {
		observeDPStore("async_tools", "error", start)
		return
	}
	sessionID := c.Query("sessionId")
	if sessionID == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "sessionId is required"})
		observeDPStore("async_tools", "error", start)
		return
	}
	ttlSeconds, _ := strconv.ParseInt(c.Query("ttlSeconds"), 10, 64)
	if ttlSeconds < 0 {
		ttlSeconds = 0
	}
	recs, err := s.store.AsyncTools().FindStale(c.Request.Context(), tenant, sessionID, time.Duration(ttlSeconds)*time.Second)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("async_tools", "error", start)
		return
	}
	if recs == nil {
		recs = []*store.AsyncToolRecord{}
	}
	c.JSON(http.StatusOK, gin.H{"records": recs})
	observeDPStore("async_tools", "ok", start)
}

// --- Tasks ----------------------------------------------------------------

type dpTaskUpsertRequest struct {
	dpTenantBody
	ParentSessionID string          `json:"parentSessionId"`
	SubAgentID      string          `json:"subAgentId"`
	SubSessionID    string          `json:"subSessionId"`
	Status          string          `json:"status"`
	Result          string          `json:"result"`
	ErrorMessage    string          `json:"errorMessage"`
	CancelRequested *bool           `json:"cancelRequested"`
	TransportType   string          `json:"transportType"`
	RemoteBaseURL   string          `json:"remoteBaseUrl"`
	RemoteHeaders   json.RawMessage `json:"remoteHeaders"`
	UserID          string          `json:"userId"`
	LastCheckedAt   *time.Time      `json:"lastCheckedAt"`
}

type dpTaskHeartbeatRequest struct {
	dpTenantBody
	Tasks []store.DPTaskRef `json:"tasks"`
}

type dpTaskSessionQuery struct {
	dpTenantBody
	ParentSessionID string `json:"parentSessionId"`
}

func parentAgentID(c *gin.Context) (agentName string, ok bool) {
	agentName = strings.TrimSpace(c.Query("agentName"))
	if agentName == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "agentName is required"})
		return "", false
	}
	return agentName, true
}

func (s *Server) dpTaskPut(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("tasks", "error", start)
		return
	}
	var req dpTaskUpsertRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body"})
		observeDPStore("tasks", "error", start)
		return
	}
	tenant, ok := req.tenant(c)
	if !ok {
		observeDPStore("tasks", "error", start)
		return
	}
	parentSessionID := strings.TrimSpace(req.ParentSessionID)
	if parentSessionID == "" {
		parentSessionID = strings.TrimSpace(c.Query("parentSessionId"))
	}
	if parentSessionID == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "parentSessionId is required"})
		observeDPStore("tasks", "error", start)
		return
	}
	taskID := c.Param("id")
	if taskID == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "task id is required"})
		observeDPStore("tasks", "error", start)
		return
	}
	agentName := strings.TrimSpace(req.AgentName)
	if agentName == "" {
		agentName = strings.TrimSpace(c.Query("agentName"))
	}
	status := req.Status
	if status == "" {
		status = store.DPTaskStatusPending
	}
	cancelRequested := false
	if req.CancelRequested != nil {
		cancelRequested = *req.CancelRequested
	}
	task := &store.DPTask{
		Tenant:          tenant,
		ParentAgentID:   agentName,
		ParentSessionID: parentSessionID,
		TaskID:          taskID,
		SubAgentID:      req.SubAgentID,
		SubSessionID:    req.SubSessionID,
		Status:          status,
		Terminal:        store.IsTerminalTaskStatus(status),
		Result:          req.Result,
		ErrorMessage:    req.ErrorMessage,
		CancelRequested: cancelRequested,
		TransportType:   req.TransportType,
		RemoteBaseURL:   req.RemoteBaseURL,
		RemoteHeaders:   req.RemoteHeaders,
		UserID:          req.UserID,
		LastCheckedAt:   req.LastCheckedAt,
	}
	out, err := s.store.Tasks().Upsert(c.Request.Context(), task)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("tasks", "error", start)
		return
	}
	c.JSON(http.StatusOK, out)
	observeDPStore("tasks", "ok", start)
}

func (s *Server) dpTaskGet(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("tasks", "error", start)
		return
	}
	tenant, ok := dpTenantQuery(c)
	if !ok {
		observeDPStore("tasks", "error", start)
		return
	}
	agentName, ok := parentAgentID(c)
	if !ok {
		observeDPStore("tasks", "error", start)
		return
	}
	parentSessionID := c.Query("parentSessionId")
	if parentSessionID == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "parentSessionId is required"})
		observeDPStore("tasks", "error", start)
		return
	}
	task, err := s.store.Tasks().Get(c.Request.Context(), tenant, agentName, parentSessionID, c.Param("id"))
	if errors.Is(err, store.ErrNotFound) {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "not found"})
		observeDPStore("tasks", "not_found", start)
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("tasks", "error", start)
		return
	}
	c.JSON(http.StatusOK, task)
	observeDPStore("tasks", "ok", start)
}

func (s *Server) dpTaskList(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("tasks", "error", start)
		return
	}
	tenant, ok := dpTenantQuery(c)
	if !ok {
		observeDPStore("tasks", "error", start)
		return
	}
	agentName, ok := parentAgentID(c)
	if !ok {
		observeDPStore("tasks", "error", start)
		return
	}
	parentSessionID := c.Query("parentSessionId")
	if parentSessionID == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "parentSessionId is required"})
		observeDPStore("tasks", "error", start)
		return
	}
	tasks, err := s.store.Tasks().List(c.Request.Context(), tenant, agentName, parentSessionID, c.Query("status"))
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("tasks", "error", start)
		return
	}
	if tasks == nil {
		tasks = []*store.DPTask{}
	}
	c.JSON(http.StatusOK, gin.H{"tasks": tasks})
	observeDPStore("tasks", "ok", start)
}

func (s *Server) dpTaskHeartbeat(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("tasks", "error", start)
		return
	}
	var req dpTaskHeartbeatRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body"})
		observeDPStore("tasks", "error", start)
		return
	}
	tenant, ok := req.tenant(c)
	if !ok {
		observeDPStore("tasks", "error", start)
		return
	}
	agentName := strings.TrimSpace(req.AgentName)
	if agentName == "" {
		agentName = strings.TrimSpace(c.Query("agentName"))
	}
	if agentName == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "agentName is required"})
		observeDPStore("tasks", "error", start)
		return
	}
	if err := s.store.Tasks().Heartbeat(c.Request.Context(), tenant, agentName, req.Tasks); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("tasks", "error", start)
		return
	}
	c.Status(http.StatusNoContent)
	observeDPStore("tasks", "ok", start)
}

func (s *Server) dpTaskCancel(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("tasks", "error", start)
		return
	}
	var req dpTaskSessionQuery
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body"})
		observeDPStore("tasks", "error", start)
		return
	}
	tenant, ok := req.tenant(c)
	if !ok {
		observeDPStore("tasks", "error", start)
		return
	}
	agentName := strings.TrimSpace(req.AgentName)
	if agentName == "" {
		agentName = strings.TrimSpace(c.Query("agentName"))
	}
	parentSessionID := strings.TrimSpace(req.ParentSessionID)
	if parentSessionID == "" {
		parentSessionID = strings.TrimSpace(c.Query("parentSessionId"))
	}
	if parentSessionID == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "parentSessionId is required"})
		observeDPStore("tasks", "error", start)
		return
	}
	if err := s.store.Tasks().RequestCancel(c.Request.Context(), tenant, agentName, parentSessionID, c.Param("id")); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "not found"})
			observeDPStore("tasks", "not_found", start)
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("tasks", "error", start)
		return
	}
	c.Status(http.StatusNoContent)
	observeDPStore("tasks", "ok", start)
}

func (s *Server) dpTaskDelivered(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("tasks", "error", start)
		return
	}
	var req dpTaskSessionQuery
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "invalid request body"})
		observeDPStore("tasks", "error", start)
		return
	}
	tenant, ok := req.tenant(c)
	if !ok {
		observeDPStore("tasks", "error", start)
		return
	}
	agentName := strings.TrimSpace(req.AgentName)
	if agentName == "" {
		agentName = strings.TrimSpace(c.Query("agentName"))
	}
	parentSessionID := strings.TrimSpace(req.ParentSessionID)
	if parentSessionID == "" {
		parentSessionID = strings.TrimSpace(c.Query("parentSessionId"))
	}
	if parentSessionID == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "parentSessionId is required"})
		observeDPStore("tasks", "error", start)
		return
	}
	written, err := s.store.Tasks().MarkDelivered(c.Request.Context(), tenant, agentName, parentSessionID, c.Param("id"))
	if errors.Is(err, store.ErrNotFound) {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "not found"})
		observeDPStore("tasks", "not_found", start)
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("tasks", "error", start)
		return
	}
	c.JSON(http.StatusOK, gin.H{"written": written})
	observeDPStore("tasks", "ok", start)
}

func (s *Server) dpTaskPendingDeliveries(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("tasks", "error", start)
		return
	}
	tenant, ok := dpTenantQuery(c)
	if !ok {
		observeDPStore("tasks", "error", start)
		return
	}
	agentName, ok := parentAgentID(c)
	if !ok {
		observeDPStore("tasks", "error", start)
		return
	}
	parentSessionID := c.Query("parentSessionId")
	if parentSessionID == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "parentSessionId is required"})
		observeDPStore("tasks", "error", start)
		return
	}
	tasks, err := s.store.Tasks().ListPendingDeliveries(c.Request.Context(), tenant, agentName, parentSessionID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("tasks", "error", start)
		return
	}
	if tasks == nil {
		tasks = []*store.DPTask{}
	}
	c.JSON(http.StatusOK, gin.H{"tasks": tasks})
	observeDPStore("tasks", "ok", start)
}

func (s *Server) dpTaskDelete(c *gin.Context) {
	start := time.Now()
	if !s.requireHostedStore(c) {
		observeDPStore("tasks", "error", start)
		return
	}
	tenant, ok := dpTenantQuery(c)
	if !ok {
		observeDPStore("tasks", "error", start)
		return
	}
	agentName, ok := parentAgentID(c)
	if !ok {
		observeDPStore("tasks", "error", start)
		return
	}
	parentSessionID := c.Query("parentSessionId")
	if parentSessionID == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "parentSessionId is required"})
		observeDPStore("tasks", "error", start)
		return
	}
	if err := s.store.Tasks().Delete(c.Request.Context(), tenant, agentName, parentSessionID, c.Param("id")); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "not found"})
			observeDPStore("tasks", "not_found", start)
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		observeDPStore("tasks", "error", start)
		return
	}
	c.Status(http.StatusNoContent)
	observeDPStore("tasks", "ok", start)
}

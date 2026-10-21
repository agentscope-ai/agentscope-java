// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
)

const portalNativeStopPath = "runtime-portal/physical-stops"

type portalNativeStop struct {
	AttemptID  string `json:"attempt_id"`
	Generation int64  `json:"generation"`
	Fence      int64  `json:"fence"`
}

func (s *Server) portalNativeStopped(ctx context.Context, attempt *model.ExecutionAttempt) (bool, error) {
	item, err := s.store.KV().Get(ctx, attempt.Tenant, portalNativeStopPath, attempt.ID.String())
	if errors.Is(err, store.ErrNotFound) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	var receipt portalNativeStop
	if err = json.Unmarshal(item.Value, &receipt); err != nil {
		return false, err
	}
	return receipt.AttemptID == attempt.ID.String() && receipt.Generation == attempt.DispatchGeneration && receipt.Fence == attempt.FencingToken, nil
}

func (s *Server) confirmExecutionPhysicalStop(c *gin.Context) {
	_, id, lease, ok := s.bindExecutionLease(c)
	if !ok {
		return
	}
	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()
	err := s.store.WithSessionLock(ctx, "portal-attempt-stream:"+id.String(), func(ctx context.Context) error {
		attempt, err := s.store.ExecutionAttempts().Get(ctx, id)
		if err != nil {
			s.writeControlPlaneError(c, err)
			return nil
		}
		if attempt.LeaseToken != lease.LeaseToken || attempt.FencingToken != lease.FencingToken || !model.IsExecutionAttemptTerminal(attempt.State) {
			c.Status(http.StatusConflict)
			return nil
		}
		// The authenticated original Host sends this only after its provider has
		// exited and its durable event outbox has drained. Late facts remain
		// acceptable until this matching immutable receipt seals the stream.
		receipt := portalNativeStop{AttemptID: id.String(), Generation: attempt.DispatchGeneration, Fence: attempt.FencingToken}
		raw, _ := json.Marshal(receipt)
		if _, err = s.store.KV().Put(ctx, attempt.Tenant, portalNativeStopPath, id.String(), raw); err != nil {
			s.writeControlPlaneError(c, err)
			return nil
		}
		c.JSON(http.StatusOK, gin.H{"attempt": attempt, "physical_stop": receipt})
		return nil
	})
	if err != nil {
		s.writeControlPlaneError(c, err)
	}
}

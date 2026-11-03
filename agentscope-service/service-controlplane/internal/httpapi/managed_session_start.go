/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package httpapi

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
)

// startManagedSession acknowledges the durable task transition before the data
// plane runs tools. Unlike historical event delivery, a start command must reject
// a replaced or terminal attempt even when its original fence is authentic.
func (s *Server) startManagedSession(c *gin.Context) {
	sessionID := strings.TrimSpace(c.Param("sessionId"))
	var report managedSessionEventReport
	if err := c.ShouldBindJSON(&report); err != nil || sessionID == "" ||
		strings.TrimSpace(report.AgentTaskID) == "" || strings.TrimSpace(report.AttemptID) == "" ||
		report.DispatchGen <= 0 || strings.TrimSpace(report.TurnID) == "" {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: "sessionId, agentTaskId, attemptId, dispatchGeneration, and turnId are required"})
		return
	}
	report.SessionID, report.Type = sessionID, "session.status_running"
	sessions, err := s.store.Sessions().List(c.Request.Context(), store.SessionFilter{SessionID: sessionID, Limit: 2})
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}
	if len(sessions) != 1 {
		c.JSON(http.StatusNotFound, ErrorResponse{Error: "managed task session not found"})
		return
	}
	err = s.store.WithSessionLock(c.Request.Context(), sessions[0].ID.String(), func(ctx context.Context) error {
		session, err := s.store.Sessions().GetByID(ctx, sessions[0].ID)
		if err != nil {
			return err
		}
		historical, err := s.managedReportIsHistorical(ctx, session, &report)
		if err != nil {
			return err
		}
		if historical {
			return errManagedAttemptGone
		}
		return s.applyManagedSessionStatus(ctx, session, &report, time.Now().UTC())
	})
	if err != nil {
		switch {
		case errors.Is(err, errManagedAttemptGone), errors.Is(err, store.ErrNotFound):
			c.JSON(http.StatusGone, ErrorResponse{Error: errManagedAttemptGone.Error()})
		case errors.Is(err, store.ErrConflict):
			c.JSON(http.StatusConflict, ErrorResponse{Error: err.Error()})
		default:
			c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		}
		return
	}
	c.Status(http.StatusNoContent)
}

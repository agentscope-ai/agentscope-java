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
	"encoding/json"
	"errors"
	"fmt"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

var errServiceConcurrency = errors.New("endpoint concurrency limit exceeded")
var errApplicationBudget = errors.New("application token budget exhausted")
var errServiceInput = errors.New("invalid endpoint input")

// Admission serializes an Application across all its endpoints before taking the endpoint lock.
// This lock order makes multi-key/multi-endpoint quotas atomic without scanning invocation history.
func (s *Server) reserveServiceInvocation(ctx context.Context, ep *model.Endpoint, input *model.EndpointInvocation) (*model.EndpointInvocation, bool, error) {
	var result *model.EndpointInvocation
	var fresh bool
	reserve := func(ctx context.Context) error {
		return s.store.WithSessionLock(ctx, "service-admission:"+ep.ID.String(), func(ctx context.Context) error {
			existing, err := s.store.Endpoints().GetInvocationByIdempotency(ctx, ep.ID, input.Mode, input.PrincipalRef, input.IdempotencyKey)
			if err == nil {
				result = existing
				return nil
			}
			if !errors.Is(err, store.ErrNotFound) {
				return err
			}
			var policy endpointRateLimit
			_ = json.Unmarshal(ep.RateLimit, &policy)
			// Replays use the accepted invocation even if a later release changes its schema or quota.
			var envelope map[string]any
			if err = json.Unmarshal(input.Input, &envelope); err != nil {
				return fmt.Errorf("%w: %s", errServiceInput, err)
			}
			var value any = envelope
			if input.Mode == model.EndpointJobMode {
				value = envelope["input"]
			}
			frozen, _ := serviceapi.ReadContract(input.Contract)
			if frozen != nil && frozen.PublicSessionID != uuid.Nil {
				value = envelope["input"]
				if message, ok := envelope["message"]; ok {
					value = message
				}
			}
			if err = validateEndpointInput(ep.InputSchema, value); err != nil {
				return fmt.Errorf("%w: %s", errServiceInput, err)
			}
			if input.ConversationID != nil && (frozen == nil || frozen.PublicSessionID == uuid.Nil) {
				n, err := s.store.Endpoints().CountActiveInvocations(ctx, store.EndpointInvocationFilter{ConversationID: *input.ConversationID})
				if err != nil {
					return err
				}
				if n > 0 {
					return fmt.Errorf("%w: conversation has an active invocation", store.ErrConflict)
				}
			}
			if policy.MaxConcurrent > 0 {
				n, err := s.store.Endpoints().CountActiveInvocations(ctx, store.EndpointInvocationFilter{EndpointID: ep.ID})
				if err != nil {
					return err
				}
				if n >= policy.MaxConcurrent {
					return errServiceConcurrency
				}
			}
			if input.ApplicationID != nil {
				app, err := s.store.Applications().Get(ctx, *input.ApplicationID)
				if err != nil {
					return err
				}
				if app.Status != "active" || app.Tenant != ep.Tenant || app.Namespace != ep.Namespace {
					return fmt.Errorf("%w: application is unavailable", store.ErrConflict)
				}
				if app.TokenBudget > 0 && app.TokensUsed >= app.TokenBudget {
					return errApplicationBudget
				}
				if app.MaxConcurrent > 0 {
					n, err := s.store.Endpoints().CountActiveInvocations(ctx, store.EndpointInvocationFilter{ApplicationID: app.ID})
					if err != nil {
						return err
					}
					if n >= app.MaxConcurrent {
						return fmt.Errorf("%w: application limit", errServiceConcurrency)
					}
				}
			}
			result, fresh, err = s.store.Endpoints().ReserveInvocation(ctx, input)
			return err
		})
	}
	var err error
	if input.ApplicationID != nil {
		err = s.store.WithSessionLock(ctx, "service-application-admission:"+input.ApplicationID.String(), reserve)
	} else {
		err = reserve(ctx)
	}
	return result, fresh, err
}
func (s *Server) writeServiceAdmissionError(c *gin.Context, err error) {
	if errors.Is(err, errServiceInput) {
		c.JSON(400, ErrorResponse{Error: err.Error()})
		return
	}
	if errors.Is(err, errServiceConcurrency) || errors.Is(err, errApplicationBudget) {
		c.Header("Retry-After", "2")
		c.JSON(429, ErrorResponse{Error: err.Error()})
		return
	}
	s.writeControlPlaneError(c, err)
}
func (s *Server) enforceServiceBudget(ctx context.Context, inv *model.EndpointInvocation, ep *model.Endpoint) error {
	var policy endpointRateLimit
	_ = json.Unmarshal(ep.RateLimit, &policy)
	snapshot, err := serviceJournal(s.store, ep, inv).Snapshot(ctx)
	if err != nil {
		return err
	}
	// An execution summary supersedes per-model records to avoid counting twice.
	totals := map[string]float64{}
	models := map[string]float64{}
	for key, record := range snapshot.Usage {
		raw, _ := json.Marshal(record["usage"])
		var usage map[string]any
		_ = json.Unmarshal(raw, &usage)
		number := func(names ...string) float64 {
			for _, name := range names {
				if v, ok := usage[name].(float64); ok {
					return v
				}
			}
			return 0
		}
		count := number("total_tokens", "totalTokens")
		if count == 0 {
			count = number("input_tokens", "inputTokens", "prompt_tokens") + number("output_tokens", "outputTokens", "completion_tokens")
		}
		execution, _ := record["execution_id"].(string)
		if key == execution+":total" {
			totals[execution] = count
		} else {
			models[execution] += count
		}
	}
	total := float64(0)
	for id, n := range models {
		if _, ok := totals[id]; !ok {
			total += n
		}
	}
	for _, n := range totals {
		total += n
	}
	if err = s.store.Endpoints().RecordInvocationTokens(ctx, inv.ID, int64(total)); err != nil {
		return err
	}
	inv.ConsumedTokens = max(inv.ConsumedTokens, int64(total))
	if serviceapi.Terminal(inv.Status) || inv.Status == model.EndpointInvocationCancelRequested {
		return nil
	}
	kind, limit, observed := "invocation_tokens", int64(policy.MaxInvocationTokens), inv.ConsumedTokens
	if inv.ApplicationID != nil {
		app, err := s.store.Applications().Get(ctx, *inv.ApplicationID)
		if err != nil {
			return err
		}
		if app.TokenBudget > 0 && app.TokensUsed >= app.TokenBudget {
			kind, limit, observed = "application_tokens", app.TokenBudget, app.TokensUsed
		}
	}
	if limit <= 0 || observed < limit {
		return nil
	}
	_, err = serviceJournal(s.store, ep, inv).Append(ctx, "budget:"+kind, "budget.exceeded", map[string]any{"kind": kind, "limit": limit, "observed": observed})
	if err != nil {
		return err
	}
	return s.cancelServiceInvocation(ctx, inv.ID, false)
}

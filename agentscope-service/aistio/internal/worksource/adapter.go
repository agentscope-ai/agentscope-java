// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

// Package worksource owns the anti-corruption boundary between external Work
// systems and AgentScope's Issue projection.
package worksource

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type Event struct {
	DeliveryID string
	EventType  string
	Payload    []byte
}

type IssueCommand struct {
	IssueID uuid.UUID
	Action  string
	Payload json.RawMessage
}

type PublishedComment struct {
	ExternalID string
}

// Adapter is the v5 SPI. Implementations preserve external authority over Work
// fields while AgentScope owns execution, approvals, artifacts and audit facts.
type Adapter interface {
	HandleEvent(context.Context, *controlmodel.WorkSource, Event) error
	FetchWork(context.Context, *controlmodel.WorkSource, string) (*controlmodel.IssueExternalRef, error)
	ApplyIssueCommand(context.Context, *controlmodel.WorkSource, IssueCommand) error
	PublishComment(context.Context, *controlmodel.WorkSource, *controlmodel.Comment) (*PublishedComment, error)
	Reconcile(context.Context, *controlmodel.WorkSource) error
}

type Registry struct{ adapters map[string]Adapter }

func NewRegistry() *Registry                              { return &Registry{adapters: map[string]Adapter{}} }
func (r *Registry) Register(kind string, adapter Adapter) { r.adapters[kind] = adapter }
func (r *Registry) Resolve(kind string) (Adapter, bool)   { a, ok := r.adapters[kind]; return a, ok }

type Service struct {
	Store    store.Store
	Adapters *Registry
}

func VerifyGitHubSignature(secret string, body []byte, signature string) bool {
	if secret == "" || len(signature) < len("sha256=") || signature[:len("sha256=")] != "sha256=" {
		return false
	}
	want, err := hex.DecodeString(signature[len("sha256="):])
	if err != nil {
		return false
	}
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write(body)
	return hmac.Equal(want, mac.Sum(nil))
}

// HandleEvent provides delivery-ID dedupe around every adapter invocation.
func (s *Service) HandleEvent(ctx context.Context, source *controlmodel.WorkSource, event Event) error {
	if source == nil || !source.Enabled {
		return fmt.Errorf("work source is disabled")
	}
	adapter, ok := s.Adapters.Resolve(source.Kind)
	if !ok {
		return fmt.Errorf("unsupported work source kind %q", source.Kind)
	}
	h := sha256.Sum256(event.Payload)
	delivery, fresh, err := s.Store.WorkSources().BeginWebhookDelivery(ctx, source.ID, event.DeliveryID, hex.EncodeToString(h[:]))
	if err != nil || !fresh {
		return err
	}
	if err = adapter.HandleEvent(ctx, source, event); err != nil {
		_ = s.Store.WorkSources().FailWebhookDelivery(ctx, delivery.ID, err.Error())
		return err
	}
	return s.Store.WorkSources().CompleteWebhookDelivery(ctx, delivery.ID)
}

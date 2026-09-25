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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package worksource

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"testing"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

type countingAdapter struct{ calls int }

func (a *countingAdapter) HandleEvent(context.Context, *controlmodel.WorkSource, Event) error {
	a.calls++
	return nil
}
func (*countingAdapter) FetchWork(context.Context, *controlmodel.WorkSource, string) (*controlmodel.IssueExternalRef, error) {
	return nil, nil
}
func (*countingAdapter) ApplyIssueCommand(context.Context, *controlmodel.WorkSource, IssueCommand) error {
	return nil
}
func (*countingAdapter) PublishComment(context.Context, *controlmodel.WorkSource, *controlmodel.Comment) (*PublishedComment, error) {
	return nil, nil
}
func (*countingAdapter) Reconcile(context.Context, *controlmodel.WorkSource) error { return nil }

type retryingAdapter struct{ calls int }

func (a *retryingAdapter) HandleEvent(context.Context, *controlmodel.WorkSource, Event) error {
	a.calls++
	if a.calls == 1 {
		return errors.New("temporary transport error")
	}
	return nil
}
func (*retryingAdapter) FetchWork(context.Context, *controlmodel.WorkSource, string) (*controlmodel.IssueExternalRef, error) {
	return nil, nil
}
func (*retryingAdapter) ApplyIssueCommand(context.Context, *controlmodel.WorkSource, IssueCommand) error {
	return nil
}
func (*retryingAdapter) PublishComment(context.Context, *controlmodel.WorkSource, *controlmodel.Comment) (*PublishedComment, error) {
	return nil, nil
}
func (*retryingAdapter) Reconcile(context.Context, *controlmodel.WorkSource) error { return nil }

func TestWebhookSignatureAndDeliveryDedupe(t *testing.T) {
	body := []byte(`{"action":"opened"}`)
	mac := hmac.New(sha256.New, []byte("secret"))
	_, _ = mac.Write(body)
	signature := "sha256=" + hex.EncodeToString(mac.Sum(nil))
	if !VerifyGitHubSignature("secret", body, signature) || VerifyGitHubSignature("wrong", body, signature) {
		t.Fatal("signature verification failed closed contract")
	}
	st, err := store.Open(context.Background(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	source, err := st.WorkSources().CreateWorkSource(context.Background(), &controlmodel.WorkSource{Tenant: "t", Namespace: "n", Kind: "github", Name: "repo", Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	adapter := &countingAdapter{}
	registry := NewRegistry()
	registry.Register("github", adapter)
	service := &Service{Store: st, Adapters: registry}
	event := Event{DeliveryID: "delivery-1", EventType: "issues", Payload: body}
	if err = service.HandleEvent(context.Background(), source, event); err != nil {
		t.Fatal(err)
	}
	if err = service.HandleEvent(context.Background(), source, event); err != nil {
		t.Fatal(err)
	}
	if adapter.calls != 1 {
		t.Fatalf("duplicate delivery invoked adapter %d times", adapter.calls)
	}
}

func TestFailedWebhookDeliveryCanRetry(t *testing.T) {
	st, err := store.Open(context.Background(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	source, err := st.WorkSources().CreateWorkSource(context.Background(), &controlmodel.WorkSource{Tenant: "t", Namespace: "n", Kind: "github", Name: "retry", Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	adapter := &retryingAdapter{}
	registry := NewRegistry()
	registry.Register("github", adapter)
	service := &Service{Store: st, Adapters: registry}
	event := Event{DeliveryID: "delivery-retry", EventType: "issues", Payload: []byte(`{"action":"opened"}`)}
	if err = service.HandleEvent(context.Background(), source, event); err == nil {
		t.Fatal("first delivery should fail")
	}
	if err = service.HandleEvent(context.Background(), source, event); err != nil {
		t.Fatalf("failed delivery was not retryable: %v", err)
	}
	if adapter.calls != 2 {
		t.Fatalf("adapter calls=%d, want 2", adapter.calls)
	}
}

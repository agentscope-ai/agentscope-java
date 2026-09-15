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
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	authzv1 "k8s.io/api/authorization/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/kubernetes/fake"
	ktesting "k8s.io/client-go/testing"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestAuthorizationUsesPersistedNamespaceForUUIDResource(t *testing.T) {
	gin.SetMode(gin.TestMode)
	st, err := store.Open(context.Background(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	issue, err := st.Collaboration().CreateIssue(context.Background(), &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "ns-a", Title: "private", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "alice"},
	})
	if err != nil {
		t.Fatal(err)
	}
	client := fake.NewSimpleClientset()
	seenNamespace := ""
	client.Fake.PrependReactor("create", "subjectaccessreviews", func(action ktesting.Action) (bool, runtime.Object, error) {
		review := action.(ktesting.CreateAction).GetObject().(*authzv1.SubjectAccessReview)
		seenNamespace = review.Spec.ResourceAttributes.Namespace
		return true, &authzv1.SubjectAccessReview{ObjectMeta: metav1.ObjectMeta{}, Status: authzv1.SubjectAccessReviewStatus{Allowed: seenNamespace == "ns-a"}}, nil
	})
	s := NewServer(ServerOptions{Store: st, KubeClient: client})
	router := gin.New()
	router.GET("/api/v1/issues/:issueId", func(c *gin.Context) { c.Set("username", "alice"); c.Next() }, s.authzMiddleware(), func(c *gin.Context) { c.Status(http.StatusNoContent) })

	w := httptest.NewRecorder()
	router.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/v1/issues/"+issue.ID.String(), nil))
	if w.Code != http.StatusNoContent || seenNamespace != "ns-a" {
		t.Fatalf("expected stored namespace authorization, status=%d namespace=%q body=%s", w.Code, seenNamespace, w.Body.String())
	}

	w = httptest.NewRecorder()
	router.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/v1/issues/"+issue.ID.String()+"?namespace=ns-b", nil))
	if w.Code != http.StatusNotFound {
		t.Fatalf("expected mismatched scope to fail closed, got %d: %s", w.Code, w.Body.String())
	}

	w = httptest.NewRecorder()
	router.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/v1/issues/"+issue.ID.String()+"?tenant=tenant-b", nil))
	if w.Code != http.StatusNotFound {
		t.Fatalf("expected mismatched tenant to fail closed, got %d: %s", w.Code, w.Body.String())
	}
}

func TestHumanInboxRejectsInternalPrincipal(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.GET("/inbox", func(c *gin.Context) {
		c.Set(ctxInternalAuth, true)
		if requireHumanPrincipal(c) {
			c.Status(http.StatusNoContent)
		}
	})
	w := httptest.NewRecorder()
	router.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/inbox", nil))
	if w.Code != http.StatusForbidden {
		t.Fatalf("expected internal principal to be rejected, got %d: %s", w.Code, w.Body.String())
	}
}

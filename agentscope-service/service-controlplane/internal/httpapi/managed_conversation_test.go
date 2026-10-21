// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package httpapi

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/product"
	"github.com/gin-gonic/gin"
)

func TestManagedConversationPreservesNativeAdmissionErrors(t *testing.T) {
	for _, status := range []int{http.StatusConflict, http.StatusForbidden, http.StatusTooManyRequests, http.StatusServiceUnavailable} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			out := httptest.NewRecorder()
			ctx, _ := gin.CreateTestContext(out)
			err := fmt.Errorf("native submit: %w", &product.ManagedServiceError{StatusCode: status, Message: "rejected"})
			writeConversationTurnError(ctx, err)
			if out.Code != status {
				t.Fatalf("admission status became retryable/unrelated: %d %s", out.Code, out.Body)
			}
		})
	}
}

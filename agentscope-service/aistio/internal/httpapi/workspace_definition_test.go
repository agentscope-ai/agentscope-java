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
package httpapi

import (
	"encoding/json"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestWorkspaceApplicationRequiresMatchingFrozenDigest(t *testing.T) {
	attempt := uuid.New()
	for _, test := range []struct {
		name, body, snapshot string
		attempt              *uuid.UUID
		code                 int
	}{
		{"empty digest", `{}`, `{}`, &attempt, 400},
		{"no definition", `{"digest":"x"}`, `{}`, &attempt, 409},
		{"wrong digest", `{"digest":"x"}`, `{"definition":{"definitionDigest":"y"}}`, &attempt, 409},
		{"no active attempt", `{"digest":"x"}`, `{"definition":{"definitionDigest":"x"}}`, nil, 409},
	} {
		t.Run(test.name, func(t *testing.T) {
			router := gin.New()
			router.POST("/application", func(c *gin.Context) {
				c.Set(ctxTaskAuth, &controlmodel.AgentTask{CurrentAttemptID: test.attempt, RuntimeBinding: json.RawMessage(test.snapshot)})
				(&Server{}).reportWorkspaceApplication(c)
			})
			req := httptest.NewRequest("POST", "/application", strings.NewReader(test.body))
			req.Header.Set("Content-Type", "application/json")
			response := httptest.NewRecorder()
			router.ServeHTTP(response, req)
			if response.Code != test.code {
				t.Fatalf("got %d: %s", response.Code, response.Body.String())
			}
		})
	}
}

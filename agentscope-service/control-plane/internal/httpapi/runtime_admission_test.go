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
	"net/http/httptest"
	"strings"
	"testing"

	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

func TestRuntimeConfigurationAdmissionRequiresServiceAndOriginalCaller(t *testing.T) {
	st, err := store.Open(t.Context(), store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	server := NewServer(ServerOptions{Store: st, InternalToken: "admission-internal",
		DefaultTenant: "t", DefaultNamespace: "n"})
	for _, token := range []string{"", "wrong", "admission-internal"} {
		request := httptest.NewRequest("POST", "/api/internal/runtime-admissions/sessions",
			strings.NewReader("{}"))
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("X-Managed-Agents-Internal-Token", token)
		response := httptest.NewRecorder()
		server.router.ServeHTTP(response, request)
		if response.Code != 401 {
			t.Fatalf("configuration admitted without original caller: token=%q status=%d", token, response.Code)
		}
	}
}

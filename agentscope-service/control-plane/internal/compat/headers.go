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

// Package compat contains wire compatibility with external framework binaries.
package compat

import "net/http"

const InternalTokenHeader = "X-Managed-Agents-Internal-Token"

// LegacyInternalTokenHeader is fixed in the AgentScope Java binary SDK.
const LegacyInternalTokenHeader = "X-Builder-Internal-Token"

// InternalToken prefers the platform header when both names are supplied.
func InternalToken(headers http.Header) string {
	if value := headers.Get(InternalTokenHeader); value != "" {
		return value
	}
	return headers.Get(LegacyInternalTokenHeader)
}

// SetRuntimeToken also supports runtimes using the unmodified Java SDK.
func SetRuntimeToken(headers http.Header, token string) {
	headers.Set(InternalTokenHeader, token)
	headers.Set(LegacyInternalTokenHeader, token)
}

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

package compat

import (
	"net/http"
	"testing"
)

func TestInternalTokenSupportsBinarySDKWithoutOverridingPlatformCredential(t *testing.T) {
	for _, test := range []struct {
		name, platform, legacy, want string
	}{
		{name: "missing"},
		{name: "platform", platform: "new", want: "new"},
		{name: "java binary", legacy: "old", want: "old"},
		{name: "platform wins", platform: "new", legacy: "old", want: "new"},
	} {
		t.Run(test.name, func(t *testing.T) {
			headers := make(http.Header)
			headers.Set(InternalTokenHeader, test.platform)
			headers.Set(LegacyInternalTokenHeader, test.legacy)
			if got := InternalToken(headers); got != test.want {
				t.Fatalf("token=%q, want %q", got, test.want)
			}
		})
	}
}

func TestRuntimeTokenSupportsUnmodifiedJavaContractServer(t *testing.T) {
	headers := make(http.Header)
	SetRuntimeToken(headers, "runtime-token")
	for _, name := range []string{"X-Managed-Agents-Internal-Token", "X-Builder-Internal-Token"} {
		if headers.Get(name) != "runtime-token" {
			t.Fatalf("runtime credential missing for %s", name)
		}
	}
}

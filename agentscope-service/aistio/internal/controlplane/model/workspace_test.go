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
package model

import (
	"encoding/json"
	"testing"
)

func TestWorkspaceConsumerRequiresExplicitCapability(t *testing.T) {
	for raw, want := range map[string]bool{`["workspace-definition-v1"]`: true, `{"workspace-definition-v1":true}`: true, `["no-workspace-definition-v1"]`: false, `{"workspace-definition-v1":false}`: false, `{}`: false, `null`: false} {
		if got := ConsumesWorkspaceDefinition(json.RawMessage(raw)); got != want {
			t.Errorf("%s: %v", raw, got)
		}
	}
}

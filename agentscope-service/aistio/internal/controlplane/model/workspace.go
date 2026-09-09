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

import "encoding/json"

// ConsumesWorkspaceDefinition accepts the SDK registration capability list and registry capability maps.
func ConsumesWorkspaceDefinition(raw json.RawMessage) bool {
	var names []string
	if json.Unmarshal(raw, &names) == nil {
		for _, name := range names {
			if name == "workspace-definition-v1" {
				return true
			}
		}
	}
	var flags map[string]any
	return json.Unmarshal(raw, &flags) == nil && flags["workspace-definition-v1"] == true
}

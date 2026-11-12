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
	"bytes"
	"encoding/json"
	"fmt"
)

// Validate the fields the acceptance evaluator consumes before persisting an
// edit. Unknown fields remain in the original JSON for runtime extensions.
func validateIssueCriteria(raw json.RawMessage) error {
	raw = bytes.TrimSpace(raw)
	if len(raw) == 0 || bytes.Equal(raw, []byte("null")) || bytes.Equal(raw, []byte("[]")) {
		return nil
	}
	var criteria struct {
		RequiredResult   bool `json:"requiredResult"`
		MinimumArtifacts int  `json:"minimumArtifacts"`
		MinimumApprovals int  `json:"minimumApprovals"`
		Checklist        []*struct {
			ID        string `json:"id"`
			Text      string `json:"text"`
			Required  *bool  `json:"required"`
			Satisfied bool   `json:"satisfied"`
		} `json:"checklist"`
	}
	if err := json.Unmarshal(raw, &criteria); err != nil {
		return fmt.Errorf("invalid acceptance criteria: %w", err)
	}
	if criteria.MinimumArtifacts < 0 || criteria.MinimumApprovals < 0 {
		return fmt.Errorf("acceptance criteria counts cannot be negative")
	}
	for _, item := range criteria.Checklist {
		if item == nil {
			return fmt.Errorf("acceptance checklist items must be objects")
		}
	}
	return nil
}

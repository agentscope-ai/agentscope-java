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

package store

import (
	"bytes"
	"encoding/json"
	"strconv"
)

// AttemptUsage returns the explicit backend usage, or the usage object embedded
// in a result document. Keeping this normalization in the store contract makes
// Managed, External Application, and Hosted completion accounting identical.
func AttemptUsage(explicit, result json.RawMessage) json.RawMessage {
	if validJSONObject(explicit) {
		return append(json.RawMessage(nil), explicit...)
	}
	var document map[string]json.RawMessage
	if len(result) == 0 || json.Unmarshal(result, &document) != nil {
		return nil
	}
	usage := document["usage"]
	if !validJSONObject(usage) {
		return nil
	}
	return append(json.RawMessage(nil), usage...)
}

// MergeUsage recursively adds numeric counters while retaining non-numeric
// dimensions. It is deliberately tolerant of provider-specific usage fields.
func MergeUsage(current, incoming json.RawMessage) json.RawMessage {
	if !validJSONObject(incoming) {
		return append(json.RawMessage(nil), current...)
	}
	left, right := decodeUsage(current), decodeUsage(incoming)
	merged := mergeUsageValue(left, right)
	out, err := json.Marshal(merged)
	if err != nil {
		return append(json.RawMessage(nil), current...)
	}
	return out
}

func validJSONObject(raw json.RawMessage) bool {
	if len(raw) == 0 || !json.Valid(raw) {
		return false
	}
	var value map[string]any
	return json.Unmarshal(raw, &value) == nil && value != nil
}

func decodeUsage(raw json.RawMessage) map[string]any {
	if !validJSONObject(raw) {
		return map[string]any{}
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	var value map[string]any
	if decoder.Decode(&value) != nil || value == nil {
		return map[string]any{}
	}
	return value
}

func mergeUsageValue(left, right map[string]any) map[string]any {
	out := make(map[string]any, len(left)+len(right))
	for key, value := range left {
		out[key] = value
	}
	for key, value := range right {
		previous, exists := out[key]
		if !exists {
			out[key] = value
			continue
		}
		switch next := value.(type) {
		case json.Number:
			if prior, ok := previous.(json.Number); ok {
				out[key] = addUsageNumbers(prior, next)
			} else {
				out[key] = value
			}
		case map[string]any:
			if prior, ok := previous.(map[string]any); ok {
				out[key] = mergeUsageValue(prior, next)
			} else {
				out[key] = value
			}
		default:
			// Latest backend metadata wins; counters are the only additive values.
			out[key] = value
		}
	}
	return out
}

func addUsageNumbers(left, right json.Number) json.Number {
	leftInt, leftErr := strconv.ParseInt(left.String(), 10, 64)
	rightInt, rightErr := strconv.ParseInt(right.String(), 10, 64)
	if leftErr == nil && rightErr == nil {
		return json.Number(strconv.FormatInt(leftInt+rightInt, 10))
	}
	leftFloat, _ := strconv.ParseFloat(left.String(), 64)
	rightFloat, _ := strconv.ParseFloat(right.String(), 64)
	return json.Number(strconv.FormatFloat(leftFloat+rightFloat, 'f', -1, 64))
}

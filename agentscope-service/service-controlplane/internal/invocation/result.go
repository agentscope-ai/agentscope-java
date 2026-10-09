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

package invocation

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
)

// ResultMapping projects business fields from a completed result using RFC 6901
// JSON pointers. A missing path is an error rather than a silently omitted field.
func CheckResultMapping(raw json.RawMessage) error {
	_, err := resultMapping(raw)
	return err
}
func resultMapping(raw json.RawMessage) (map[string]string, error) {
	if len(raw) == 0 || string(raw) == "null" {
		return nil, nil
	}
	var mapping map[string]string
	if err := json.Unmarshal(raw, &mapping); err != nil {
		return nil, fmt.Errorf("resultMapping must map field names to JSON pointers: %w", err)
	}
	if len(mapping) > 256 {
		return nil, fmt.Errorf("resultMapping exceeds 256 fields")
	}
	for field, pointer := range mapping {
		if strings.TrimSpace(field) == "" {
			return nil, fmt.Errorf("resultMapping field must not be empty")
		}
		if _, err := pointerTokens(pointer); err != nil {
			return nil, fmt.Errorf("resultMapping field %q: %w", field, err)
		}
	}
	return mapping, nil
}
func pointerTokens(pointer string) ([]string, error) {
	if pointer == "" {
		return nil, nil
	}
	if !strings.HasPrefix(pointer, "/") {
		return nil, fmt.Errorf("JSON pointer must start with / or be empty")
	}
	parts := strings.Split(pointer[1:], "/")
	if len(parts) > 128 {
		return nil, fmt.Errorf("JSON pointer exceeds 128 segments")
	}
	for i, part := range parts {
		var decoded strings.Builder
		for j := 0; j < len(part); j++ {
			if part[j] != '~' {
				decoded.WriteByte(part[j])
				continue
			}
			j++
			if j >= len(part) || (part[j] != '0' && part[j] != '1') {
				return nil, fmt.Errorf("invalid JSON pointer escape")
			}
			if part[j] == '0' {
				decoded.WriteByte('~')
			} else {
				decoded.WriteByte('/')
			}
		}
		parts[i] = decoded.String()
	}
	return parts, nil
}
func MapResult(ep *model.Endpoint, raw json.RawMessage) (json.RawMessage, error) {
	mapping, err := resultMapping(ep.ResultMapping)
	if err != nil {
		return nil, err
	}
	if len(raw) == 0 {
		raw = json.RawMessage("null")
	}
	if len(mapping) == 0 {
		return raw, nil
	}
	var input any
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	if err = decoder.Decode(&input); err != nil {
		return nil, err
	}
	output := make(map[string]any, len(mapping))
	for field, pointer := range mapping {
		parts, _ := pointerTokens(pointer)
		value := input
		for _, part := range parts {
			switch current := value.(type) {
			case map[string]any:
				var ok bool
				value, ok = current[part]
				if !ok {
					return nil, fmt.Errorf("result field %q: missing JSON pointer %q", field, pointer)
				}
			case []any:
				index, e := strconv.Atoi(part)
				if e != nil || index < 0 || index >= len(current) || strconv.Itoa(index) != part {
					return nil, fmt.Errorf("result field %q: invalid array index in %q", field, pointer)
				}
				value = current[index]
			default:
				return nil, fmt.Errorf("result field %q: cannot resolve JSON pointer %q", field, pointer)
			}
		}
		output[field] = value
	}
	return json.Marshal(output)
}

// CompleteResult applies the published projection before validating the public schema.
func CompleteResult(ep *model.Endpoint, inv *model.EndpointInvocation, raw json.RawMessage) {
	mapped, err := MapResult(ep, raw)
	if err != nil {
		inv.Status = model.EndpointInvocationFailed
		inv.ErrorCode = "output_mapping_failed"
		inv.ErrorMessage = err.Error()
		inv.Result = nil
		return
	}
	inv.Result = mapped
	if err = ValidateResult(ep, mapped); err != nil {
		inv.Status = model.EndpointInvocationFailed
		inv.ErrorCode = "output_schema_violation"
		inv.ErrorMessage = err.Error()
	}
}

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package collaboration

import (
	"bytes"
	"encoding/json"
	"strings"
)

// EndpointIssueDescription keeps arbitrary JSON input visible without losing
// structured fields or numeric precision. It also repairs context for older
// jobs whose Issue only stored the endpoint's placeholder title.
func EndpointIssueDescription(description string, input json.RawMessage) string {
	raw := bytes.TrimSpace(input)
	if len(raw) == 0 || bytes.Equal(raw, []byte("null")) {
		return description
	}
	var text string
	if json.Unmarshal(raw, &text) != nil {
		var value any
		decoder := json.NewDecoder(bytes.NewReader(raw))
		decoder.UseNumber()
		var formatted bytes.Buffer
		encoder := json.NewEncoder(&formatted)
		encoder.SetEscapeHTML(false)
		encoder.SetIndent("", "  ")
		if decoder.Decode(&value) == nil && encoder.Encode(value) == nil {
			text = "```json\n" + strings.TrimSuffix(formatted.String(), "\n") + "\n```"
		} else {
			text = string(raw)
		}
	}
	if strings.TrimSpace(text) == "" || strings.Contains(description, text) {
		return description
	}
	if strings.TrimSpace(description) == "" {
		return "请求输入：\n\n" + text
	}
	return description + "\n\n请求输入：\n\n" + text
}

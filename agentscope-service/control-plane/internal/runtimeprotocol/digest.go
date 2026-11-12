// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package runtimeprotocol

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"sort"
	"unicode/utf16"
)

// Digest matches the Agent Service's sorted-map JSON digest. UseNumber preserves the
// number representation supplied by the sender; structs first become JSON maps.
func Digest(value any) (string, error) {
	var initial bytes.Buffer
	encoder := json.NewEncoder(&initial)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(value); err != nil {
		return "", err
	}
	var decoded any
	decoder := json.NewDecoder(&initial)
	decoder.UseNumber()
	if err := decoder.Decode(&decoded); err != nil {
		return "", err
	}
	var canonical bytes.Buffer
	if err := writeCanonical(&canonical, decoded); err != nil {
		return "", err
	}
	digest := sha256.Sum256(canonical.Bytes())
	return hex.EncodeToString(digest[:]), nil
}

func writeCanonical(out *bytes.Buffer, value any) error {
	switch v := value.(type) {
	case map[string]any:
		keys := make([]string, 0, len(v))
		for key := range v {
			keys = append(keys, key)
		}
		// Java String ordering compares UTF-16 code units, including astral keys.
		sort.Slice(keys, func(i, j int) bool {
			a, b := utf16.Encode([]rune(keys[i])), utf16.Encode([]rune(keys[j]))
			for k := 0; k < len(a) && k < len(b); k++ {
				if a[k] != b[k] {
					return a[k] < b[k]
				}
			}
			return len(a) < len(b)
		})
		out.WriteByte('{')
		for index, key := range keys {
			if index > 0 {
				out.WriteByte(',')
			}
			if err := writeCanonical(out, key); err != nil {
				return err
			}
			out.WriteByte(':')
			if err := writeCanonical(out, v[key]); err != nil {
				return err
			}
		}
		out.WriteByte('}')
	case []any:
		out.WriteByte('[')
		for index, item := range v {
			if index > 0 {
				out.WriteByte(',')
			}
			if err := writeCanonical(out, item); err != nil {
				return err
			}
		}
		out.WriteByte(']')
	case string:
		out.WriteByte('"')
		for _, ch := range v {
			switch ch {
			case '"', '\\':
				out.WriteByte('\\')
				out.WriteRune(ch)
			case '\b':
				out.WriteString(`\b`)
			case '\f':
				out.WriteString(`\f`)
			case '\n':
				out.WriteString(`\n`)
			case '\r':
				out.WriteString(`\r`)
			case '\t':
				out.WriteString(`\t`)
			default:
				if ch < 0x20 {
					fmt.Fprintf(out, `\u%04X`, ch)
				} else if ch > 0xFFFF {
					code := uint32(ch) - 0x10000
					fmt.Fprintf(out, `\u%04X\u%04X`, 0xD800+(code>>10), 0xDC00+(code&0x3FF))
				} else {
					out.WriteRune(ch)
				}
			}
		}
		out.WriteByte('"')
	case json.Number:
		out.WriteString(v.String())
	case nil:
		out.WriteString("null")
	case bool:
		if v {
			out.WriteString("true")
		} else {
			out.WriteString("false")
		}
	default:
		return fmt.Errorf("unsupported canonical JSON value: %T", value)
	}
	return nil
}

func BootstrapDigest(request RuntimeSessionBootstrap) (string, error) {
	c := request.Context
	return Digest(map[string]any{"tenant": c.Tenant, "namespace": c.Namespace, "applicationId": c.ApplicationID,
		"sessionId": c.SessionID, "actor": c.Actor, "runtimeKind": request.RuntimeKind,
		"configurationRevision": request.ConfigurationRevision, "configuration": request.Configuration})
}

func TurnDigest(command TurnCommand) (string, error) {
	c := command.Context
	return Digest(map[string]any{"tenant": c.Tenant, "namespace": c.Namespace, "applicationId": c.ApplicationID,
		"sessionId": c.SessionID, "turnId": c.TurnID, "actor": c.Actor, "idempotencyKey": command.IdempotencyKey,
		"inputDigest": command.InputDigest, "admissionSequence": command.AdmissionSequence})
}

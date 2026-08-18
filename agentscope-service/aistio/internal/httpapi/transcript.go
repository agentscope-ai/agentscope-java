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

package httpapi

import (
	"bufio"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/spring-ai-alibaba/aistio/internal/prober"
)

// TranscriptMessagesFunc reads Level-3 messages from a CP-side transcript
// source. ok=false means "not available / miss" — callers fall back to live DP.
// When fromEnd is true and offset is 0, the page starts at max(0, total-limit)
// so clients can open a long session on the newest messages.
type TranscriptMessagesFunc func(ctx context.Context, agentName, namespace, sessionID string, offset, limit int, fromEnd bool) (page *prober.MessagePage, ok bool, err error)

// FilesystemTranscriptMessages reads JSONL segments from a shared filesystem
// (NAS) layout: {root}/{tenant}/{agent}/{session}/events/*.jsonl
// Tenant defaults to namespace when present, else "default".
// Enable via ServerOptions.TranscriptMessages or AISTIO_TRANSCRIPT_FS_ROOT.
func FilesystemTranscriptMessages(root string) TranscriptMessagesFunc {
	root = strings.TrimSpace(root)
	if root == "" {
		return nil
	}
	return func(ctx context.Context, agentName, namespace, sessionID string, offset, limit int, fromEnd bool) (*prober.MessagePage, bool, error) {
		tenant := namespace
		if tenant == "" {
			tenant = "default"
		}
		dir := filepath.Join(root, tenant, agentName, sessionID, "events")
		entries, err := os.ReadDir(dir)
		if err != nil {
			if os.IsNotExist(err) {
				return nil, false, nil
			}
			return nil, false, err
		}
		var files []string
		for _, e := range entries {
			if e.IsDir() || !strings.HasSuffix(e.Name(), ".jsonl") {
				continue
			}
			files = append(files, filepath.Join(dir, e.Name()))
		}
		if len(files) == 0 {
			return nil, false, nil
		}
		sort.Strings(files)

		var items []prober.MessageItem
		seq := int32(0)
		for _, path := range files {
			select {
			case <-ctx.Done():
				return nil, false, ctx.Err()
			default:
			}
			f, err := os.Open(path)
			if err != nil {
				return nil, false, err
			}
			sc := bufio.NewScanner(f)
			sc.Buffer(make([]byte, 0, 64*1024), 4*1024*1024)
			for sc.Scan() {
				line := strings.TrimSpace(sc.Text())
				if line == "" {
					continue
				}
				item, ok := transcriptLineToMessage(line, seq)
				if !ok {
					continue
				}
				seq++
				item.Seq = seq
				items = append(items, item)
			}
			cerr := f.Close()
			if err := sc.Err(); err != nil {
				return nil, false, err
			}
			if cerr != nil {
				return nil, false, cerr
			}
		}
		if len(items) == 0 {
			return nil, false, nil
		}
		total := len(items)
		if offset < 0 {
			offset = 0
		}
		if limit <= 0 {
			limit = 100
		}
		if fromEnd && offset == 0 && total > limit {
			offset = total - limit
		}
		if offset >= total {
			return &prober.MessagePage{
				SessionID: sessionID,
				Offset:    offset,
				Limit:     limit,
				Total:     total,
				Messages:  []prober.MessageItem{},
				Source:    "transcript",
			}, true, nil
		}
		end := offset + limit
		if end > total {
			end = total
		}
		page := &prober.MessagePage{
			SessionID: sessionID,
			Offset:    offset,
			Limit:     limit,
			Total:     total,
			Messages:  items[offset:end],
			Source:    "transcript",
		}
		return page, true, nil
	}
}

func transcriptLineToMessage(line string, fallbackSeq int32) (prober.MessageItem, bool) {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal([]byte(line), &raw); err != nil {
		return prober.MessageItem{}, false
	}
	var typ string
	_ = json.Unmarshal(raw["type"], &typ)
	item := prober.MessageItem{Seq: fallbackSeq + 1}

	switch typ {
	case "message":
		_ = json.Unmarshal(raw["role"], &item.Role)
		_ = json.Unmarshal(raw["content"], &item.Content)
		decodeTranscriptMeta(raw, &item)
		return item, true
	case "tool_use":
		item.Role = "assistant"
		_ = json.Unmarshal(raw["name"], &item.ToolName)
		_ = json.Unmarshal(raw["toolCallId"], &item.ToolCallID)
		if in, ok := raw["input"]; ok {
			item.ToolInput = in
			item.Content = compactJSONPreview(in)
		}
		decodeTranscriptMeta(raw, &item)
		return item, true
	case "tool_result":
		item.Role = "tool"
		_ = json.Unmarshal(raw["name"], &item.ToolName)
		_ = json.Unmarshal(raw["toolCallId"], &item.ToolCallID)
		if out, ok := raw["output"]; ok {
			var s string
			if json.Unmarshal(out, &s) == nil {
				item.ToolOutput = s
			} else {
				item.ToolOutput = string(out)
			}
			item.Content = item.ToolOutput
		}
		decodeTranscriptMeta(raw, &item)
		return item, true
	default:
		return prober.MessageItem{}, false
	}
}

func decodeTranscriptMeta(raw map[string]json.RawMessage, item *prober.MessageItem) {
	if ts, ok := raw["timestamp"]; ok {
		var s string
		if json.Unmarshal(ts, &s) == nil {
			item.OccurredAt = s
		}
	}
	if t, ok := raw["truncated"]; ok {
		var b bool
		if json.Unmarshal(t, &b) == nil {
			item.Truncated = b
		}
	}
	if sz, ok := raw["originalSize"]; ok {
		var n int
		if json.Unmarshal(sz, &n) == nil {
			item.OriginalSize = n
		}
	}
}

func compactJSONPreview(raw json.RawMessage) string {
	if len(raw) == 0 {
		return ""
	}
	var v any
	if json.Unmarshal(raw, &v) != nil {
		return string(raw)
	}
	b, err := json.Marshal(v)
	if err != nil {
		return string(raw)
	}
	s := string(b)
	if len(s) > 240 {
		return s[:240] + "…"
	}
	return s
}

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
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	"github.com/google/uuid"
)

// PublicEvent is independent of runtime and orchestration storage DTOs.
type PublicEvent struct {
	SchemaVersion int            `json:"schema_version"`
	ID            string         `json:"id"`
	Type          string         `json:"type"`
	InvocationID  string         `json:"invocation_id"`
	CreatedAt     int64          `json:"created_at"`
	Cursor        string         `json:"cursor"`
	Data          map[string]any `json:"data"`
	Sequence      int64          `json:"-"`
}
type Snapshot struct {
	AsOf       string                    `json:"as_of"`
	Invocation map[string]any            `json:"invocation"`
	Items      map[string]map[string]any `json:"items"`
	Tools      map[string]map[string]any `json:"tools"`
	Actions    map[string]map[string]any `json:"required_actions"`
	Steps      map[string]map[string]any `json:"steps"`
	Artifacts  map[string]map[string]any `json:"artifacts"`
	Usage      map[string]map[string]any `json:"usage"`
}
type sourceReceipt struct {
	Sequence int64 `json:"sequence"`
	Pruned   bool  `json:"pruned,omitempty"`
}
type head struct {
	Sequence int64 `json:"sequence"`
	Floor    int64 `json:"floor"`
}
type snapshotCheckpoint struct {
	Sequence int64    `json:"sequence"`
	Snapshot Snapshot `json:"snapshot"`
}

var ErrCursorExpired = errors.New("event cursor expired; load snapshot and resume from as_of")

const checkpointInterval = 64

func emptySnapshot(id uuid.UUID) Snapshot {
	return Snapshot{AsOf: Cursor(id, 0), Invocation: map[string]any{}, Items: map[string]map[string]any{}, Tools: map[string]map[string]any{}, Actions: map[string]map[string]any{}, Steps: map[string]map[string]any{}, Artifacts: map[string]map[string]any{}, Usage: map[string]map[string]any{}}
}

type Journal struct {
	Store        store.Store
	Tenant       string
	InvocationID uuid.UUID
}

func (j Journal) path() string { return "service-api/invocations/" + j.InvocationID.String() }
func Cursor(id uuid.UUID, seq int64) string {
	return base64.RawURLEncoding.EncodeToString([]byte("inv1:" + id.String() + ":" + strconv.FormatInt(seq, 10)))
}
func Position(id uuid.UUID, cursor string) (int64, error) {
	if cursor == "" {
		return 0, nil
	}
	raw, err := base64.RawURLEncoding.DecodeString(cursor)
	if err != nil {
		return 0, fmt.Errorf("invalid invocation cursor")
	}
	p := strings.Split(string(raw), ":")
	if len(p) != 3 || p[0] != "inv1" || p[1] != id.String() {
		return 0, fmt.Errorf("cursor belongs to another invocation")
	}
	n, err := strconv.ParseInt(p[2], 10, 64)
	if err != nil || n < 0 {
		return 0, fmt.Errorf("invalid invocation cursor")
	}
	return n, nil
}
func (j Journal) load(ctx context.Context) (*head, error) {
	h := &head{}
	item, err := j.Store.KV().Get(ctx, j.Tenant, j.path(), "head")
	if errors.Is(err, store.ErrNotFound) {
		return h, nil
	}
	if err != nil {
		return nil, err
	}
	if err = json.Unmarshal(item.Value, h); err != nil {
		return nil, err
	}
	return h, nil
}

// Snapshot replays at most one checkpoint interval. Writers only rewrite the
// full view every 64 events, avoiding a full transcript write per token delta.
func (j Journal) snapshotAt(ctx context.Context, sequence int64) (Snapshot, error) {
	checkpoint := snapshotCheckpoint{Snapshot: emptySnapshot(j.InvocationID)}
	row, err := j.Store.KV().Get(ctx, j.Tenant, j.path(), "snapshot")
	if err == nil {
		if err = json.Unmarshal(row.Value, &checkpoint); err != nil {
			return Snapshot{}, err
		}
	} else if !errors.Is(err, store.ErrNotFound) {
		return Snapshot{}, err
	}
	if checkpoint.Sequence > sequence {
		return Snapshot{}, store.ErrConflict
	}
	for n := checkpoint.Sequence + 1; n <= sequence; n++ {
		event, err := j.event(ctx, n)
		if err != nil {
			return Snapshot{}, err
		}
		apply(&checkpoint.Snapshot, event)
	}
	checkpoint.Snapshot.AsOf = Cursor(j.InvocationID, sequence)
	return checkpoint.Snapshot, nil
}
func (j Journal) Snapshot(ctx context.Context) (Snapshot, error) {
	var snapshot Snapshot
	err := j.Store.WithSessionLock(ctx, "service-journal:"+j.InvocationID.String(), func(ctx context.Context) error {
		h, err := j.load(ctx)
		if err != nil {
			return err
		}
		snapshot, err = j.snapshotAt(ctx, h.Sequence)
		return err
	})
	return snapshot, err
}
func (j Journal) Append(ctx context.Context, source, eventType string, data map[string]any) (*PublicEvent, error) {
	var result *PublicEvent
	err := j.Store.WithSessionLock(ctx, "service-journal:"+j.InvocationID.String(), func(ctx context.Context) error {
		h, err := j.load(ctx)
		if err != nil {
			return err
		}
		id := uuid.NewSHA1(j.InvocationID, []byte(source)).String()
		indexKey := "source/" + id
		if item, err := j.Store.KV().Get(ctx, j.Tenant, j.path(), indexKey); err == nil {
			var receipt sourceReceipt
			if json.Unmarshal(item.Value, &receipt) == nil && receipt.Sequence <= h.Sequence {
				n := receipt.Sequence
				if n <= h.Floor && receipt.Pruned {
					result = &PublicEvent{SchemaVersion: 1, ID: uuid.NewSHA1(j.InvocationID, []byte(source)).String(), Type: eventType, InvocationID: j.InvocationID.String(), Cursor: Cursor(j.InvocationID, n), Sequence: n}
					return nil
				}
				result, err = j.event(ctx, n)
				if err != nil && !(errors.Is(err, store.ErrNotFound) && n <= h.Floor) {
					return err
				}
				if result != nil && result.ID == id {
					return nil
				}
			}
		} else if !errors.Is(err, store.ErrNotFound) {
			return err
		}
		n := h.Sequence + 1
		result = &PublicEvent{SchemaVersion: 1, ID: uuid.NewSHA1(j.InvocationID, []byte(source)).String(), Type: eventType, InvocationID: j.InvocationID.String(), CreatedAt: time.Now().UnixMilli(), Cursor: Cursor(j.InvocationID, n), Data: data, Sequence: n}
		raw, err := json.Marshal(result)
		if err != nil {
			return err
		}
		if _, err = j.Store.KV().Put(ctx, j.Tenant, j.path()+"/events", fmt.Sprintf("%020d", n), raw); err != nil {
			return err
		}
		raw, _ = json.Marshal(sourceReceipt{Sequence: n})
		if _, err = j.Store.KV().Put(ctx, j.Tenant, j.path(), indexKey, raw); err != nil {
			return err
		}
		h.Sequence = n

		raw, err = json.Marshal(h)
		if err != nil {
			return err
		}
		if _, err = j.Store.KV().Put(ctx, j.Tenant, j.path(), "head", raw); err != nil {
			return err
		}
		if n%checkpointInterval == 0 {
			snapshot, err := j.snapshotAt(ctx, n)
			if err != nil {
				return err
			}
			raw, err = json.Marshal(snapshotCheckpoint{Sequence: n, Snapshot: snapshot})
			if err != nil {
				return err
			}
			if _, err = j.Store.KV().Put(ctx, j.Tenant, j.path(), "snapshot", raw); err != nil {
				return err
			}
		}
		return nil
	})
	return result, err
}
func (j Journal) event(ctx context.Context, n int64) (*PublicEvent, error) {
	item, err := j.Store.KV().Get(ctx, j.Tenant, j.path()+"/events", fmt.Sprintf("%020d", n))
	if err != nil {
		return nil, err
	}
	var e PublicEvent
	err = json.Unmarshal(item.Value, &e)
	e.Sequence = n
	return &e, err
}
func (j Journal) Events(ctx context.Context, after string, limit int) ([]*PublicEvent, string, bool, error) {
	var events []*PublicEvent
	var next string
	var more bool
	err := j.Store.WithSessionLock(ctx, "service-journal:"+j.InvocationID.String(), func(ctx context.Context) error {
		var err error
		events, next, more, err = j.eventsLocked(ctx, after, limit)
		return err
	})
	return events, next, more, err
}

// Reading the floor and its page uses the writer lock, so pruning cannot turn
// an otherwise valid page into a missing-row response midway through delivery.
func (j Journal) eventsLocked(ctx context.Context, after string, limit int) ([]*PublicEvent, string, bool, error) {
	n, err := Position(j.InvocationID, after)
	if err != nil {
		return nil, "", false, err
	}
	if limit < 1 || limit > 1000 {
		return nil, "", false, fmt.Errorf("limit must be 1..1000")
	}
	h, err := j.load(ctx)
	if err != nil {
		return nil, "", false, err
	}
	if n < h.Floor {
		return nil, "", false, ErrCursorExpired
	}
	if n > h.Sequence {
		return nil, "", false, store.ErrConflict
	}
	result := make([]*PublicEvent, 0)
	for n < h.Sequence && len(result) < limit {
		n++
		e, err := j.event(ctx, n)
		if err != nil {
			return nil, "", false, err
		}
		result = append(result, e)
	}
	return result, Cursor(j.InvocationID, n), n < h.Sequence, nil
}

// PruneEvents preserves a complete snapshot and source deduplication receipts.
// Call only for terminal invocations after their configured retention window.
// The committed floor is moved before deletion, so expired cursors fail clearly.
func (j Journal) PruneEvents(ctx context.Context) (bool, error) {
	var done bool
	err := j.Store.WithSessionLock(ctx, "service-journal:"+j.InvocationID.String(), func(ctx context.Context) error {
		h, err := j.load(ctx)
		if err != nil {
			return err
		}
		snapshot, err := j.snapshotAt(ctx, h.Sequence)
		if err != nil {
			return err
		}
		raw, _ := json.Marshal(snapshotCheckpoint{Sequence: h.Sequence, Snapshot: snapshot})
		if _, err = j.Store.KV().Put(ctx, j.Tenant, j.path(), "snapshot", raw); err != nil {
			return err
		}
		h.Floor = h.Sequence
		raw, _ = json.Marshal(h)
		if _, err = j.Store.KV().Put(ctx, j.Tenant, j.path(), "head", raw); err != nil {
			return err
		}
		rows, err := j.Store.KV().Search(ctx, j.Tenant, j.path()+"/events", 1000, 0)
		if err != nil {
			return err
		}
		for _, row := range rows {
			var event PublicEvent
			if err = json.Unmarshal(row.Value, &event); err != nil {
				return err
			}
			sequence, err := Position(j.InvocationID, event.Cursor)
			if err != nil {
				return err
			}
			if sequence <= h.Floor {
				receipt, _ := json.Marshal(sourceReceipt{Sequence: sequence, Pruned: true})
				if _, err = j.Store.KV().Put(ctx, j.Tenant, j.path(), "source/"+event.ID, receipt); err != nil {
					return err
				}
			}
			if err = j.Store.KV().Delete(ctx, j.Tenant, j.path()+"/events", row.Key); err != nil {
				return err
			}
		}
		done = len(rows) < 1000
		return nil
	})
	return done, err
}

func field(d map[string]any, k string) string { v, _ := d[k].(string); return v }
func object(v any) map[string]any {
	m, _ := v.(map[string]any)
	if m == nil {
		return map[string]any{}
	}
	return m
}
func blocks(v any) []any { a, _ := v.([]any); return a }
func merge(a, b map[string]any) map[string]any {
	out := map[string]any{}
	for k, v := range a {
		out[k] = v
	}
	for k, v := range b {
		out[k] = v
	}
	return out
}
func terminalTool(status string) bool {
	switch status {
	case "completed", "success", "error", "denied", "interrupted", "failed":
		return true
	}
	return false
}
func updateTool(s *Snapshot, e *PublicEvent, b map[string]any, status string) {
	id := field(b, "id")
	if id == "" {
		id = field(b, "tool_call_id")
	}
	if id == "" {
		return
	}
	key := field(e.Data, "execution_id") + ":" + id
	d := merge(s.Tools[key], map[string]any{"execution_id": e.Data["execution_id"], "tool_call_id": id})
	for _, k := range []string{"item_id", "agent_id", "model_call_id"} {
		if v, ok := e.Data[k]; ok {
			d[k] = v
		}
	}
	if name := field(b, "name"); name != "" && !strings.HasPrefix(name, "__") {
		d["name"] = name
	}
	if v, ok := b["input"]; ok {
		d["input"] = v
	}
	if args, ok := b["content"].(string); ok {
		if e.Type == "item.delta" {
			args = field(d, "arguments") + args
		}
		d["arguments"] = args
	}
	if output, ok := b["output"]; ok {
		if e.Type == "tool.delta" {
			d["progress"] = append(blocks(d["progress"]), blocks(output)...)
		} else {
			d["output"] = output
		}
	}
	if v, ok := b["result"]; ok {
		d["result"] = v
	}
	if state := field(b, "state"); state != "" {
		status = strings.ToLower(state)
	}
	if !terminalTool(field(d, "status")) || status != "requested" && status != "generating_arguments" {
		d["status"] = status
	}
	s.Tools[key] = d
}
func apply(s *Snapshot, e *PublicEvent) {
	d := e.Data
	switch {
	case strings.HasPrefix(e.Type, "invocation."):
		s.Invocation = d
	case e.Type == "item.started" || e.Type == "item.completed":
		id := field(d, "item_id")
		if id == "" {
			return
		}
		s.Items[id] = d
		item := object(d["item"])
		if e.Type == "item.completed" {
			item["status"] = "completed"
		}
		for _, v := range blocks(item["content"]) {
			b := object(v)
			if field(b, "type") == "tool_use" {
				updateTool(s, e, b, "requested")
			}
			if field(b, "type") == "tool_result" {
				updateTool(s, e, b, "completed")
			}
		}
	case e.Type == "item.delta":
		id := field(d, "item_id")
		if id == "" {
			return
		}
		old := s.Items[id]
		item := object(old["item"])
		if field(item, "status") == "completed" || field(item, "status") == "incomplete" {
			return
		}
		content := blocks(item["content"])
		active := field(old, "active_tool_call_id")
		for _, v := range blocks(d["content"]) {
			b := object(v)
			switch field(b, "type") {
			case "text":
				if len(content) > 0 && field(object(content[len(content)-1]), "type") == "text" {
					tail := object(content[len(content)-1])
					tail["text"] = field(tail, "text") + field(b, "text")
				} else {
					content = append(content, b)
				}
			case "tool_use":
				b = merge(nil, b)
				if field(b, "id") != "" {
					active = field(b, "id")
				} else {
					b["id"] = active
				}
				updateTool(s, e, b, "generating_arguments")
			default:
				content = append(content, b)
			}
		}
		item = merge(item, map[string]any{"id": id, "type": "message", "role": "assistant", "content": content, "status": "in_progress"})
		next := merge(old, d)
		delete(next, "content")
		next["item"], next["active_tool_call_id"] = item, active
		s.Items[id] = next
	case strings.HasPrefix(e.Type, "tool."):
		status := "running"
		if e.Type == "tool.requested" {
			status = "requested"
		}
		if e.Type == "tool.completed" {
			status = field(d, "status")
			if status == "" {
				status = "completed"
			}
		}
		updateTool(s, e, merge(d, object(d["result"])), status)
	case e.Type == "execution.ended":
		for _, v := range s.Items {
			item := object(v["item"])
			if v["execution_id"] == d["execution_id"] && field(item, "status") == "in_progress" {
				item["status"] = "incomplete"
			}
		}
		for _, v := range s.Tools {
			if v["execution_id"] == d["execution_id"] && !terminalTool(field(v, "status")) {
				v["status"] = "unknown"
			}
		}
	case strings.HasPrefix(e.Type, "required_action."):
		id := field(d, "request_id")
		if e.Type == "required_action.resolved" {
			delete(s.Actions, id)
		} else {
			s.Actions[id] = d
		}
	case strings.HasPrefix(e.Type, "step."):
		s.Steps[field(d, "step_id")] = d
	case e.Type == "artifact.published":
		s.Artifacts[field(d, "artifact_id")] = d
	case e.Type == "artifact.deleted":
		delete(s.Artifacts, field(d, "artifact_id"))
	case e.Type == "usage.recorded" || e.Type == "model.completed":
		id := field(d, "model_call_id")
		if id == "" {
			id = "total"
		}
		s.Usage[field(d, "execution_id")+":"+id] = d
	}
}

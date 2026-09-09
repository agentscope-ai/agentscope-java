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

// Package conversation persists personal External conversation turns independently
// of published Endpoint invocations. Session locks serialize admission, reports,
// and timeout recovery across control-plane replicas.
package conversation

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type Turn struct {
	ID                  uuid.UUID `json:"id"`
	InvocationID        uuid.UUID `json:"invocationId"`
	ConversationID      uuid.UUID `json:"conversationId"`
	AgentID             uuid.UUID `json:"agentId"`
	BindingID           uuid.UUID `json:"bindingId"`
	InstanceID          uuid.UUID `json:"instanceId"`
	Generation          int64     `json:"generation"`
	State               string    `json:"state"`
	Sequence            int64     `json:"sequence"`
	LegacyDeltaSequence int64     `json:"legacyDeltaSequence,omitempty"`
	Deadline            time.Time `json:"deadline"`
}

func (t *Turn) Pending() bool { return t != nil && (t.State == "dispatching" || t.State == "running") }

func Read(session *store.Session) *Turn {
	var data struct {
		Turn *Turn `json:"conversationTurn"`
	}
	if session == nil || json.Unmarshal(session.TaskContext, &data) != nil {
		return nil
	}
	return data.Turn
}

func lockKey(s *store.Session) string {
	return s.Tenant + "\x00" + s.Namespace + "\x00" + s.AgentID.String() + "\x00" + s.SessionID
}

func save(ctx context.Context, st store.Store, s *store.Session, turn *Turn) error {
	data := map[string]json.RawMessage{}
	if len(s.TaskContext) > 0 {
		if err := json.Unmarshal(s.TaskContext, &data); err != nil {
			return err
		}
	}
	if data == nil {
		data = map[string]json.RawMessage{}
	}
	data["conversationTurn"], _ = json.Marshal(turn)
	s.TaskContext, _ = json.Marshal(data)
	_, err := st.Sessions().Upsert(ctx, s)
	return err
}

// Begin durably records admission before sending a command to the runtime.
func Begin(ctx context.Context, st store.Store, session *store.Session, message string, now time.Time) (*Turn, error) {
	var turn *Turn
	err := st.WithSessionLock(ctx, lockKey(session), func(ctx context.Context) error {
		s, err := st.Sessions().GetByID(ctx, session.ID)
		if err != nil {
			return err
		}
		if err := CheckChatActive(ctx, st, s); err != nil {
			return err
		}
		if Read(s).Pending() {
			return fmt.Errorf("conversation already has a turn in progress: %w", store.ErrConflict)
		}
		turn = &Turn{ID: uuid.New(), InvocationID: uuid.New(), ConversationID: s.ID,
			AgentID: s.AgentID, BindingID: s.BindingID, InstanceID: s.AgentInstanceID,
			Generation: s.InstanceGeneration, State: "dispatching", Deadline: now.Add(5 * time.Minute)}
		s.Phase, s.LastActiveAt = store.SessionPhaseActive, &now
		if err = save(ctx, st, s, turn); err != nil {
			return err
		}
		if err = st.Turns().SyncOnPhase(ctx, s.ID, store.SessionPhaseActive); err != nil {
			return err
		}
		if err = appendEvent(ctx, st, s, turn, "user", "user.message", "user", message, nil, now); err != nil {
			return err
		}
		return appendEvent(ctx, st, s, turn, "start", "turn.started", "", "", nil, now)
	})
	return turn, err
}

// CheckChatActive is called while holding the Session lock so deletion cannot
// race a new admission through either Chat or the Session diagnostics endpoint.
func CheckChatActive(ctx context.Context, st store.Store, s *store.Session) error {
	if s.OriginType != "chat" {
		return nil
	}
	id, err := uuid.Parse(s.OriginRef)
	if err != nil {
		return store.ErrNotFound
	}
	chat, err := st.Chats().Get(ctx, id)
	if err != nil || chat.SessionID != s.ID || chat.Tenant != s.Tenant || chat.Namespace != s.Namespace || chat.AgentID != s.AgentID {
		return store.ErrNotFound
	}
	if chat.Status != model.ChatActive {
		return fmt.Errorf("restore this Chat before sending a message: %w", store.ErrConflict)
	}
	return nil
}

type Report struct {
	InvocationID, ConversationID, TurnID uuid.UUID
	AgentID, BindingID, InstanceID       uuid.UUID
	Generation                           int64
	Action                               string
	Sequence                             int64
	Payload                              json.RawMessage
	ErrorCode, ErrorMessage              string
}

// Apply accepts only the frozen runtime identity and current turn. Replays and
// late sequenced reports cannot append duplicate messages or resurrect a terminal turn.
// Legacy deltas have no delivery identity and are preserved in arrival order.
func Apply(ctx context.Context, st store.Store, session *store.Session, report Report, now time.Time) error {
	return st.WithSessionLock(ctx, lockKey(session), func(ctx context.Context) error {
		s, err := st.Sessions().GetByID(ctx, session.ID)
		if err != nil {
			return err
		}
		t := Read(s)
		if t == nil || t.ID != report.TurnID || t.InvocationID != report.InvocationID ||
			t.ConversationID != report.ConversationID || t.AgentID != report.AgentID ||
			t.BindingID != report.BindingID || t.InstanceID != report.InstanceID || t.Generation != report.Generation {
			return store.ErrNotFound
		}
		if !t.Pending() || report.Sequence > 0 && report.Sequence <= t.Sequence {
			return nil
		}
		return applyLocked(ctx, st, s, t, report, now)
	})
}

func applyLocked(ctx context.Context, st store.Store, s *store.Session, t *Turn, r Report, now time.Time) error {
	state, eventType, role, content := "running", "turn."+r.Action, "", ""
	switch r.Action {
	case "accepted", "started":
	case "delta":
		eventType, role, content = "assistant.delta", "assistant", responseText(r.Payload)
	case "completed":
		state, content = "completed", responseText(r.Payload)
	case "failed":
		state, role, content = "failed", "error", r.ErrorMessage
		if content == "" {
			content = "The Agent could not complete this turn."
		}
	case "cancelled":
		state = "cancelled"
	default:
		return fmt.Errorf("unsupported ConversationTurn action %q", r.Action)
	}
	key := fmt.Sprintf("%s:%d", r.Action, r.Sequence)
	if r.Action == "delta" && r.Sequence <= 0 {
		// Older SDKs omit sequence. Do not collapse every chunk into delta:0;
		// identical adjacent chunks are legitimate. Sequenced reports retain
		// replay deduplication; final output replaces the accumulated stream.
		t.LegacyDeltaSequence++
		key = fmt.Sprintf("delta:legacy:%d", t.LegacyDeltaSequence)
	}
	if r.Action == "completed" && content != "" {
		if err := appendEvent(ctx, st, s, t, key+":message", "assistant.message", "assistant", content, r.Payload, now); err != nil {
			return err
		}
		content = ""
	}
	meta, _ := json.Marshal(map[string]any{"payload": json.RawMessage(r.Payload), "errorCode": r.ErrorCode})
	if err := appendEvent(ctx, st, s, t, key, eventType, role, content, meta, now); err != nil {
		return err
	}
	t.State = state
	if r.Sequence > 0 {
		t.Sequence = r.Sequence
	}
	s.LastActiveAt = &now
	phase := store.SessionPhaseActive
	if !t.Pending() {
		s.Phase, phase = store.SessionPhaseIdle, store.SessionPhaseIdle
		if state == "failed" {
			phase = store.TurnStatusFailed
		} else if state == "cancelled" {
			phase = store.SessionPhaseTerminated
		}
	}
	// Close the durable turn before admission is released. Repeating a report
	// after a partial write remains safe because event source keys are stable.
	if err := st.Turns().SyncOnPhase(ctx, s.ID, phase); err != nil {
		return err
	}
	return save(ctx, st, s, t)
}

func Fail(ctx context.Context, st store.Store, session *store.Session, turnID uuid.UUID, code, message string, now time.Time) error {
	return st.WithSessionLock(ctx, lockKey(session), func(ctx context.Context) error {
		s, err := st.Sessions().GetByID(ctx, session.ID)
		if err != nil {
			return err
		}
		t := Read(s)
		if !t.Pending() || t.ID != turnID {
			return nil
		}
		return applyLocked(ctx, st, s, t, Report{Action: "failed", ErrorCode: code, ErrorMessage: message}, now)
	})
}

func responseText(raw json.RawMessage) string {
	var text string
	if json.Unmarshal(raw, &text) == nil {
		return text
	}
	var value map[string]json.RawMessage
	if json.Unmarshal(raw, &value) != nil {
		return ""
	}
	for _, key := range []string{"content", "text", "answer", "output", "message", "result"} {
		if v := value[key]; len(v) > 0 {
			if text := responseText(v); text != "" {
				return text
			}
		}
	}
	return ""
}

func appendEvent(ctx context.Context, st store.Store, s *store.Session, t *Turn, key, eventType, role, content string, raw json.RawMessage, now time.Time) error {
	key = "conversation:" + t.ID.String() + ":" + key
	events, err := st.Events().List(ctx, s.ID)
	if err != nil {
		return err
	}
	seq := 0
	for _, e := range events {
		if e.Seq > seq {
			seq = e.Seq
		}
		var meta struct {
			SourceKey string `json:"sourceKey"`
		}
		if json.Unmarshal(e.FrameworkMeta, &meta) == nil && meta.SourceKey == key {
			return nil
		}
	}
	meta, _ := json.Marshal(map[string]any{"sourceKey": key, "turnId": t.ID, "invocationId": t.InvocationID, "payload": json.RawMessage(raw)})
	return st.Events().Append(ctx, &store.SessionEvent{SessionFK: s.ID, Seq: seq + 1, EventType: eventType, Role: role,
		Content: content, FrameworkMeta: meta, OccurredAt: now})
}

// Sweep also recovers after control-plane restarts; no in-process timer owns a turn.
func Sweep(ctx context.Context, st store.Store, now time.Time) error {
	var pending []*store.Session
	for offset := 0; ; offset += 200 {
		page, err := st.Sessions().List(ctx, store.SessionFilter{PendingConversation: true, Limit: 200, Offset: offset})
		if err != nil {
			return err
		}
		for _, s := range page {
			if Read(s).Pending() {
				pending = append(pending, s)
			}
		}
		if len(page) < 200 {
			break
		}
	}
	for _, s := range pending {
		t := Read(s)
		code, message := "", ""
		if !now.Before(t.Deadline) {
			code, message = "conversation_timeout", "The Agent did not finish before this turn's deadline. Retry when it is available."
		}
		instance, err := st.RuntimeRegistry().GetAgentInstance(ctx, t.InstanceID)
		if err != nil && err != store.ErrNotFound {
			return err
		}
		if err == store.ErrNotFound || instance.Generation != t.Generation || instance.Health != model.RuntimeHealthHealthy {
			code, message = "conversation_instance_unavailable", "The Agent disconnected while processing this message. Retry when it is available."
		}
		if code != "" {
			if err := Fail(ctx, st, s, t.ID, code, message, now); err != nil {
				return err
			}
		}
	}
	return nil
}

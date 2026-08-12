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

package sessionops

import (
	"encoding/json"
	"fmt"
	"net/http"

	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// Command names accepted by the router.
const (
	CommandCompress  = "compress"
	CommandTerminate = "terminate"
	CommandAbort     = "abort"
	CommandUndo      = "undo"
	CommandRedo      = "redo"
	CommandPlan      = "plan"
)

// Error codes returned to the console (and stored on audit rows).
const (
	CodeBusy        = "busy"
	CodeUnsupported = "unsupported"
	CodeUnreachable = "unreachable"
	CodeFailed      = "failed"
	CodeNotFound    = "not_found"
	CodeQueued      = "queued"
)

// Hints refine CodeBusy for the console.
const (
	HintWaitIdle     = "wait_idle"
	HintForceConfirm = "force_confirm"
	HintQueued       = "queued"
)

// Request is an inbound session command.
type Request struct {
	Command   string
	Operator  string
	Source    string
	Force     bool
	// Queue controls deferral when the session is busy (busy=true).
	// nil means default: queue idle-required commands; false forces 409.
	Queue     *bool
	CommandID string
}

// Result is a successful (or cached idempotent / queued) command outcome.
type Result struct {
	Accepted  bool                  `json:"accepted"`
	CommandID string                `json:"commandId,omitempty"`
	Phase     string                `json:"phase,omitempty"`
	Result    json.RawMessage       `json:"result,omitempty"`
	Forced    bool                  `json:"forced,omitempty"`
	Cached    bool                  `json:"cached,omitempty"`
	Queued    bool                  `json:"queued,omitempty"`
	Audit     *store.SessionCommand `json:"-"`
}

// Error is a typed command failure with HTTP status and console error shape.
type Error struct {
	Status int
	Code   string
	Msg    string
	Hint   string
}

func (e *Error) Error() string {
	if e == nil {
		return ""
	}
	if e.Hint != "" {
		return fmt.Sprintf("%s (%s): %s", e.Code, e.Hint, e.Msg)
	}
	return fmt.Sprintf("%s: %s", e.Code, e.Msg)
}

// AsError unwraps a typed sessionops.Error.
func AsError(err error) (*Error, bool) {
	if err == nil {
		return nil, false
	}
	e, ok := err.(*Error)
	return e, ok
}

func errBusy(msg, hint string) *Error {
	if hint == "" {
		hint = HintWaitIdle
	}
	return &Error{Status: http.StatusConflict, Code: CodeBusy, Msg: msg, Hint: hint}
}

func errUnsupported(msg string) *Error {
	return &Error{Status: http.StatusNotImplemented, Code: CodeUnsupported, Msg: msg}
}

func errUnreachable(msg string) *Error {
	return &Error{Status: http.StatusServiceUnavailable, Code: CodeUnreachable, Msg: msg}
}

func errFailed(msg string) *Error {
	return &Error{Status: http.StatusInternalServerError, Code: CodeFailed, Msg: msg}
}

func errNotFound(msg string) *Error {
	return &Error{Status: http.StatusNotFound, Code: CodeNotFound, Msg: msg}
}

func shouldQueue(req Request) bool {
	if req.Queue == nil {
		return true // default: queue when busy
	}
	return *req.Queue
}

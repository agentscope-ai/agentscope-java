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
	"strings"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// requiredCapability maps a command to the data-plane capability that must be
// advertised before the control plane will dispatch it.
func requiredCapability(command string) string {
	switch strings.ToLower(command) {
	case CommandCompress, CommandTerminate:
		return v1alpha1.CapabilitySessionCommand
	case CommandAbort:
		return v1alpha1.CapabilitySessionAbort
	case CommandUndo:
		return v1alpha1.CapabilitySessionUndo
	case CommandRedo:
		return v1alpha1.CapabilitySessionRedo
	case CommandPlan:
		return v1alpha1.CapabilityPlanMode
	default:
		return ""
	}
}

func hasCapability(caps []string, want string) bool {
	for _, c := range caps {
		if c == want {
			return true
		}
	}
	return false
}

func normalizePhase(phase string) string {
	return strings.ToLower(strings.TrimSpace(phase))
}

// phaseIsActive reports whether the session is mid-turn (phase authoritative).
func phaseIsActive(sess *store.Session) bool {
	if sess == nil {
		return false
	}
	if normalizePhase(sess.Phase) == store.SessionPhaseActive {
		return true
	}
	// Legacy: busy=true without phase=active still means in-flight turn.
	return sess.Busy != nil && *sess.Busy
}

// interruptAllowed reports whether abort/terminate may run for the phase.
func interruptAllowed(phase string) bool {
	switch normalizePhase(phase) {
	case store.SessionPhaseActive, store.SessionPhaseIdle, store.SessionPhaseCompressing:
		return true
	default:
		return false
	}
}

// checkCapability verifies the instance advertises the command capability.
func checkCapability(entry *dataplane.Entry, command string) *Error {
	capName := requiredCapability(command)
	if capName == "" {
		return errUnsupported("unknown command: " + command)
	}
	if entry == nil {
		return errUnreachable("no data plane instance for session")
	}
	if !hasCapability(entry.Capabilities, capName) {
		return errUnsupported("data plane does not advertise capability " + capName)
	}
	return nil
}

// resolveInstance prefers session.instanceRef (affinity). Soft-affinity peer
// fallback is allowed only when the session is not hard-bound mid-turn
// (active / compressing), so abort/compress-in-flight never hit a cold replica.
func resolveInstance(registry *dataplane.Registry, sess *store.Session) (*dataplane.Entry, *Error) {
	if sess == nil {
		return nil, errUnreachable("session is nil")
	}
	if registry == nil {
		return nil, errUnreachable("data plane registry unavailable")
	}
	phase := normalizePhase(sess.Phase)
	hardBound := phase == store.SessionPhaseActive || phase == store.SessionPhaseCompressing

	if sess.InstanceRef != "" {
		if entry := registry.Get(sess.InstanceRef); entry != nil && entry.Healthy && entry.BaseURL != "" {
			return entry, nil
		}
		if hardBound {
			return nil, errUnreachable("instance unavailable while session is " + phase + ": " + sess.InstanceRef)
		}
	} else if hardBound {
		return nil, errUnreachable("session has no instanceRef while " + phase)
	}

	for _, entry := range registry.ListByAgent(sess.AgentName, sess.Namespace) {
		if entry != nil && entry.Healthy && entry.BaseURL != "" {
			return entry, nil
		}
	}
	if sess.InstanceRef != "" {
		return nil, errUnreachable("preferred instance unavailable and no healthy peer: " + sess.InstanceRef)
	}
	return nil, errUnreachable("no healthy data plane instance for agent " + sess.AgentName)
}

// checkInstanceReachable is the soft-affinity resolver used by the router.
func checkInstanceReachable(registry *dataplane.Registry, sess *store.Session) (*dataplane.Entry, *Error) {
	return resolveInstance(registry, sess)
}

// checkGate enforces the operational phase state machine.
//
// Compress/undo/redo/plan require phase=idle (or unknown phase with force).
// Active / compressing → wait_idle (queueable). Archived / terminated → reject.
//
// Returns (forced, err).
func checkGate(sess *store.Session, command string, force bool) (forced bool, err *Error) {
	cmd := strings.ToLower(command)
	phase := normalizePhase(sess.Phase)

	switch cmd {
	case CommandAbort, CommandTerminate:
		if !interruptAllowed(phase) {
			if phase == store.SessionPhaseTerminated || phase == store.SessionPhaseArchived {
				return false, errNotFound("session is " + phase)
			}
			return false, errBusy("command not allowed in phase "+sess.Phase, HintWaitIdle)
		}
		return false, nil

	case CommandCompress, CommandUndo, CommandRedo, CommandPlan:
		switch phase {
		case store.SessionPhaseTerminated, store.SessionPhaseArchived:
			return false, errNotFound("session is " + phase)
		case store.SessionPhaseActive, store.SessionPhaseCompressing:
			return false, errBusy("session phase is "+phase, HintWaitIdle)
		case store.SessionPhaseIdle:
			return false, nil
		default:
			// Unknown / empty phase: treat like legacy busy unknown.
			if phaseIsActive(sess) {
				return false, errBusy("session is busy", HintWaitIdle)
			}
			if sess.Busy != nil && !*sess.Busy {
				return false, nil
			}
			if force {
				return true, nil
			}
			return false, errBusy("session phase unknown; confirm to proceed", HintForceConfirm)
		}

	default:
		return false, errUnsupported("unknown command: " + command)
	}
}

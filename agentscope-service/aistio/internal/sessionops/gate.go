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

// checkInstanceReachable rejects missing or stale (unhealthy) instances.
// Prefer instanceRef only — never fall back to sibling replicas here.
func checkInstanceReachable(registry *dataplane.Registry, sess *store.Session) (*dataplane.Entry, *Error) {
	if sess.InstanceRef == "" {
		return nil, errUnreachable("session has no instanceRef")
	}
	if registry == nil {
		return nil, errUnreachable("data plane registry unavailable")
	}
	entry := registry.Get(sess.InstanceRef)
	if entry == nil {
		return nil, errUnreachable("instance not registered: " + sess.InstanceRef)
	}
	if !entry.Healthy {
		return nil, errUnreachable("instance stale/unhealthy: " + sess.InstanceRef)
	}
	if entry.BaseURL == "" {
		return nil, errUnreachable("instance has empty baseUrl: " + sess.InstanceRef)
	}
	return entry, nil
}

// checkGate enforces phase/busy rules.
//
// For idle-required commands:
//   - busy=true → errBusy(wait_idle) — caller may queue
//   - busy=nil (unknown) → errBusy(force_confirm) unless Force=true
//
// Returns (forced, err).
func checkGate(sess *store.Session, command string, force bool) (forced bool, err *Error) {
	cmd := strings.ToLower(command)
	phase := normalizePhase(sess.Phase)

	switch cmd {
	case CommandAbort, CommandTerminate:
		if !interruptAllowed(phase) {
			if phase == store.SessionPhaseTerminated {
				return false, errNotFound("session is terminated")
			}
			return false, errBusy("command not allowed in phase "+sess.Phase, HintWaitIdle)
		}
		return false, nil

	case CommandCompress, CommandUndo, CommandRedo, CommandPlan:
		if phase == store.SessionPhaseTerminated {
			return false, errNotFound("session is terminated")
		}
		if sess.Busy != nil {
			if *sess.Busy {
				return false, errBusy("session is busy", HintWaitIdle)
			}
			return false, nil
		}
		// busy unknown
		if force {
			return true, nil
		}
		return false, errBusy("session busy state unknown; confirm to proceed", HintForceConfirm)

	default:
		return false, errUnsupported("unknown command: " + command)
	}
}

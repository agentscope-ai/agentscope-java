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
package io.agentscope.core.permission;

import java.util.Locale;
import java.util.Map;

/**
 * The model-requested permission escalation vocabulary and validation shared by every
 * escalation-aware tool family: the strictly-wider ladder, the argument-pairing validation, and
 * the fail-closed resolution of a {@code sandbox_permissions} request. One home keeps the tool
 * families' validation ordering and model-facing denial texts from drifting apart.
 *
 * <p>Ladder (strictly-wider table — a request may only name a mode strictly wider than the call's
 * effective mode; nothing escalates down, {@code read-only} is the floor):
 *
 * <ul>
 *   <li>{@code read-only} — read operations only (floor)
 *   <li>{@code workspace-write} — reads plus workspace modifications
 *   <li>{@code danger-full-access} — unrestricted (ceiling)
 * </ul>
 *
 * <p>Validation is checked at EXECUTION time from the raw tool-call input, never baked into a
 * tool schema: schemas are registry-global while the effective mode is per-call truth. The
 * schema only advertises the closed target vocabulary.
 *
 * <p>All rejections are fail-closed: a malformed pairing, an unknown target, a non-wider
 * target, or an unavailable approver deny the call with a model-facing reason instead of
 * falling back to normal evaluation.
 */
public final class PermissionEscalation {

    /** Tool argument naming the requested target on the ladder. */
    public static final String ARG_PERMISSIONS = "sandbox_permissions";

    /** Tool argument carrying the model's justification for the request. */
    public static final String ARG_JUSTIFICATION = "justification";

    /** Closed escalation-target vocabulary, ordered from floor to ceiling. */
    public static final String TARGET_READ_ONLY = "read-only";

    public static final String TARGET_WORKSPACE_WRITE = "workspace-write";
    public static final String TARGET_DANGER_FULL_ACCESS = "danger-full-access";

    /** Upper bound on the justification text carried into the approval prompt. */
    public static final int MAX_JUSTIFICATION_LENGTH = 500;

    /**
     * Whether a tool's parameter schema advertises the escalation argument. Escalation intent
     * is scoped to advertising tools so that ordinary business tools with (for example) a
     * legitimate {@code justification} parameter keep their previous evaluation untouched —
     * the single home for this sniff so engine and agent-side gating cannot drift apart.
     */
    public static boolean advertisesEscalation(io.agentscope.core.tool.ToolBase tool) {
        if (tool == null) {
            return false;
        }
        Map<String, Object> parameters = tool.getParameters();
        if (parameters == null) {
            return false;
        }
        Object properties = parameters.get("properties");
        if (!(properties instanceof Map<?, ?> props)) {
            return false;
        }
        return props.containsKey(ARG_PERMISSIONS);
    }

    private PermissionEscalation() {}

    /**
     * Whether a tool-call input carries escalation arguments at all. Callers use this to engage
     * the permission engine for calls that opted in, without changing the evaluation path of
     * calls that did not. A key explicitly mapped to {@code null} carries no request and is
     * ignored, so such a call keeps its previous evaluation path.
     */
    public static boolean hasEscalationArgs(Map<String, Object> input) {
        return input != null
                && (input.get(ARG_PERMISSIONS) != null || input.get(ARG_JUSTIFICATION) != null);
    }

    /**
     * Resolves an escalation request embedded in a tool call's input.
     *
     * @param input the raw tool-call input map
     * @param mode the call's effective permission mode
     * @param escalationEnabled whether escalation is enabled on this agent
     * @return the outcome — {@link Outcome.Type#NOT_PRESENT} when the call carries no escalation
     *     arguments, {@link Outcome.Type#ASK} for a valid strictly-wider request (the caller
     *     routes it through the user-confirmation flow), or {@link Outcome.Type#DENY} with a
     *     model-facing reason for every rejection
     */
    public static Outcome resolve(
            Map<String, Object> input, PermissionMode mode, boolean escalationEnabled) {
        Object permissions = input == null ? null : input.get(ARG_PERMISSIONS);
        Object justification = input == null ? null : input.get(ARG_JUSTIFICATION);

        if (permissions == null && justification == null) {
            return Outcome.notPresent();
        }
        if (!escalationEnabled) {
            return Outcome.deny(
                    "Permission escalation is not enabled on this agent; remove the "
                            + ARG_PERMISSIONS
                            + " and "
                            + ARG_JUSTIFICATION
                            + " arguments and call the tool without them.");
        }
        // Argument pairing: an approval prompt without a reason, or a reason driving nothing,
        // is a malformed ask.
        if (permissions == null) {
            return Outcome.deny(
                    "Invalid escalation: "
                            + ARG_JUSTIFICATION
                            + " is only valid together with "
                            + ARG_PERMISSIONS
                            + ".");
        }
        if (justification == null) {
            return Outcome.deny(
                    "Invalid escalation: "
                            + ARG_PERMISSIONS
                            + " requires a "
                            + ARG_JUSTIFICATION
                            + ".");
        }
        // Non-string values are rejected rather than coerced: a coerced object or array would
        // produce a nonsense prompt the user is asked to approve.
        if (!(permissions instanceof String)) {
            return Outcome.deny("Invalid escalation: " + ARG_PERMISSIONS + " must be a string.");
        }
        if (!(justification instanceof String)) {
            return Outcome.deny(
                    "Invalid justification: " + ARG_JUSTIFICATION + " must be a string.");
        }
        String reason = ((String) justification).trim();
        if (reason.isEmpty()) {
            return Outcome.deny(
                    "Invalid justification: expected a non-empty sentence explaining why this "
                            + "call needs wider permissions.");
        }
        if (reason.length() > MAX_JUSTIFICATION_LENGTH) {
            // Keep the approved prompt honest: the approver must see that the text was cut.
            reason = reason.substring(0, MAX_JUSTIFICATION_LENGTH - 1) + "…";
        }
        String target = ((String) permissions).trim().toLowerCase(Locale.ROOT);
        Integer targetRank = rankOfTarget(target);
        if (targetRank == null) {
            return Outcome.deny(
                    "Invalid escalation target '"
                            + target
                            + "': expected one of "
                            + TARGET_READ_ONLY
                            + ", "
                            + TARGET_WORKSPACE_WRITE
                            + ", "
                            + TARGET_DANGER_FULL_ACCESS
                            + ".");
        }
        if (mode == PermissionMode.DONT_ASK) {
            return Outcome.deny(
                    "Permission escalation is unavailable: no user is available to approve it.");
        }
        int modeRank = rankOfMode(mode);
        if (targetRank <= modeRank) {
            return Outcome.deny(
                    "Invalid escalation target '"
                            + target
                            + "': it is not strictly wider than the current mode ("
                            + mode.name().toLowerCase(Locale.ROOT)
                            + "); call the tool without escalation arguments.");
        }
        return Outcome.ask(target, reason);
    }

    /**
     * Ladder rank of an escalation target, {@code null} when unknown. Ranks are ordered
     * floor-to-ceiling and comparable with {@link #rankOfMode}.
     */
    private static Integer rankOfTarget(String target) {
        return switch (target) {
            case TARGET_READ_ONLY -> 0;
            case TARGET_WORKSPACE_WRITE -> 2;
            case TARGET_DANGER_FULL_ACCESS -> 3;
            default -> null;
        };
    }

    /**
     * Width rank of a permission mode on the same scale as {@link #rankOfTarget}. {@code
     * DONT_ASK} maps to the ceiling so no target is strictly wider (escalation requires an
     * approver that mode explicitly declares unavailable); callers may also short-circuit it
     * earlier for a clearer denial message.
     */
    private static int rankOfMode(PermissionMode mode) {
        return switch (mode) {
            case EXPLORE -> 0;
            case DEFAULT -> 1;
            case ACCEPT_EDITS -> 2;
            case BYPASS -> 3;
            case DONT_ASK -> 3;
        };
    }

    /** Resolution of one escalation request. */
    public record Outcome(Type type, String target, String justification, String denialReason) {

        public enum Type {
            /** The tool call carries no escalation arguments; normal evaluation proceeds. */
            NOT_PRESENT,
            /** A valid strictly-wider request; route through the user-confirmation flow. */
            ASK,
            /** Rejected — always fail-closed, with a model-facing reason. */
            DENY
        }

        static Outcome notPresent() {
            return new Outcome(Type.NOT_PRESENT, null, null, null);
        }

        static Outcome ask(String target, String justification) {
            return new Outcome(Type.ASK, target, justification, null);
        }

        static Outcome deny(String reason) {
            return new Outcome(Type.DENY, null, null, reason);
        }
    }
}

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
package io.agentscope.harness.agent.middleware;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolErrorMetadata;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Detects a model that keeps repeating the same failing tool call and injects one short,
 * non-persisted reminder before the next model request.
 *
 * <p>The guard is a kernel default: it is installed by {@code HarnessAgent} unless the
 * application calls {@code disableRepeatedFailureGuard()}. It exposes no tuning parameters —
 * the thresholds below are fixed, and applications that want different behaviour can register
 * their own middleware instead.
 *
 * <p><b>What counts.</b> Only {@link ToolResultEndEvent}s whose state is {@link
 * ToolResultState#ERROR} or {@link ToolResultState#DENIED} and whose metadata carries
 * {@link ToolErrorMetadata#METADATA_ERROR_KIND} are classified failures. An {@code ERROR}
 * result without a kind is treated as unclassified and ignored, so tools that do not adopt the
 * metadata contract are never classified by their output text. A {@code DENIED} result without a kind is counted
 * as {@code permission_denied}, because denial is unambiguous.
 *
 * <p><b>Signature.</b> Failures are merged per {@code toolName|errorKind[|errorScope]}. Two
 * different files failing with {@code not_found} do not add up; the same file failing with
 * {@code not_found} does.
 *
 * <p><b>Repetition.</b> Independently, identical arguments and identical streamed results are
 * fingerprinted, including successful (exit 0) results. Valid output SHA-256 metadata takes
 * precedence over the exact streamed text/data digest. This path uses a neutral repetition
 * reminder, never infers failure from text, and stores hashes rather than raw inputs or output.
 * Changed results reset only that call's repetition history. Both paths share the reminder cap.
 *
 * <p><b>Thresholds.</b> The guard remembers the last {@value #WINDOW_SIZE} classified failures
 * and intervenes when one signature reaches {@value #FAILURE_THRESHOLD} occurrences. Failures
 * marked {@code agentscope_retriable=true} get a higher threshold
 * ({@value #RETRIABLE_FAILURE_THRESHOLD}) because the contract says a retry may legitimately
 * succeed. At most {@value #MAX_INTERVENTIONS} reminders are injected per user turn, the second
 * one after {@value #SECOND_INTERVENTION_INTERVAL} further occurrences.
 *
 * <p><b>What it does not do.</b> It never retries, rewrites arguments, or stops the run. It only
 * adds context to the next model request; the model remains in charge. The reminder is added to
 * the {@link ReasoningInput} of the following step and is never written to the agent state.
 *
 * <p><b>Reset.</b> Counters are reset after admission of every agent call (a new user turn), on a
 * successful result for the same call (or matching scope when arguments are unavailable), and
 * when the call finishes; per-session state is bounded
 * to {@value #MAX_TRACKED_SESSIONS} sessions and evicted least-recently-used first.
 */
public class RepeatedFailureGuard implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(RepeatedFailureGuard.class);
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /** Number of recent classified failures considered when counting a signature. */
    static final int WINDOW_SIZE = 8;

    /** Occurrences of one signature that trigger the first reminder. */
    static final int FAILURE_THRESHOLD = 3;

    /** Higher trigger for failures the tool itself marked as retriable. */
    static final int RETRIABLE_FAILURE_THRESHOLD = 5;

    /** Further occurrences after the first reminder before the last one is injected. */
    static final int SECOND_INTERVENTION_INTERVAL = 3;

    /** Upper bound on reminders per user turn; enough to nudge, not enough to nag. */
    static final int MAX_INTERVENTIONS = 2;

    /** Hard cap on reminder text so it can never dominate the following request. */
    static final int MAX_REMINDER_CHARS = 800;

    /** Resource bound for per-session counters; states are tiny and evicted LRU. */
    static final int MAX_TRACKED_SESSIONS = 128;

    private static final String DEFAULT_DOMAIN = "unknown";
    private static final String DENIED_KIND = "permission_denied";
    private static final String METADATA_SYNTHETIC = "synthetic";
    private static final String METADATA_REMINDER_KIND = "reminderKind";
    private static final String REMINDER_KIND = "repeated_failure";
    private static final String METADATA_OUTPUT_SHA256 = "agentscope_output_sha256";

    private final Map<String, SessionState> sessions =
            Collections.synchronizedMap(
                    new LinkedHashMap<String, SessionState>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<String, SessionState> eldest) {
                            return size() > MAX_TRACKED_SESSIONS;
                        }
                    });

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(
                ExtensionPoint.ON_AGENT,
                ExtensionPoint.ON_AGENT_STATE_READY,
                ExtensionPoint.ON_REASONING,
                ExtensionPoint.ON_AGENT_EVENT,
                ExtensionPoint.ON_AGENT_FINISH);
    }

    /** Invocation lifetime only; event observation happens before buffering or forwarding. */
    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        // Subscription can precede admission to the same-thread call gate. A queued call must
        // neither claim the active window nor clear it when cancelled.
        return Flux.defer(
                () -> {
                    InvocationToken token = ctx == null ? null : new InvocationToken();
                    if (ctx != null) {
                        // Core supplies an independent effective context per subscription. Copies
                        // within this invocation retain the token; child/new calls replace it.
                        ctx.put(InvocationToken.class, token);
                    }
                    return Flux.defer(() -> next.apply(input))
                            .doFinally(
                                    signal -> {
                                        SessionState state = stateFor(ctx);
                                        synchronized (state) {
                                            // Capture this subscription's token: a late drain must
                                            // not read a replacement token and clear its window.
                                            if (state.owner == token) {
                                                state.reset();
                                            }
                                        }
                                    });
                });
    }

    @Override
    public void onAgentStateReady(
            Agent agent, RuntimeContext ctx, AgentState agentState, List<Msg> inputMessages) {
        SessionState state = stateFor(ctx);
        synchronized (state) {
            state.reset();
            state.owner = invocationToken(ctx);
            state.input = new AgentInput(inputMessages);
        }
    }

    @Override
    public void onAgentEvent(Agent agent, RuntimeContext ctx, AgentEvent event) {
        // The child's own production notification precedes source-tagging by its emitter.
        // Already-forwarded events never belong to this invocation's failure window.
        if (event.getSource() != null && !event.getSource().isBlank()) {
            return;
        }
        SessionState state = stateFor(ctx);
        synchronized (state) {
            if (state.owner != invocationToken(ctx) || state.input == null) {
                return;
            }
            if (event instanceof ToolResultStartEvent start) {
                ToolUseBlock use =
                        findCall(ctx, state.input, start.getToolCallId(), start.getToolCallName());
                if (use != null) {
                    state.calls.put(start.getToolCallId(), new CallObservation(use));
                }
            } else if (event instanceof ToolResultTextDeltaEvent text) {
                CallObservation call = state.calls.get(text.getToolCallId());
                if (call != null) {
                    call.text(text.getDelta());
                }
            } else if (event instanceof ToolResultDataDeltaEvent data) {
                CallObservation call = state.calls.get(data.getToolCallId());
                if (call != null) {
                    call.data(data.getData());
                }
            } else if (event instanceof ToolResultEndEvent result) {
                state.observe(result, state.calls.remove(result.getToolCallId()));
            }
        }
    }

    private static ToolUseBlock findCall(
            RuntimeContext ctx, AgentInput input, String id, String name) {
        // Never fall back to the agent's shared/default slot: only this invocation's history
        // identifies resumed calls safely. At result start, reasoning has already persisted them.
        if (ctx != null && ctx.getAgentState() != null) {
            ToolUseBlock use = findCall(ctx.getAgentState().getContext(), id, name);
            if (use != null) {
                return use;
            }
        }
        return findCall(input.msgs(), id, name);
    }

    private static ToolUseBlock findCall(List<Msg> messages, String id, String name) {
        if (id == null || messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            for (ToolUseBlock use : messages.get(i).getContentBlocks(ToolUseBlock.class)) {
                if (id.equals(use.getId()) && java.util.Objects.equals(name, use.getName())) {
                    return use;
                }
            }
        }
        return null;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        SessionState state = stateFor(ctx);
        Msg reminder;
        synchronized (state) {
            reminder =
                    state.owner == invocationToken(ctx) && state.input != null
                            ? state.takeReminder()
                            : null;
        }
        if (reminder == null) {
            return next.apply(input);
        }
        List<Msg> messages = new ArrayList<>(input.messages());
        messages.add(reminder);
        return next.apply(new ReasoningInput(messages, input.tools(), input.options()));
    }

    @Override
    public Mono<Void> onAgentFinish(Agent agent, RuntimeContext ctx, Msg result) {
        SessionState state = stateFor(ctx);
        synchronized (state) {
            if (state.owner == invocationToken(ctx)) {
                state.reset();
            }
        }
        return Mono.empty();
    }

    private SessionState stateFor(RuntimeContext ctx) {
        String key;
        if (ctx == null) {
            key = "";
        } else {
            key =
                    nullToEmpty(ctx.getUserId())
                            + '\u0000'
                            + nullToEmpty(ctx.getSessionId())
                            + '\u0000'
                            + nullToEmpty(ctx.getThreadId());
        }
        return sessions.computeIfAbsent(key, unused -> new SessionState());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static InvocationToken invocationToken(RuntimeContext ctx) {
        return ctx == null ? null : ctx.get(InvocationToken.class);
    }

    /** Identity survives context derivation, but is replaced for every subscription. */
    private static final class InvocationToken {}

    /** Per-session failure window and pending reminder. Guarded by its own monitor. */
    private static final class SessionState {
        private final Map<String, CallObservation> calls = new HashMap<>();
        private InvocationToken owner;
        private AgentInput input;
        private final Deque<Failure> window = new ArrayDeque<>(WINDOW_SIZE);
        private final Map<String, Integer> failuresSinceReminder = new HashMap<>();
        private final Deque<Repetition> repetitions = new ArrayDeque<>(WINDOW_SIZE);
        private final Map<String, Integer> repetitionsSinceReminder = new HashMap<>();
        private int interventions;
        private PendingReminder pending;

        void reset() {
            calls.clear();
            owner = null;
            input = null;
            window.clear();
            failuresSinceReminder.clear();
            repetitions.clear();
            repetitionsSinceReminder.clear();
            interventions = 0;
            pending = null;
        }

        void observe(ToolResultEndEvent event, CallObservation call) {
            String suppliedDigest = outputDigest(event.getMetadata());
            if (call == null
                    || call.arguments == null
                    || (!call.valid && suppliedDigest == null)
                    || (event.getState() != ToolResultState.SUCCESS
                            && event.getState() != ToolResultState.ERROR
                            && event.getState() != ToolResultState.DENIED)) {
                observeFailure(event, call);
                return;
            }
            String domain =
                    event.getToolCallName() == null ? DEFAULT_DOMAIN : event.getToolCallName();
            Repetition repetition =
                    new Repetition(
                            domain,
                            call.arguments,
                            event.getState()
                                    + ":"
                                    + (suppliedDigest == null
                                            ? "stream:"
                                                    + HexFormat.of().formatHex(call.output.digest())
                                            : "output:" + suppliedDigest));
            boolean classified =
                    event.getState() == ToolResultState.DENIED
                            || (event.getState() == ToolResultState.ERROR
                                    && stringValue(
                                                    event.getMetadata(),
                                                    ToolErrorMetadata.METADATA_ERROR_KIND)
                                            != null);
            repetitions.removeIf(
                    seen ->
                            seen.domain.equals(domain)
                                    && seen.arguments.equals(call.arguments)
                                    && (classified || !seen.output.equals(repetition.output)));
            if (!classified) {
                if (repetitions.size() == WINDOW_SIZE) {
                    repetitions.removeFirst();
                }
                repetitions.addLast(repetition);
            }
            Set<String> live = new HashSet<>();
            int count = 0;
            for (Repetition seen : repetitions) {
                live.add(seen.signature());
                if (seen.equals(repetition)) {
                    count++;
                }
            }
            repetitionsSinceReminder.keySet().retainAll(live);
            if (pending != null && pending.kind == null && !live.contains(pending.signature)) {
                pending = null;
            }
            observeFailure(event, call);
            if (classified) {
                return;
            }
            int threshold =
                    Boolean.TRUE.equals(
                                    booleanValue(
                                            event.getMetadata(),
                                            ToolErrorMetadata.METADATA_RETRIABLE))
                            ? RETRIABLE_FAILURE_THRESHOLD
                            : FAILURE_THRESHOLD;
            Integer sinceReminder =
                    repetitionsSinceReminder.computeIfPresent(
                            repetition.signature(),
                            (signature, seen) -> Math.min(SECOND_INTERVENTION_INTERVAL, seen + 1));
            if (count >= threshold
                    && (sinceReminder == null || sinceReminder >= SECOND_INTERVENTION_INTERVAL)
                    && pending == null
                    && interventions < MAX_INTERVENTIONS) {
                pending = new PendingReminder(domain, null, null, count, repetition.signature());
            }
        }

        void observeFailure(ToolResultEndEvent event, CallObservation call) {
            String domain =
                    event.getToolCallName() == null || event.getToolCallName().isBlank()
                            ? DEFAULT_DOMAIN
                            : event.getToolCallName();
            Map<String, Object> metadata = event.getMetadata();

            if (event.getState() == ToolResultState.SUCCESS) {
                // Success on an unrelated command/path is not recovery of this failure.
                String scope = stringValue(metadata, ToolErrorMetadata.METADATA_ERROR_SCOPE);
                Set<String> removed = new HashSet<>();
                window.removeIf(
                        failure -> {
                            boolean recovered =
                                    failure.domain.equals(domain)
                                            && ((call != null
                                                            && call.arguments != null
                                                            && failure.arguments != null)
                                                    ? call.arguments.equals(failure.arguments)
                                                    : java.util.Objects.equals(
                                                            scope, failure.scope));
                            if (recovered) {
                                removed.add(failure.signature());
                            }
                            return recovered;
                        });
                for (Failure failure : window) {
                    removed.remove(failure.signature());
                }
                failuresSinceReminder.keySet().removeAll(removed);
                if (pending != null
                        && pending.kind != null
                        && removed.contains(pending.signature)) {
                    pending = null;
                }
                return;
            }
            if (event.getState() != ToolResultState.ERROR
                    && event.getState() != ToolResultState.DENIED) {
                return;
            }

            String kind = stringValue(metadata, ToolErrorMetadata.METADATA_ERROR_KIND);
            if (kind == null) {
                if (event.getState() != ToolResultState.DENIED) {
                    return;
                }
                kind = DENIED_KIND;
            }
            String scope = stringValue(metadata, ToolErrorMetadata.METADATA_ERROR_SCOPE);
            boolean retriable =
                    Boolean.TRUE.equals(
                            booleanValue(metadata, ToolErrorMetadata.METADATA_RETRIABLE));

            Failure failure =
                    new Failure(domain, kind, scope, call == null ? null : call.arguments);
            String signature = failure.signature();
            if (window.size() == WINDOW_SIZE) {
                window.removeFirst();
                // Keep the intervention bookkeeping bounded to the failures still in the window.
                Set<String> live = new HashSet<>();
                for (Failure seen : window) {
                    live.add(seen.signature());
                }
                failuresSinceReminder.keySet().retainAll(live);
                if (pending != null && pending.kind != null && !live.contains(pending.signature)) {
                    pending = null;
                }
            }
            window.addLast(failure);

            int count = 0;
            for (Failure seen : window) {
                if (seen.signature().equals(signature)) {
                    count++;
                }
            }

            int threshold = retriable ? RETRIABLE_FAILURE_THRESHOLD : FAILURE_THRESHOLD;
            Integer sinceReminder =
                    failuresSinceReminder.computeIfPresent(
                            signature,
                            (key, seen) -> Math.min(SECOND_INTERVENTION_INTERVAL, seen + 1));
            if (count < threshold
                    || (sinceReminder != null && sinceReminder < SECOND_INTERVENTION_INTERVAL)
                    || pending != null
                    || interventions >= MAX_INTERVENTIONS) {
                return;
            }
            pending = new PendingReminder(domain, kind, scope, count, signature);
            log.debug(
                    "RepeatedFailureGuard: {} consecutive '{}' failures on {} (scope={})",
                    count,
                    kind,
                    domain,
                    scope);
        }

        Msg takeReminder() {
            PendingReminder reminder = pending;
            pending = null;
            if (reminder == null) {
                return null;
            }
            int count =
                    reminder.kind == null
                            ? (int)
                                    repetitions.stream()
                                            .filter(
                                                    seen ->
                                                            seen.signature()
                                                                    .equals(reminder.signature))
                                            .count()
                            : (int)
                                    window.stream()
                                            .filter(
                                                    seen ->
                                                            seen.signature()
                                                                    .equals(reminder.signature))
                                            .count();
            if (count < reminder.count) {
                return null;
            }
            interventions++;
            Map<String, Integer> progress =
                    reminder.kind == null ? repetitionsSinceReminder : failuresSinceReminder;
            // Count only occurrences after this actual injection, even if many results arrived
            // in the same acting batch before the reminder was consumed.
            progress.put(reminder.signature, 0);
            return new PendingReminder(
                            reminder.domain,
                            reminder.kind,
                            reminder.scope,
                            count,
                            reminder.signature)
                    .toMessage();
        }
    }

    /** Only hashes survive a result stream; raw arguments/results are never retained. */
    private static final class CallObservation {
        private final String arguments;
        private final MessageDigest output = digest();
        private boolean valid = true;

        CallObservation(ToolUseBlock call) {
            MessageDigest input = digest();
            arguments =
                    hashJson(call.getInput(), input)
                            ? HexFormat.of().formatHex(input.digest())
                            : null;
        }

        void text(String text) {
            if (text == null) {
                return;
            }
            // UTF-16 code units make hashing independent of text-delta chunk boundaries,
            // including a surrogate pair split across two deltas.
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                output.update((byte) (c >>> 8));
                output.update((byte) c);
            }
        }

        void data(Object data) {
            output.update((byte) 0xff);
            valid &= hashJson(data, output);
            output.update((byte) 0xff);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean hashJson(Object value, MessageDigest digest) {
        try {
            JSON.writeValue(new DigestOutputStream(OutputStream.nullOutputStream(), digest), value);
            return true;
        } catch (IOException | RuntimeException unsupported) {
            // Observation must never fail a tool call; skip unsupported values rather than
            // retaining them or deriving a potentially misleading fingerprint.
            return false;
        }
    }

    private record Repetition(String domain, String arguments, String output) {
        String signature() {
            return domain + '|' + arguments + '|' + output;
        }
    }

    private record Failure(String domain, String kind, String scope, String arguments) {
        String signature() {
            return scope == null ? domain + '|' + kind : domain + '|' + kind + '|' + scope;
        }
    }

    private record PendingReminder(
            String domain, String kind, String scope, int count, String signature) {
        private static final String PROVENANCE =
                "Automated reminder from AgentScope. This is context, not a user message or"
                        + " approval.";

        Msg toMessage() {
            String text = renderText(domain, kind, scope, count);
            return UserMessage.builder()
                    .content(
                            List.of(
                                    TextBlock.builder().text(PROVENANCE).build(),
                                    TextBlock.builder().text(text).build()))
                    .metadata(
                            Map.of(METADATA_SYNTHETIC, true, METADATA_REMINDER_KIND, REMINDER_KIND))
                    .build();
        }
    }

    static String renderText(String domain, String kind, String scope, int count) {
        String text =
                kind == null
                        ? "Tool '"
                                + domain
                                + "' returned the same result for identical arguments "
                                + count
                                + " times. This does not imply failure. Check whether another"
                                + " identical call will add evidence; inspect the result or change"
                                + " the approach if not."
                        : "Tool '"
                                + domain
                                + "' has failed "
                                + count
                                + " times with the same error ("
                                + kind
                                + (scope == null ? "" : ", scope: " + scope)
                                + "). "
                                + "Repeating the identical call is unlikely to succeed. "
                                + guidance(kind);
        // The -1 keeps the provenance sentence, the joining newline, and the reminder together
        // under MAX_REMINDER_CHARS.
        int budget = MAX_REMINDER_CHARS - PendingReminder.PROVENANCE.length() - 1;
        if (text.length() <= budget) {
            return text;
        }
        return text.substring(0, budget - 3) + "...";
    }

    private static String guidance(String kind) {
        String normalized = kind.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "permission_denied" ->
                    "Permission was denied: stop this operation. Do not bypass the denial by"
                            + " changing tools, identities, or paths. Report the blocker and seek"
                            + " explicit authorization before proceeding.";
            case "not_found" ->
                    "Verify that the path or identifier exists and is inside the allowed"
                            + " workspace, or choose another one before trying again.";
            case "command_failed" ->
                    "Inspect the exit code and diagnostic output, including any truncated output"
                            + " reference, then correct the command or its prerequisites before"
                            + " retrying.";
            case "invalid_argument" ->
                    "Re-read the tool schema and correct the offending argument instead of"
                            + " resending it unchanged.";
            case "conflict", "unsupported" ->
                    "The state changed or the operation is unavailable: re-read the current"
                            + " state or pick a supported alternative.";
            case "timeout", "rate_limited", "upstream_error" ->
                    "The dependency is transient: wait, degrade gracefully, or report the"
                            + " limitation instead of retrying immediately.";
            default ->
                    "Treat this failure as a fact: change the inputs, use another tool, or tell"
                            + " the user what is blocking the task.";
        };
    }

    private static String outputDigest(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get(METADATA_OUTPUT_SHA256);
        // Exact contract validation; no trimming, text heuristics, or lossy normalization.
        return value instanceof String text && text.matches("[0-9a-f]{64}") ? text : null;
    }

    private static String stringValue(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (!(value instanceof String text)) {
            return null;
        }
        String stripped = text.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static Boolean booleanValue(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value instanceof Boolean bool ? bool : null;
    }
}

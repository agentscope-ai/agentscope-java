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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolErrorMetadata;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
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
 * metadata contract are never second-guessed. A {@code DENIED} result without a kind is counted
 * as {@code permission_denied}, because denial is unambiguous.
 *
 * <p><b>Signature.</b> Failures are merged per {@code toolName|errorKind[|errorScope]}. Two
 * different files failing with {@code not_found} do not add up; the same file failing with
 * {@code not_found} does.
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
 * <p><b>Reset.</b> Counters are reset at the start of every agent call (a new user turn), on a
 * successful result for the same tool, and when the call finishes; per-session state is bounded
 * to {@value #MAX_TRACKED_SESSIONS} sessions and evicted least-recently-used first.
 */
public class RepeatedFailureGuard implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(RepeatedFailureGuard.class);

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
                ExtensionPoint.ON_REASONING,
                ExtensionPoint.ON_ACTING,
                ExtensionPoint.ON_AGENT_FINISH);
    }

    /** A new agent call is a new user turn: forget the previous turn's failures. */
    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        stateFor(ctx).reset();
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        SessionState state = stateFor(ctx);
        return next.apply(input)
                .doOnNext(
                        event -> {
                            if (event instanceof ToolResultEndEvent result) {
                                synchronized (state) {
                                    state.observe(result);
                                }
                            }
                        });
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
            reminder = state.takeReminder();
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
        stateFor(ctx).reset();
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

    /** Per-session failure window and pending reminder. Guarded by its own monitor. */
    private static final class SessionState {
        private final Deque<Failure> window = new ArrayDeque<>(WINDOW_SIZE);
        private final Map<String, Integer> lastInterventionCounts = new HashMap<>();
        private int interventions;
        private PendingReminder pending;

        void reset() {
            window.clear();
            lastInterventionCounts.clear();
            interventions = 0;
            pending = null;
        }

        void observe(ToolResultEndEvent event) {
            String domain =
                    event.getToolCallName() == null || event.getToolCallName().isBlank()
                            ? DEFAULT_DOMAIN
                            : event.getToolCallName();
            Map<String, Object> metadata = event.getMetadata();

            if (event.getState() == ToolResultState.SUCCESS) {
                // The same tool works now: earlier failures for it are no longer evidence of a
                // loop.
                window.removeIf(failure -> failure.domain.equals(domain));
                String prefix = domain + '|';
                lastInterventionCounts.keySet().removeIf(signature -> signature.startsWith(prefix));
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

            Failure failure = new Failure(domain, kind, scope);
            String signature = failure.signature();
            if (window.size() == WINDOW_SIZE) {
                window.removeFirst();
                // Keep the intervention bookkeeping bounded to the failures still in the window.
                Set<String> live = new HashSet<>();
                for (Failure seen : window) {
                    live.add(seen.signature());
                }
                lastInterventionCounts.keySet().retainAll(live);
            }
            window.addLast(failure);

            int count = 0;
            for (Failure seen : window) {
                if (seen.signature().equals(signature)) {
                    count++;
                }
            }

            int threshold = retriable ? RETRIABLE_FAILURE_THRESHOLD : FAILURE_THRESHOLD;
            Integer last = lastInterventionCounts.get(signature);
            int expected = last == null ? threshold : last + SECOND_INTERVENTION_INTERVAL;
            if (count < expected || interventions >= MAX_INTERVENTIONS) {
                return;
            }
            interventions++;
            lastInterventionCounts.put(signature, count);
            pending = new PendingReminder(domain, kind, scope, count);
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
            return reminder == null ? null : reminder.toMessage();
        }
    }

    private record Failure(String domain, String kind, String scope) {
        String signature() {
            return scope == null ? domain + '|' + kind : domain + '|' + kind + '|' + scope;
        }
    }

    private record PendingReminder(String domain, String kind, String scope, int count) {
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
                "Tool '"
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
            case "not_found", "permission_denied" ->
                    "Verify that the path or identifier exists and is inside the allowed"
                            + " workspace, or choose another one before trying again.";
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

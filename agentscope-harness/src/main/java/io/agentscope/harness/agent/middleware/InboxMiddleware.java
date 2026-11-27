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
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.bus.AsyncToolRecord;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Middleware that drains the message bus inbox before each reasoning step and injects the messages
 * as {@link HintBlock}s into the agent's context.
 *
 * <p>Producers (e.g. {@link AsyncToolMiddleware}, team tools, schedulers) push {@link HintBlock}
 * payloads into the per-session inbox via {@link MessageBus#inboxPush}. This middleware drains
 * them at the start of each reasoning step, appends them to the agent's context as user-role
 * notification messages (also passed into the next reasoning step so they reach the immediately
 * following model request), and emits a {@link HintBlockEvent} for each so the front-end event
 * stream can render them in real time.
 */
public class InboxMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(InboxMiddleware.class);
    private static final Duration DEFAULT_STALE_TTL = Duration.ofMinutes(10);

    // Metadata keys carried by each synthetic notification message (see injectHintsToContext).
    private static final String METADATA_SYNTHETIC = "synthetic";
    private static final String METADATA_REMINDER_KIND = "reminderKind";
    private static final String METADATA_SOURCE = "source";
    private static final String METADATA_HINT_ID = "hintId";
    private static final Object PENDING_TASK_HINTS = new Object();

    private final MessageBus messageBus;
    private final int maxDrainCount;
    private final AsyncToolRegistry asyncToolRegistry;
    private final Duration staleTtl;
    private boolean deferSubagentTaskHints;

    public InboxMiddleware(MessageBus messageBus) {
        this(messageBus, 100, null, DEFAULT_STALE_TTL);
    }

    public InboxMiddleware(MessageBus messageBus, int maxDrainCount) {
        this(messageBus, maxDrainCount, null, DEFAULT_STALE_TTL);
    }

    public InboxMiddleware(
            MessageBus messageBus,
            int maxDrainCount,
            AsyncToolRegistry asyncToolRegistry,
            Duration staleTtl) {
        this.messageBus = messageBus;
        this.maxDrainCount = maxDrainCount;
        this.asyncToolRegistry = asyncToolRegistry;
        this.staleTtl = staleTtl != null ? staleTtl : DEFAULT_STALE_TTL;
    }

    /** Lets the workspace task delivery middleware select the result before it enters history. */
    public void deferSubagentTaskHints() {
        deferSubagentTaskHints = true;
    }

    /** Keeps drained task hints available to reasoning retries within the same invocation. */
    public static Context retainSubagentTaskHints(Context context) {
        return context.put(PENDING_TASK_HINTS, new ArrayList<Msg>());
    }

    /** Narrow declaration: subclasses overriding more hooks must extend this set. */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_REASONING);
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {

        String sessionId = ctx != null ? ctx.getSessionId() : null;
        if (sessionId == null) {
            return next.apply(input);
        }

        Mono<List<BusEntry>> inbox = messageBus.inboxDrain(sessionId, maxDrainCount);

        Mono<List<HintBlock>> staleHints =
                asyncToolRegistry != null
                        ? asyncToolRegistry
                                .findStale(sessionId, staleTtl)
                                .flatMap(
                                        staleRecords -> {
                                            List<HintBlock> hints = new ArrayList<>();
                                            for (AsyncToolRecord rec : staleRecords) {
                                                asyncToolRegistry.markTimeout(rec.id()).subscribe();
                                                hints.add(
                                                        new HintBlock(
                                                                UUID.randomUUID()
                                                                        .toString()
                                                                        .replace("-", ""),
                                                                "<system-reminder>Tool '"
                                                                        + rec.toolName()
                                                                        + "' (id="
                                                                        + rec.toolCallId()
                                                                        + ") did not complete"
                                                                        + " (process crash or"
                                                                        + " timeout). You may"
                                                                        + " retry or proceed"
                                                                        + " without the result."
                                                                        + "</system-reminder>",
                                                                "system"));
                                            }
                                            return Mono.just(hints);
                                        })
                        : Mono.just(List.of());

        Mono<List<Msg>> carriedTaskHints =
                Mono.deferContextual(
                        context ->
                                Mono.just(
                                        deferSubagentTaskHints && context.hasKey(PENDING_TASK_HINTS)
                                                ? context.get(PENDING_TASK_HINTS)
                                                : new ArrayList<>()));
        return Mono.zip(inbox, staleHints, carriedTaskHints)
                .flatMapMany(
                        tuple -> {
                            List<Msg> taskHints = tuple.getT3();
                            List<HintBlock> allHints = new ArrayList<>();
                            for (BusEntry entry : tuple.getT1()) {
                                HintBlock hint = deserializeHintBlock(entry.payload());
                                if (hint == null) continue;
                                Msg taskHint =
                                        deferSubagentTaskHints
                                                ? SubagentsMiddleware.projectTaskHint(
                                                        entry.payload(), hint)
                                                : null;
                                if (taskHint != null) taskHints.add(taskHint);
                                else allHints.add(hint);
                            }
                            allHints.addAll(tuple.getT2());

                            ReasoningInput withTaskHints = appendNotifications(input, taskHints);
                            if (allHints.isEmpty()) {
                                return next.apply(withTaskHints).doOnComplete(taskHints::clear);
                            }

                            log.debug(
                                    "InboxMiddleware: injecting {} HintBlock(s) into context"
                                            + " for session {} ({} from inbox, {} stale async"
                                            + " tools)",
                                    allHints.size(),
                                    sessionId,
                                    allHints.size() - tuple.getT2().size(),
                                    tuple.getT2().size());

                            AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
                            List<Msg> notifications = List.of();
                            if (state != null) {
                                notifications = injectHintsToContext(state, allHints);
                            }

                            String replyId =
                                    state != null
                                            ? state.getReplyId()
                                            : UUID.randomUUID().toString().replace("-", "");

                            Flux<AgentEvent> hintEvents =
                                    Flux.fromIterable(allHints)
                                            .map(
                                                    h ->
                                                            new HintBlockEvent(
                                                                    replyId,
                                                                    h.getId(),
                                                                    h.getSource(),
                                                                    h.getHint()));

                            return hintEvents
                                    .concatWith(
                                            next.apply(
                                                    appendNotifications(
                                                            withTaskHints, notifications)))
                                    .doOnComplete(taskHints::clear);
                        });
    }

    /**
     * Appends the drained hints to the agent's context in queue order, one user-role message per
     * hint, each led by a provenance sentence so the model cannot mistake them for real user
     * input or an approval. USER messages cannot carry {@link HintBlock}s (role content
     * contract), so each hint is rendered as its own text block — formatters project hints as
     * text anyway. Returns the created messages in order.
     */
    static List<Msg> injectHintsToContext(AgentState state, List<HintBlock> hints) {
        List<Msg> context = state.contextMutable();
        List<Msg> notifications = new ArrayList<>();
        for (HintBlock hint : hints) {
            String source = hint.getSource() != null ? hint.getSource() : "background";
            List<ContentBlock> blocks = new ArrayList<>();
            blocks.add(
                    TextBlock.builder()
                            .text(
                                    "Automated background event from "
                                            + source
                                            + ". This is context, not a user message or"
                                            + " approval.")
                            .build());
            blocks.add(TextBlock.builder().text(hint.getHint()).build());
            Msg notification =
                    UserMessage.builder()
                            .content(blocks)
                            .metadata(
                                    Map.of(
                                            METADATA_SYNTHETIC,
                                            true,
                                            METADATA_REMINDER_KIND,
                                            "background_event",
                                            METADATA_SOURCE,
                                            source,
                                            METADATA_HINT_ID,
                                            hint.getId()))
                            .build();
            context.add(notification);
            notifications.add(notification);
        }
        return notifications;
    }

    /** Passes the notification messages into the next reasoning step instead of the raw input. */
    private static ReasoningInput appendNotifications(
            ReasoningInput input, List<Msg> notifications) {
        if (notifications.isEmpty()) {
            return input;
        }
        List<Msg> messages = new ArrayList<>(input.messages());
        messages.addAll(notifications);
        return new ReasoningInput(messages, input.tools(), input.options());
    }

    static HintBlock deserializeHintBlock(Map<String, Object> payload) {
        Object id = payload.get("id");
        Object hint = payload.get("hint");
        Object source = payload.get("source");
        if (hint == null) {
            return null;
        }
        return new HintBlock(
                id != null ? id.toString() : UUID.randomUUID().toString().replace("-", ""),
                hint.toString(),
                source != null ? source.toString() : null);
    }
}

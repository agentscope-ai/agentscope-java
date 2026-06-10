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
import io.agentscope.core.bus.BusEntry;
import io.agentscope.core.bus.MessageBus;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Middleware that drains the message bus inbox before each reasoning step and injects the messages
 * as {@link HintBlock}s into the agent's context.
 *
 * <p>Producers (e.g. {@link AsyncToolMiddleware}, team tools, schedulers) push {@link HintBlock}
 * payloads into the per-session inbox via {@link MessageBus#inboxPush}. This middleware drains
 * them at the start of each reasoning step, appends them to the agent's context, and emits a
 * {@link HintBlockEvent} for each so the front-end event stream can render them in real time.
 */
public class InboxMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(InboxMiddleware.class);

    private final MessageBus messageBus;
    private final int maxDrainCount;

    public InboxMiddleware(MessageBus messageBus) {
        this(messageBus, 100);
    }

    public InboxMiddleware(MessageBus messageBus, int maxDrainCount) {
        this.messageBus = messageBus;
        this.maxDrainCount = maxDrainCount;
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

        return messageBus
                .inboxDrain(sessionId, maxDrainCount)
                .flatMapMany(
                        entries -> {
                            if (entries.isEmpty()) {
                                return next.apply(input);
                            }

                            List<HintBlock> hints = new ArrayList<>(entries.size());
                            for (BusEntry entry : entries) {
                                HintBlock hint = deserializeHintBlock(entry.payload());
                                if (hint != null) {
                                    hints.add(hint);
                                }
                            }

                            if (hints.isEmpty()) {
                                return next.apply(input);
                            }

                            log.debug(
                                    "InboxMiddleware: injecting {} HintBlock(s) into context"
                                            + " for session {}",
                                    hints.size(),
                                    sessionId);

                            AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
                            if (state != null) {
                                injectHintsToContext(state, hints, agent.getName());
                            }

                            String replyId =
                                    state != null
                                            ? state.getReplyId()
                                            : UUID.randomUUID().toString().replace("-", "");

                            Flux<AgentEvent> hintEvents =
                                    Flux.fromIterable(hints)
                                            .map(
                                                    h ->
                                                            new HintBlockEvent(
                                                                    replyId,
                                                                    h.getId(),
                                                                    h.getSource(),
                                                                    h.getHint()));

                            return hintEvents.concatWith(next.apply(input));
                        });
    }

    /**
     * Inject hint blocks into the agent's context. Appends to the last assistant message's content
     * list if present; otherwise creates a new assistant message.
     */
    static void injectHintsToContext(AgentState state, List<HintBlock> hints, String agentName) {
        List<Msg> context = state.contextMutable();
        if (!context.isEmpty()) {
            Msg last = context.get(context.size() - 1);
            if (last.getRole() == MsgRole.ASSISTANT
                    && agentName != null
                    && agentName.equals(last.getName())) {
                List<ContentBlock> extended = new ArrayList<>(last.getContent());
                extended.addAll(hints);
                Msg updatedMsg =
                        Msg.builder()
                                .id(last.getId())
                                .name(last.getName())
                                .role(MsgRole.ASSISTANT)
                                .content(extended)
                                .build();
                context.set(context.size() - 1, updatedMsg);
                return;
            }
        }
        Msg hintMsg =
                AssistantMessage.builder().name(agentName).content(new ArrayList<>(hints)).build();
        context.add(hintMsg);
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

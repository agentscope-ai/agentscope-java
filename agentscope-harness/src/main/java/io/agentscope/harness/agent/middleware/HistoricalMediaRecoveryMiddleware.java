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
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ModelMediaException;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Recovers a Harness conversation when a model provider can no longer fetch URL-backed media from
 * historical messages.
 *
 * <p>The current call's messages are never modified. On a classified media-unavailable failure,
 * historical URL-backed media is replaced with text and reasoning is retried exactly once. The
 * canonical {@link AgentState} is updated only after that retry completes successfully.
 */
public final class HistoricalMediaRecoveryMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log =
            LoggerFactory.getLogger(HistoricalMediaRecoveryMiddleware.class);

    private final HistoricalMediaRecoveryConfig config;

    public HistoricalMediaRecoveryMiddleware(HistoricalMediaRecoveryConfig config) {
        this.config = config != null ? config : HistoricalMediaRecoveryConfig.defaults();
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (ctx == null) {
            return next.apply(input);
        }
        return Flux.defer(
                () -> {
                    CurrentInputMessageIds previous = ctx.get(CurrentInputMessageIds.class);
                    CurrentInputMessageIds current = currentInputMessageIds(input.msgs());
                    ctx.put(CurrentInputMessageIds.class, current);
                    return next.apply(input)
                            .doFinally(ignored -> ctx.put(CurrentInputMessageIds.class, previous));
                });
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    CurrentInputMessageIds current =
                            ctx != null ? ctx.get(CurrentInputMessageIds.class) : null;
                    if (current == null || !current.safeToRecover()) {
                        return next.apply(input);
                    }

                    AtomicBoolean sawStart = new AtomicBoolean(false);
                    AtomicBoolean sawOutput = new AtomicBoolean(false);
                    return Flux.defer(() -> next.apply(input))
                            .doOnNext(
                                    event -> {
                                        if (event instanceof ModelCallStartEvent) {
                                            sawStart.set(true);
                                        } else {
                                            sawOutput.set(true);
                                        }
                                    })
                            .onErrorResume(
                                    error -> {
                                        if (sawOutput.get() || !isMediaUnavailable(error)) {
                                            return Flux.error(error);
                                        }
                                        SanitizationResult sanitized =
                                                sanitizeMessages(
                                                        input.messages(), current.ids(), null);
                                        if (!sanitized.changed()) {
                                            return Flux.error(error);
                                        }

                                        String sessionId =
                                                ctx != null && ctx.getSessionId() != null
                                                        ? ctx.getSessionId()
                                                        : "default";
                                        log.warn(
                                                "[{}] Retrying after replacing {} unavailable"
                                                        + " historical media block(s) for session"
                                                        + " {}",
                                                agent.getName(),
                                                sanitized.replacementCount(),
                                                sessionId);

                                        ReasoningInput retryInput =
                                                new ReasoningInput(
                                                        sanitized.messages(),
                                                        input.tools(),
                                                        input.options());
                                        return Flux.defer(() -> next.apply(retryInput))
                                                .filter(
                                                        event ->
                                                                !(sawStart.get()
                                                                        && event
                                                                                instanceof
                                                                                ModelCallStartEvent))
                                                .doOnComplete(
                                                        () -> {
                                                            commitSanitizedHistory(
                                                                    agent,
                                                                    ctx,
                                                                    current.ids(),
                                                                    sanitized.changedMessageIds());
                                                            log.info(
                                                                    "[{}] Recovered unavailable"
                                                                            + " historical media"
                                                                            + " for session {}",
                                                                    agent.getName(),
                                                                    sessionId);
                                                        })
                                                .doOnError(
                                                        retryError ->
                                                                log.warn(
                                                                        "[{}] Historical media"
                                                                            + " recovery retry"
                                                                            + " failed for session"
                                                                            + " {}",
                                                                        agent.getName(),
                                                                        sessionId));
                                    });
                });
    }

    private CurrentInputMessageIds currentInputMessageIds(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return new CurrentInputMessageIds(Set.of(), true);
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Msg message : messages) {
            if (message == null || message.getId() == null) {
                return new CurrentInputMessageIds(Set.of(), false);
            }
            ids.add(message.getId());
        }
        return new CurrentInputMessageIds(Set.copyOf(ids), true);
    }

    private boolean isMediaUnavailable(Throwable error) {
        IdentityHashMap<Throwable, Boolean> visited = new IdentityHashMap<>();
        Throwable current = error;
        while (current != null && visited.put(current, Boolean.TRUE) == null) {
            if (current instanceof ModelMediaException mediaError
                    && mediaError.isMediaUnavailable()) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void commitSanitizedHistory(
            Agent agent,
            RuntimeContext ctx,
            Set<String> currentInputIds,
            Set<String> changedMessageIds) {
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        if (state == null || changedMessageIds.isEmpty()) {
            return;
        }
        List<Msg> context = state.contextMutable();
        SanitizationResult result = sanitizeMessages(context, currentInputIds, changedMessageIds);
        if (!result.changed()) {
            return;
        }
        for (int i = 0; i < context.size(); i++) {
            context.set(i, result.messages().get(i));
        }
    }

    private SanitizationResult sanitizeMessages(
            List<Msg> messages, Set<String> currentInputIds, Set<String> allowedMessageIds) {
        if (messages == null || messages.isEmpty()) {
            return new SanitizationResult(
                    messages != null ? messages : List.of(), false, 0, Set.of());
        }

        List<Msg> rebuiltMessages = new ArrayList<>(messages.size());
        Set<String> changedMessageIds = new LinkedHashSet<>();
        int replacementCount = 0;
        for (Msg message : messages) {
            if (message == null
                    || currentInputIds.contains(message.getId())
                    || (allowedMessageIds != null
                            && !allowedMessageIds.contains(message.getId()))) {
                rebuiltMessages.add(message);
                continue;
            }
            BlockSanitization sanitized = sanitizeBlocks(message.getContent());
            if (!sanitized.changed()) {
                rebuiltMessages.add(message);
                continue;
            }
            rebuiltMessages.add(rebuildMessage(message, sanitized.blocks()));
            changedMessageIds.add(message.getId());
            replacementCount += sanitized.replacementCount();
        }
        boolean changed = !changedMessageIds.isEmpty();
        return new SanitizationResult(
                changed ? rebuiltMessages : messages,
                changed,
                replacementCount,
                changed ? Set.copyOf(changedMessageIds) : Set.of());
    }

    private BlockSanitization sanitizeBlocks(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return new BlockSanitization(blocks != null ? blocks : List.of(), false, 0);
        }
        List<ContentBlock> rebuilt = new ArrayList<>(blocks.size());
        boolean changed = false;
        int replacementCount = 0;
        for (ContentBlock block : blocks) {
            if (isUrlBackedMedia(block)) {
                rebuilt.add(TextBlock.builder().text(config.getReplacementText()).build());
                changed = true;
                replacementCount++;
                continue;
            }
            if (block instanceof ToolResultBlock toolResult) {
                BlockSanitization nested = sanitizeBlocks(toolResult.getOutput());
                if (nested.changed()) {
                    rebuilt.add(
                            new ToolResultBlock(
                                    toolResult.getId(),
                                    toolResult.getName(),
                                    nested.blocks(),
                                    toolResult.getMetadata(),
                                    toolResult.getState()));
                    changed = true;
                    replacementCount += nested.replacementCount();
                    continue;
                }
            }
            rebuilt.add(block);
        }
        return new BlockSanitization(changed ? rebuilt : blocks, changed, replacementCount);
    }

    private boolean isUrlBackedMedia(ContentBlock block) {
        return block instanceof ImageBlock image && image.getSource() instanceof URLSource
                || block instanceof AudioBlock audio && audio.getSource() instanceof URLSource
                || block instanceof VideoBlock video && video.getSource() instanceof URLSource
                || block instanceof DataBlock data && data.getSource() instanceof URLSource;
    }

    private Msg rebuildMessage(Msg message, List<ContentBlock> content) {
        return Msg.builderForRole(message.getRole())
                .id(message.getId())
                .name(message.getName())
                .content(content)
                .metadata(message.getMetadata())
                .timestamp(message.getTimestamp())
                .usage(message.getUsage())
                .build();
    }

    private record CurrentInputMessageIds(Set<String> ids, boolean safeToRecover) {}

    private record BlockSanitization(
            List<ContentBlock> blocks, boolean changed, int replacementCount) {}

    private record SanitizationResult(
            List<Msg> messages,
            boolean changed,
            int replacementCount,
            Set<String> changedMessageIds) {}
}

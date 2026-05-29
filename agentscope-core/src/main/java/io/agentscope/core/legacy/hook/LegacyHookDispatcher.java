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
package io.agentscope.core.legacy.hook;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Encapsulates all legacy {@link HookEvent} construction and dispatch logic.
 *
 * <p>This class exists solely to keep the {@link ReActAgent} core loop clean and focused on the
 * 2.0 {@link io.agentscope.core.middleware.MiddlewareBase} / {@link io.agentscope.core.event.AgentEvent}
 * path. All methods here delegate to the agent's registered {@link Hook} list.
 *
 * @deprecated since 2.0.0, for removal. Will be deleted together with the Hook system.
 */
@Deprecated(forRemoval = true, since = "2.0.0")
@SuppressWarnings("deprecation")
public final class LegacyHookDispatcher {

    private final ReActAgent agent;

    public LegacyHookDispatcher(ReActAgent agent) {
        this.agent = agent;
    }

    // ==================== Generic dispatch ====================

    public <T extends HookEvent> Mono<T> fire(T event) {
        Mono<T> result = Mono.just(event);
        for (Hook hook : agent.getSortedHooks()) {
            result = result.flatMap(hook::onEvent);
        }
        return result;
    }

    // ==================== Reasoning hooks ====================

    public Mono<PreReasoningEvent> firePreReasoning(
            List<Msg> msgs, Msg systemMsg, String modelName) {
        PreReasoningEvent event = new PreReasoningEvent(agent, modelName, null, msgs);
        event.setSystemMessage(systemMsg);
        return fire(event);
    }

    public Mono<PostReasoningEvent> firePostReasoning(Msg msg, String modelName) {
        return fire(new PostReasoningEvent(agent, modelName, null, msg));
    }

    public Mono<Void> fireReasoningChunk(Msg chunkMsg, ReasoningContext context, String modelName) {
        ContentBlock content = chunkMsg.getFirstContentBlock();

        ContentBlock accumulatedContent = null;
        if (content instanceof TextBlock) {
            accumulatedContent = TextBlock.builder().text(context.getAccumulatedText()).build();
        } else if (content instanceof ThinkingBlock) {
            accumulatedContent =
                    ThinkingBlock.builder().thinking(context.getAccumulatedThinking()).build();
        } else if (content instanceof ToolUseBlock tub) {
            ToolUseBlock accumulated = context.getAccumulatedToolCall(tub.getId());
            accumulatedContent = accumulated != null ? accumulated : tub;
        }

        if (accumulatedContent != null) {
            Msg accumulated =
                    Msg.builder()
                            .id(chunkMsg.getId())
                            .name(chunkMsg.getName())
                            .role(chunkMsg.getRole())
                            .content(accumulatedContent)
                            .build();
            if (context.getChatUsage() != null) {
                accumulated
                        .getMetadata()
                        .put(MessageMetadataKeys.CHAT_USAGE, context.getChatUsage());
            }
            ReasoningChunkEvent event =
                    new ReasoningChunkEvent(agent, modelName, null, chunkMsg, accumulated);
            return Flux.fromIterable(agent.getSortedHooks())
                    .flatMap(hook -> hook.onEvent(event))
                    .then();
        }

        return Mono.empty();
    }

    // ==================== Acting hooks ====================

    public Mono<List<ToolUseBlock>> firePreActing(List<ToolUseBlock> toolCalls, Toolkit toolkit) {
        return Flux.fromIterable(toolCalls)
                .concatMap(tool -> fire(new PreActingEvent(agent, toolkit, tool)))
                .map(PreActingEvent::getToolUse)
                .collectList();
    }

    public Mono<PostActingEvent> firePostActing(
            ToolUseBlock toolUse, ToolResultBlock result, Toolkit toolkit, Msg toolMsg) {
        PostActingEvent event = new PostActingEvent(agent, toolkit, toolUse, result);
        event.setToolResultMsg(toolMsg);
        return fire(event);
    }

    public Mono<Void> fireActingChunk(
            ToolUseBlock toolUse, ToolResultBlock chunk, Toolkit toolkit) {
        ActingChunkEvent event =
                new ActingChunkEvent(
                        agent,
                        toolkit,
                        toolUse,
                        chunk.withIdAndName(toolUse.getId(), toolUse.getName()));
        return Flux.fromIterable(agent.getSortedHooks())
                .flatMap(hook -> hook.onEvent(event))
                .then();
    }

    // ==================== Summary hooks ====================

    public Mono<PreSummaryEvent> firePreSummary(
            List<Msg> msgs,
            GenerateOptions generateOptions,
            String modelName,
            int maxIters,
            Msg systemMsg) {
        PreSummaryEvent event =
                new PreSummaryEvent(agent, modelName, generateOptions, msgs, maxIters, maxIters);
        event.setSystemMessage(systemMsg);
        return fire(event);
    }

    public Mono<PostSummaryEvent> firePostSummary(
            Msg msg, GenerateOptions generateOptions, String modelName) {
        return fire(new PostSummaryEvent(agent, modelName, generateOptions, msg));
    }

    public Mono<Void> fireSummaryChunk(
            Msg chunkMsg,
            ReasoningContext context,
            GenerateOptions generateOptions,
            String modelName) {
        ContentBlock content = chunkMsg.getFirstContentBlock();

        ContentBlock accumulatedContent = null;
        if (content instanceof TextBlock) {
            accumulatedContent = TextBlock.builder().text(context.getAccumulatedText()).build();
        } else if (content instanceof ThinkingBlock) {
            accumulatedContent =
                    ThinkingBlock.builder().thinking(context.getAccumulatedThinking()).build();
        }

        if (accumulatedContent != null) {
            Msg accumulated =
                    Msg.builder()
                            .id(chunkMsg.getId())
                            .name(chunkMsg.getName())
                            .role(chunkMsg.getRole())
                            .content(accumulatedContent)
                            .build();
            if (context.getChatUsage() != null) {
                accumulated
                        .getMetadata()
                        .put(MessageMetadataKeys.CHAT_USAGE, context.getChatUsage());
            }
            SummaryChunkEvent event =
                    new SummaryChunkEvent(agent, modelName, generateOptions, chunkMsg, accumulated);
            return Flux.fromIterable(agent.getSortedHooks())
                    .flatMap(hook -> hook.onEvent(event))
                    .then();
        }

        return Mono.empty();
    }
}

/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.adapter;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agui.adapter.strategy.BlockEventConverter;
import io.agentscope.core.agui.adapter.strategy.TextBlockConverter;
import io.agentscope.core.agui.adapter.strategy.ThinkingBlockConverter;
import io.agentscope.core.agui.adapter.strategy.ToolResultBlockConverter;
import io.agentscope.core.agui.adapter.strategy.ToolUseBlockConverter;
import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;

/**
 * Adapter that bridges AgentScope agents to the AG-UI protocol.
 *
 * <p>This adapter converts AG-UI protocol inputs to AgentScope messages,
 * invokes the agent, and converts the streaming events back to AG-UI events.
 *
 * <p><b>Event Mapping:</b>
 * <ul>
 *   <li>AgentScope REASONING/SUMMARY events → AG-UI TEXT_MESSAGE_* events (for TextBlock)</li>
 *   <li>AgentScope REASONING/SUMMARY events → AG-UI REASONING_* events (for ThinkingBlock)</li>
 *   <li>AgentScope TOOL_RESULT events → AG-UI TOOL_CALL_END events</li>
 *   <li>ToolUseBlock content → AG-UI TOOL_CALL_START events</li>
 * </ul>
 *
 * <p><b>Reasoning Support:</b>
 * <ul>
 *   <li>ThinkingBlock content is converted to REASONING_* events</li>
 *   <li>Reasoning output is disabled by default (enableReasoning=false) for backward compatibility</li>
 *   <li>Set enableReasoning=true in AguiAdapterConfig to enable reasoning events</li>
 * </ul>
 */
public class AguiAgentAdapter {

    private final Agent agent;
    private final AguiAdapterConfig config;
    private final AguiMessageConverter messageConverter;
    private final Map<Class<?>, BlockEventConverter<?>> converters = new HashMap<>();

    /**
     * Creates a new AguiAgentAdapter and registers all block conversion strategies.
     *
     * @param agent  The agent to adapt
     * @param config The adapter configuration
     */
    public AguiAgentAdapter(Agent agent, AguiAdapterConfig config) {
        this.agent = Objects.requireNonNull(agent, "agent cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.messageConverter = new AguiMessageConverter();

        // Register default block conversion strategies
        converters.put(TextBlock.class, new TextBlockConverter());
        converters.put(ThinkingBlock.class, new ThinkingBlockConverter());
        converters.put(ToolUseBlock.class, new ToolUseBlockConverter());
        converters.put(ToolResultBlock.class, new ToolResultBlockConverter());

        // Override with custom converters if provided by the user
        if (!config.getCustomConverters().isEmpty()) {
            converters.putAll(config.getCustomConverters());
        }
    }

    /**
     * Run the agent with AG-UI protocol input.
     *
     * <p>This method converts the input messages, invokes the agent's streaming API,
     * and emits AG-UI protocol events.
     *
     * @param input The AG-UI run input
     * @return A Flux of AG-UI events
     */
    public Flux<AguiEvent> run(RunAgentInput input) {
        return Flux.defer(
                () -> {
                    String threadId = input.getThreadId();
                    String runId = input.getRunId();

                    // Convert AG-UI messages to AgentScope messages
                    List<Msg> msgs = messageConverter.toMsgList(input.getMessages());

                    // Create stream options - use incremental mode for true streaming
                    StreamOptions options =
                            StreamOptions.builder()
                                    .eventTypes(EventType.ALL)
                                    .incremental(true)
                                    .includeActingChunk(config.isEnableActingChunk())
                                    .build();

                    StreamContext ctx = new StreamContext(threadId, runId, config);

                    return Flux.concat(
                                    // Emit RUN_STARTED
                                    Flux.just(new AguiEvent.RunStarted(threadId, runId)),

                                    // Stream agent events and convert to AG-UI events
                                    // Use concatMapIterable to preserve strict event ordering
                                    agent.stream(msgs, options)
                                            .concatMapIterable(event -> processEvent(event, ctx)),

                                    // Emit any pending end events
                                    Flux.defer(
                                            () ->
                                                    Flux.fromIterable(
                                                            ctx.flushAllRemainingDeferred())),

                                    // Emit RUN_FINISHED
                                    Flux.just(new AguiEvent.RunFinished(threadId, runId)))
                            .onErrorResume(error -> handleError(threadId, runId, ctx, error));
                });
    }

    /**
     * Dispatches the incoming event to the appropriate converter strategies based on block types.
     *
     * @param event The incoming agent event
     * @param ctx   The current stream context
     * @return A list of AG-UI events generated during this processing cycle
     */
    @SuppressWarnings("unchecked")
    private List<AguiEvent> processEvent(Event event, StreamContext ctx) {
        // Dispatch each content block to its corresponding converter
        for (ContentBlock block : event.getMessage().getContent()) {
            BlockEventConverter<ContentBlock> converter =
                    (BlockEventConverter<ContentBlock>) converters.get(block.getClass());

            if (converter != null && converter.isApplicable(event)) {
                converter.convert(block, event, ctx);
            }
        }

        return ctx.getAndClearEmittedEvents();
    }

    /**
     * Handles errors that occur during the stream pipeline.
     * Guarantees that all deferred end events are flushed before the error event is emitted.
     *
     * @param threadId The thread ID
     * @param runId    The run ID
     * @param ctx      The current stream context
     * @param error    The thrown exception
     * @return A Flux containing the fallback closure events
     */
    private Flux<AguiEvent> handleError(
            String threadId, String runId, StreamContext ctx, Throwable error) {
        List<AguiEvent> events = new ArrayList<>();
        events.addAll(ctx.flushAllRemainingDeferred());

        String msg =
                error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        events.add(new AguiEvent.Raw(threadId, runId, Map.of("error", msg)));
        events.add(new AguiEvent.RunFinished(threadId, runId));

        return Flux.fromIterable(events);
    }
}

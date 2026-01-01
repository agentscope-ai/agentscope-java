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
package io.agentscope.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Interface for all agents in the AgentScope framework.
 *
 * <p>This interface defines the core contract for agents, including basic properties and
 * capabilities. Agents process messages and can be monitored/intercepted using hooks.
 *
 * <p>Design Philosophy:
 * <ul>
 *   <li>Memory management is NOT part of the core Agent interface - it's the responsibility
 *       of specific agent implementations (e.g., ReActAgent)</li>
 *   <li>Structured output is a specialized capability provided by specific agents</li>
 *   <li>Observe pattern allows agents to receive messages without generating a reply,
 *       enabling multi-agent collaboration</li>
 * </ul>
 */
public interface Agent {

    /**
     * Get the unique identifier for this agent.
     *
     * @return Agent ID
     */
    String getAgentId();

    /**
     * Get the name of this agent.
     *
     * @return Agent name
     */
    String getName();

    /**
     * Get the description of this agent.
     *
     * @return Agent description
     */
    default String getDescription() {
        return "Agent(" + getAgentId() + ") " + getName();
    }

    /**
     * Process a single input message and generate a response.
     *
     * @param msg Input message
     * @return Response message
     */
    default Mono<Msg> call(Msg msg) {
        return call(msg == null ? List.of() : List.of(msg));
    }

    /**
     * Process a list of input messages and generate a response.
     *
     * @param msgs Input messages
     * @return Response message
     */
    Mono<Msg> call(List<Msg> msgs);

    /**
     * Continue generation based on current state without adding new input.
     * This allows the agent to continue generating responses based on existing context.
     *
     * @return Response message
     */
    default Mono<Msg> call() {
        return call(List.of());
    }

    /**
     * Process a single input message with structured model and generate a response.
     *
     * <p>The structured model parameter defines the expected structure of input or output data.
     * For UserAgent, this enables structured input collection from users. For other agents,
     * this can be used to request structured output from LLMs.
     *
     * <p>The structured data will be stored in the returned message's metadata field and can be
     * extracted using {@link Msg#getStructuredData(Class)}.
     *
     * <p>Default implementation ignores the structuredModel parameter. Agents that support
     * structured input/output should override this method.
     *
     * @param msg Input message
     * @param structuredModel Optional class defining the structure (e.g., a POJO class)
     * @return Response message with structured data in metadata
     */
    default Mono<Msg> call(Msg msg, Class<?> structuredModel) {
        return call(msg == null ? List.of() : List.of(msg), structuredModel);
    }

    /**
     * Process a single input message with structured model and generate a response.
     *
     * <p>The structured model parameter defines the expected structure of output data.
     * Not support UserAgent
     *
     * <p>The structured data will be stored in the returned message's metadata field and can be
     * extracted using {@link Msg#getStructuredData(boolean mutable)}.
     *
     * <p>Default implementation ignores the schemaDesc parameter. Agents that support
     * structured output should override this method.
     *
     * @param msg Input message
     * @param schemaDesc A com.fasterxml.jackson.databind.JsonNode instance defining the structure (e.g., a com.fasterxml.jackson.databind.JsonNode instance)
     * @return Response message with structured data in metadata
     */
    default Mono<Msg> call(Msg msg, JsonNode schemaDesc) {
        return call(msg == null ? List.of() : List.of(msg), schemaDesc);
    }

    /**
     * Process multiple input messages with structured model and generate a response.
     *
     * <p>The structured model parameter defines the expected structure of input or output data.
     * The structured data will be stored in the returned message's metadata field.
     *
     * <p>Default implementation ignores the structuredModel parameter. Agents that support
     * structured input/output should override this method.
     *
     * @param msgs Input messages
     * @param structuredModel Optional class defining the structure
     * @return Response message with structured data in metadata
     */
    Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel);

    Mono<Msg> call(List<Msg> msgs, JsonNode schemaDesc);

    /**
     * Continue generation with structured model based on current state.
     *
     * <p>The structured model parameter defines the expected structure of output data.
     * The structured data will be stored in the returned message's metadata field.
     *
     * @param structuredModel Optional class defining the structure
     * @return Response message with structured data in metadata
     */
    default Mono<Msg> call(Class<?> structuredModel) {
        return call(List.of(), structuredModel);
    }

    /**
     * Observe a message without generating a reply.
     * This allows agents to receive messages from other agents or the environment
     * without responding. It's commonly used in multi-agent collaboration scenarios.
     *
     * <p>Implementation patterns:
     * <ul>
     *   <li>UserAgent: Empty implementation (doesn't need to observe)</li>
     *   <li>ReActAgent: Add message to memory for context in future calls</li>
     * </ul>
     *
     * @param msg The message to observe
     * @return Mono that completes when observation is done
     */
    Mono<Void> observe(Msg msg);

    /**
     * Observe multiple messages without generating a reply.
     * This allows agents to receive multiple messages from other agents or the environment
     * without responding. It's commonly used in multi-agent collaboration scenarios.
     *
     * @param msgs The messages to observe
     * @return Mono that completes when all observations are done
     */
    Mono<Void> observe(List<Msg> msgs);

    /**
     * Interrupt the current agent execution.
     * This method sets an interrupt flag that will be checked by the agent at appropriate
     * checkpoints during execution. The interruption is cooperative and may not take effect
     * immediately.
     */
    void interrupt();

    /**
     * Interrupt the current agent execution with a user message.
     * This method sets an interrupt flag and associates a user message with the interruption.
     * The interruption is cooperative and may not take effect immediately.
     *
     * @param msg User message associated with the interruption
     */
    void interrupt(Msg msg);

    /**
     * Stream execution events in real-time as the agent processes the input.
     *
     * <p>Returns a Flux of {@link Event} objects representing different stages
     * of the agent's reasoning-acting loop. Each event contains the message content
     * and metadata about the execution stage.
     *
     * <p><b>Event Types:</b>
     * <ul>
     *   <li>{@link EventType#REASONING} - Agent thinking and planning</li>
     *   <li>{@link EventType#TOOL_RESULT} - Tool execution results</li>
     *   <li>{@link EventType#HINT} - RAG/memory/planning hints</li>
     *   <li>{@link EventType#SUMMARY} - Summary when max iterations reached</li>
     *   <li>{@link EventType#AGENT_RESULT} - Final result (opt-in via options)</li>
     * </ul>
     *
     * <p><b>Streaming Behavior:</b>
     * For streaming content (e.g., LLM streaming output), multiple events with the
     * same message ID will be emitted. Use {@link Event#isLast()} to detect
     * when a complete message has been received:
     * <pre>{@code
     * agent.stream(msg, options)
     *     .subscribe(event -> {
     *         if (event.isLast()) {
     *             // Complete message - safe to persist or process
     *             database.save(event.getMessage());
     *         } else {
     *             // Intermediate chunk - update UI
     *             ui.append(event.getMessage().getTextContent());
     *         }
     *     });
     * }</pre>
     *
     * <p><b>Filtering:</b>
     * Use {@link StreamOptions} to filter event types at the framework level:
     * <pre>{@code
     * StreamOptions options = StreamOptions.builder()
     *     .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
     *     .chunkMode(ChunkMode.INCREMENTAL)
     *     .build();
     *
     * agent.stream(msg, options)
     *     .subscribe(event -> {
     *         // Only receives REASONING and TOOL_RESULT events
     *     });
     * }</pre>
     *
     * @param msg Input message
     * @param options Stream configuration options
     * @return Flux of events emitted during execution
     */
    Flux<Event> stream(Msg msg, StreamOptions options);

    /**
     * Stream with default options (all event types except AGENT_RESULT, incremental mode).
     *
     * @param msg Input message
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(Msg msg) {
        return stream(msg, StreamOptions.defaults());
    }

    /**
     * Stream with multiple input messages.
     *
     * @param msgs Input messages
     * @param options Stream configuration options
     * @return Flux of events emitted during execution
     */
    Flux<Event> stream(List<Msg> msgs, StreamOptions options);

    /**
     * Stream with multiple input messages using default options.
     *
     * @param msgs Input messages
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(List<Msg> msgs) {
        return stream(msgs, StreamOptions.defaults());
    }

    /**
     * Stream with default options (all event types except AGENT_RESULT, incremental mode).
     *
     * @param msg Input message
     * @param options Stream configuration options
     * @param structuredModel Optional class defining the structure
     *
     * @return Flux of events emitted during execution
     */
    Flux<Event> stream(Msg msg, StreamOptions options, Class<?> structuredModel);

    /**
     * Process multiple input messages with structured model and generate a response.
     *
     * <p>The structured model parameter defines the expected structure of input or output data.
     * The structured data will be stored in the returned message's metadata field.
     *
     * <p>Default implementation ignores the structuredModel parameter. Agents that support
     * structured input/output should override this method.
     *
     * @param msgs Input messages
     * @param structuredModel Optional class defining the structure
     * @return Response message with structured data in metadata
     */
    Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel);
}

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

import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * Interface for agents that support streaming events during execution.
 *
 * <p>This interface enables real-time streaming of execution events as the agent
 * processes input. Events can include reasoning steps, tool results, and final output.
 *
 * <p>Streaming is useful for:
 * <ul>
 *   <li>Displaying incremental progress to users</li>
 *   <li>Monitoring agent reasoning in real-time</li>
 *   <li>Building interactive chat interfaces</li>
 * </ul>
 */
public interface StreamableAgent {

    /**
     * Stream execution events based on current state without adding new input.
     *
     * @param options Stream configuration options
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(StreamOptions options) {
        return stream(List.of(), options);
    }

    /**
     * Stream execution events with structured output support based on current state.
     *
     * @param structuredModel Class defining the structure of the output
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(Class<?> structuredModel) {
        return stream(List.of(), StreamOptions.defaults(), structuredModel);
    }

    /**
     * Stream execution events with structured output support based on current state.
     *
     * @param options Stream configuration options
     * @param structuredModel Class defining the structure of the output
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(StreamOptions options, Class<?> structuredModel) {
        return stream(List.of(), options, structuredModel);
    }

    /**
     * Stream execution events for a single message with default options.
     *
     * @param msg Input message
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(Msg msg) {
        return stream(msg, StreamOptions.defaults());
    }

    /**
     * Stream execution events for a single message.
     *
     * @param msg Input message
     * @param options Stream configuration options
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(Msg msg, StreamOptions options) {
        return stream(List.of(msg), options);
    }

    /**
     * Stream execution events for a single message with structured output support.
     *
     * @param msg Input message
     * @param options Stream configuration options
     * @param structuredModel Class defining the structure of the output
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(Msg msg, StreamOptions options, Class<?> structuredModel) {
        return stream(List.of(msg), options, structuredModel);
    }

    /**
     * Stream execution events for multiple messages with default options.
     *
     * @param msgs Input messages
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(List<Msg> msgs) {
        return stream(msgs, StreamOptions.defaults());
    }

    /**
     * Stream execution events in real-time as the agent processes the input.
     *
     * @param msgs Input messages
     * @param options Stream configuration options
     * @return Flux of events emitted during execution
     */
    Flux<Event> stream(List<Msg> msgs, StreamOptions options);

    /**
     * Stream execution events with structured output support.
     *
     * @param msgs Input messages
     * @param options Stream configuration options
     * @param structuredModel Class defining the structure of the output
     * @return Flux of events emitted during execution
     */
    Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel);
}

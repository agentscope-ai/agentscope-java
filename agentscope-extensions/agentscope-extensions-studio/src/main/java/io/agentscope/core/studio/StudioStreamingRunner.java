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
package io.agentscope.core.studio;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.StreamableAgent;
import io.agentscope.core.message.Msg;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class StudioStreamingRunner {

    private final StudioStreamingBridge streamingBridge;

    public StudioStreamingRunner(StudioStreamingBridge streamingBridge) {
        this.streamingBridge = streamingBridge;
    }

    public Mono<Void> runWithStreaming(StreamableAgent agent, Msg input, StreamOptions options) {
        StreamOptions effectiveOptions =
                options != null ? options : StreamOptions.defaults();

        Flux<Event> eventFlux = agent.stream(input, effectiveOptions);
        return streamingBridge.forwardToStudio(eventFlux);
    }
}

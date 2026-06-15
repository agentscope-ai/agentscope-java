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
package io.agentscope.examples.planskillcombo;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * In-memory event bus that publishes {@link ChatResp} objects, mirroring {@code
 * io.agentscope.builder.web.toolbus.ToolEventBus}.
 *
 * <p>Consumers subscribe via {@link #subscribe(String)} filtering by session key. Publishers call
 * {@link #publish(String, ChatResp)} from hooks.
 */
public class ToolEventBus {

    private final Sinks.Many<Envelope> sink =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    /** Publishes a chat response to all current subscribers under the given session key. */
    public void publish(String sessionKey, ChatResp resp) {
        sink.tryEmitNext(new Envelope(sessionKey, resp));
    }

    /**
     * Returns a {@link Flux} of {@link ChatResp} filtered to the given session key. Callers should
     * manage the flux lifecycle (e.g. take-until-signal).
     */
    public Flux<ChatResp> subscribe(String sessionKey) {
        return sink.asFlux().filter(e -> sessionKey.equals(e.sessionKey)).map(e -> e.resp);
    }

    private record Envelope(String sessionKey, ChatResp resp) {}
}

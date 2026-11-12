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
package io.agentcore.managed.server.runtime.managed;

import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Native inbox is empty: Managed inputs and events use the Session protocol.
 * Public multiagent messaging is rejected at admission. Reject native writes explicitly
 * instead of creating an independent workspace/Redis inbox that cannot recover with a Session.
 */
public final class ManagedSessionMessageBus implements MessageBus {
    private <T> Mono<T> unsupported() {
        return Mono.error(
                new UnsupportedOperationException(
                        "Native messaging is not an enabled Managed capability"));
    }

    public Mono<String> queuePush(String key, Map<String, Object> payload) {
        return unsupported();
    }

    public Mono<List<BusEntry>> queueDrain(String key, int count) {
        return Mono.just(List.of());
    }

    public Mono<Void> queueDelete(String key) {
        return Mono.empty();
    }

    public Mono<Boolean> queuePeek(String key) {
        return Mono.just(false);
    }

    public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
        return unsupported();
    }

    public Mono<List<BusEntry>> logRead(String key, String since, int count) {
        return Mono.just(List.of());
    }

    public Mono<Void> logTrim(String key) {
        return Mono.empty();
    }

    public Mono<Void> publish(String key, Map<String, Object> payload) {
        return unsupported();
    }

    public Flux<Map<String, Object>> subscribe(String key) {
        return Flux.error(new UnsupportedOperationException("Use Session Service events"));
    }
}

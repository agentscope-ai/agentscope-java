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
package io.agentscope.builder.runtime.managed;

import io.agentscope.harness.agent.bus.AsyncToolRecord;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Mono;

/** Service offloading is exclusively ToolTask/Hands; never run the local orphan-timeout registry. */
public final class ManagedAsyncToolRegistry implements AsyncToolRegistry {
    @Override
    public Mono<List<AsyncToolRecord>> findStale(String session, Duration ttl) {
        return Mono.just(List.of());
    }

    @Override
    public Mono<Void> register(AsyncToolRecord record) {
        return unsupported();
    }

    @Override
    public Mono<Void> complete(String id, String result) {
        return unsupported();
    }

    @Override
    public Mono<Void> fail(String id, String error) {
        return unsupported();
    }

    @Override
    public Mono<Void> markTimeout(String id) {
        return unsupported();
    }

    private static Mono<Void> unsupported() {
        return Mono.error(
                new UnsupportedOperationException(
                        "Managed async tools use durable ToolTask/Hands execution"));
    }
}

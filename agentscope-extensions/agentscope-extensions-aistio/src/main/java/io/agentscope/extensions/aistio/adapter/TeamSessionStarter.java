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
package io.agentscope.extensions.aistio.adapter;

import io.agentscope.harness.agent.middleware.TeamsMiddleware;
import reactor.core.publisher.Mono;

/**
 * Host callback that adopts a control-plane team session (BYO {@code team_join} / {@code
 * team_leave}). Typically registers an external session id on {@code HarnessGateway} and starts
 * {@code TeamsMiddleware} wakeup.
 */
public interface TeamSessionStarter {

    /**
     * Adopts {@code sessionId} with the opaque TeamContext JSON in {@code params}.
     *
     * @param sessionId CP-allocated session id
     * @param params TeamContext JSON bytes (may be empty)
     */
    Mono<Void> join(String sessionId, byte[] params);

    /** Leaves the team session; the default only drops the wakeup registration. */
    default Mono<Void> leave(String sessionId) {
        return Mono.fromRunnable(() -> TeamsMiddleware.unregisterSession(sessionId));
    }
}

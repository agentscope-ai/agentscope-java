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
import io.agentscope.core.state.AgentState;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * Invocation-scoped cooperation with an execution host. Core owns its loop; the host coordinates
 * persistent input and control at native boundaries and can resume finalization. State and facts
 * still commit through the configured native journal, which can share the host's transaction.
 * Implementations must keep blocking storage work off reactive event-loop threads.
 */
public interface AgentExecutionHost {
    /**
     * Cooperative stop at a recoverable boundary. Core records a suspended invocation instead of
     * reporting a failure or synthesizing interrupted tool results. The host owns task policy.
     */
    final class Paused extends RuntimeException {
        public Paused() {
            super("Invocation paused by execution host", null, false, false);
        }
    }

    enum Boundary {
        REASONING,
        MODEL,
        ACTING,
        FINALIZATION
    }

    /** Runs before a new phase starts; input applied at REASONING enters the next model context. */
    default Mono<Void> beforeBoundary(Boundary boundary, RuntimeContext context, AgentState state) {
        return Mono.empty();
    }

    /** A previously produced reply whose finalization must resume, or null for ordinary execution. */
    default Msg resumeResult() {
        return null;
    }

    /**
     * Prepare, run or restore finalization while the recorder and authorization are live. The host
     * can persist the reply before invoking the continuation, which checks the FINALIZATION boundary
     * before executing finish middleware. Throwing {@link Paused} must preserve a recovery position.
     */
    Mono<Void> finish(RuntimeContext context, Msg result, Supplier<Mono<Void>> continuation);
}

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
package io.agentscope.harness.agent.subagent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/** Optional host-owned execution boundary. Standalone Harness uses its local invocation. */
public interface SubagentExecution {
    record Invocation(String agentId, String prompt, String resultHeader) {}

    default String childSessionId(RuntimeContext parent) {
        return null;
    }

    /** Host seeds permissions and plan state in its durable child store instead of a local slot. */
    default boolean ownsChildState() {
        return false;
    }

    Mono<Msg> invoke(
            Agent agent,
            RuntimeContext parent,
            RuntimeContext child,
            Supplier<Mono<Msg>> localInvocation);
}

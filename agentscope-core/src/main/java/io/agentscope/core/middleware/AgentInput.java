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
package io.agentscope.core.middleware;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import java.util.List;

/**
 * Input context for {@link MiddlewareBase#onAgent}.
 *
 * @param msgs the input messages to the agent
 * @param runtimeContext per-call runtime context for this agent invocation
 */
public record AgentInput(List<Msg> msgs, RuntimeContext runtimeContext) {

    public AgentInput(List<Msg> msgs) {
        this(msgs, RuntimeContext.empty());
    }

    public AgentInput {
        msgs = msgs != null ? msgs : List.of();
        runtimeContext = runtimeContext != null ? runtimeContext : RuntimeContext.empty();
    }
}

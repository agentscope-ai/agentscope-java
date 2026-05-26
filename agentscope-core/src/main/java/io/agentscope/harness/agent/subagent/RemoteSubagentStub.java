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

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Placeholder {@link io.agentscope.core.agent.Agent} for {@linkplain SubagentDeclaration remote}
 * subagents. Execution is delegated to a remote task HTTP server; this instance is only used for
 * registry bookkeeping and should not receive synchronous {@code invokeAgent} calls in normal
 * async flows.
 */
public final class RemoteSubagentStub extends AgentBase {

    public RemoteSubagentStub(String name, String description) {
        super(name, description, false, List.of());
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        return Mono.just(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .textContent(
                                "This subagent is configured for remote HTTP execution only. Use"
                                    + " agent_spawn with timeout_seconds=0 so the parent delegates"
                                    + " via the task protocol; synchronous in-process call() is not"
                                    + " supported for this subagent type.")
                        .build());
    }

    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
        return Mono.just(Msg.builder().role(MsgRole.ASSISTANT).textContent("Interrupted.").build());
    }
}

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
package io.agentscope.harness.agent.gateway;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** Verifies wakeup rounds tolerate an external session without a recorded outbound route. */
@DisplayName("HarnessGateway wakeup route")
class HarnessGatewayWakeupRouteTest {

    @Test
    @DisplayName("runWakeup skips the outbound address when no route is recorded")
    void runWakeupWithoutRouteDoesNotFail() {
        HarnessAgent agent =
                spy(
                        HarnessAgent.builder()
                                .name("assistant")
                                .sysPrompt("hi")
                                .model(new MockModel("ok"))
                                .build());
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        doAnswer(
                        inv -> {
                            seen.set(inv.getArgument(1));
                            return Mono.just(
                                    Msg.builder()
                                            .role(MsgRole.ASSISTANT)
                                            .textContent("ok")
                                            .build());
                        })
                .when(agent)
                .call(anyList(), any(RuntimeContext.class));

        HarnessGateway gateway = HarnessGateway.create();
        gateway.bindMainAgent(agent);
        gateway.registerExternalSession("ext-session", "chatui|ext-session");

        Msg reply = gateway.runWakeup("user-1", "ext-session").block();

        assertNotNull(reply);
        assertNotNull(seen.get());
        assertNull(seen.get().get("outboundAddress"));
    }
}

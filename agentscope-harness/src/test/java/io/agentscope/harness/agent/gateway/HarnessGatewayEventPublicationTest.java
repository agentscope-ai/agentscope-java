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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class HarnessGatewayEventPublicationTest {

    @Test
    void wakeupFinishesBeforePublicationAndHandlesItsLateFailure() {
        var publication = Sinks.<String>one();
        var bus = mock(MessageBus.class);
        when(bus.sessionPublishEvent(eq("session-42"), anyMap())).thenReturn(publication.asMono());
        var reply = Msg.builder().role(MsgRole.ASSISTANT).textContent("private reply").build();
        var event = new AgentResultEvent("event-17", "2026-01-01T00:00:00Z", reply);
        var agent = mock(HarnessAgent.class);
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.just(event));
        var gateway = HarnessGateway.create(new ChannelManager(), bus);
        var store = new InMemoryStore();
        store.put(
                List.of("gateway", "routes"),
                "session-42",
                OutboundAddress.direct("chat", "user").toMap());
        gateway.setBaseStore(store);
        gateway.bindMainAgent(agent);
        gateway.registerExternalSession("session-42", "gate");

        assertSame(reply, gateway.runWakeup("user", "session-42").block(Duration.ofSeconds(5)));
        assertEquals(1, publication.currentSubscriberCount());
        var failure = new IllegalStateException("backend rejected private reply");
        StepVerifier.create(
                        Mono.fromRunnable(
                                () ->
                                        publication.emitError(
                                                failure, Sinks.EmitFailureHandler.FAIL_FAST)))
                .expectComplete()
                .verifyThenAssertThat()
                .hasNotDroppedErrors();
    }
}

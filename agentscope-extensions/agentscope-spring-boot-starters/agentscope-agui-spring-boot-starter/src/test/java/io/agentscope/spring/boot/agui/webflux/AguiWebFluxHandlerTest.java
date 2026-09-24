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
package io.agentscope.spring.boot.agui.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.web.reactive.function.server.EntityResponse;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AguiWebFluxHandlerTest {

    @Test
    void cancelInterruptsAgentWithResolvedUserId() throws Exception {
        HandlerFixture fixture = fixture(true);
        ServerResponse response = fixture.handler.handle(request()).block();
        Flux<?> body = entityBody(response);

        Disposable subscription = body.subscribe();
        assertTrue(fixture.firstRunSubscribed.await(5, TimeUnit.SECONDS));
        subscription.dispose();

        ArgumentCaptor<RuntimeContext> contextCaptor =
                ArgumentCaptor.forClass(RuntimeContext.class);
        verify(fixture.agent, timeout(5000)).interrupt(contextCaptor.capture());
        assertEquals("user-1", contextCaptor.getValue().getUserId());
        assertEquals("thread-1", contextCaptor.getValue().getSessionId());
    }

    @Test
    void cancelKeepsAgentRunningWhenInterruptOnDisconnectIsDisabled() throws Exception {
        HandlerFixture fixture = fixture(false);
        ServerResponse response = fixture.handler.handle(request()).block();
        Flux<?> body = entityBody(response);

        Disposable subscription = body.subscribe();
        assertTrue(fixture.firstRunSubscribed.await(5, TimeUnit.SECONDS));
        subscription.dispose();

        verify(fixture.agent, never())
                .interrupt(org.mockito.ArgumentMatchers.any(RuntimeContext.class));
        verify(fixture.agent, never()).interrupt();
    }

    private static HandlerFixture fixture(boolean interruptOnDisconnect) {
        ReActAgent agent = mock(ReActAgent.class);
        AguiAgentRegistry registry = new AguiAgentRegistry();
        registry.register("default", agent);
        CountDownLatch firstRunSubscribed = new CountDownLatch(1);
        AtomicInteger runCount = new AtomicInteger();
        AguiWebFluxHandler handler =
                AguiWebFluxHandler.builder()
                        .agentRegistry(registry)
                        .interruptOnDisconnect(interruptOnDisconnect)
                        .runtimeContextResolver(
                                request -> RuntimeContext.builder().userId("user-1").build())
                        .adapterFactory(
                                (resolvedAgent, config) ->
                                        new TestAdapter(
                                                resolvedAgent,
                                                config,
                                                firstRunSubscribed,
                                                runCount))
                        .build();
        return new HandlerFixture(handler, agent, firstRunSubscribed);
    }

    private static MockServerRequest request() {
        return MockServerRequest.builder()
                .method(HttpMethod.POST)
                .uri(URI.create("http://localhost/agui/run"))
                .body(
                        Mono.just(
                                """
                                {
                                  "threadId": "thread-1",
                                  "runId": "run-1",
                                  "messages": [
                                    {"id": "message-1", "role": "user", "content": "hello"}
                                  ]
                                }
                                """));
    }

    private static Flux<?> entityBody(ServerResponse response) {
        return (Flux<?>) ((EntityResponse<?>) response).entity();
    }

    private record HandlerFixture(
            AguiWebFluxHandler handler, ReActAgent agent, CountDownLatch firstRunSubscribed) {}

    private static final class TestAdapter extends AguiAgentAdapter {

        private final CountDownLatch firstRunSubscribed;
        private final AtomicInteger runCount;

        private TestAdapter(
                Agent agent,
                AguiAdapterConfig config,
                CountDownLatch firstRunSubscribed,
                AtomicInteger runCount) {
            super(agent, config);
            this.firstRunSubscribed = firstRunSubscribed;
            this.runCount = runCount;
        }

        @Override
        public Flux<AguiEvent> run(RunAgentInput input, RuntimeContext runtimeContext) {
            runCount.incrementAndGet();
            return Flux.<AguiEvent>never().doOnRequest(ignored -> firstRunSubscribed.countDown());
        }
    }
}

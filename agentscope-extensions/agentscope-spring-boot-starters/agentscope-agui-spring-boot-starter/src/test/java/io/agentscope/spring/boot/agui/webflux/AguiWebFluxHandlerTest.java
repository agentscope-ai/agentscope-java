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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.agui.runtime.AguiRequestBodyParser;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AguiWebFluxHandlerTest {

    @Test
    void customProcessorHandlesRequestsWithoutRegistry() {
        AguiRequestProcessor processor = mock(AguiRequestProcessor.class);
        RunAgentInput input = input();
        when(processor.process(any(AguiRuntimeContextRequest.class)))
                .thenReturn(
                        new AguiRequestProcessor.ProcessResult(
                                mock(Agent.class),
                                Flux.just(new AguiEvent.RunFinished("thread-1", "run-1")),
                                RuntimeContext.empty()));
        AguiRequestBodyParser requestBodyParser = mock(AguiRequestBodyParser.class);
        when(requestBodyParser.parse("{}")).thenReturn(input);
        AguiWebFluxHandler handler =
                AguiWebFluxHandler.builder()
                        .requestProcessor(processor)
                        .requestBodyParser(requestBodyParser)
                        .build();
        MockServerRequest request =
                MockServerRequest.builder()
                        .method(HttpMethod.POST)
                        .uri(URI.create("http://localhost/agui/run/path-agent"))
                        .header("X-Agent-Id", "header-agent")
                        .pathVariable("agentId", "path-agent")
                        .body(Mono.just("{}"));

        ServerResponse response = handler.handleWithAgentId(request).block();

        assertNotNull(response);
        ArgumentCaptor<AguiRuntimeContextRequest> contextRequest =
                ArgumentCaptor.forClass(AguiRuntimeContextRequest.class);
        verify(processor).process(contextRequest.capture());
        assertSame(input, contextRequest.getValue().getInput());
        assertEquals("header-agent", contextRequest.getValue().getHeaderAgentId());
        assertEquals("path-agent", contextRequest.getValue().getPathAgentId());
        assertEquals(
                AguiRuntimeContextRequest.Transport.WEBFLUX,
                contextRequest.getValue().getTransport());
    }

    @Test
    void customProcessorTakesPrecedenceOverDefaultProcessorConfiguration() {
        AguiRequestProcessor processor = mock(AguiRequestProcessor.class);
        AguiWebFluxHandler handler =
                AguiWebFluxHandler.builder()
                        .agentRegistry(new AguiAgentRegistry())
                        .requestProcessor(processor)
                        .build();

        assertSame(processor, ReflectionTestUtils.getField(handler, "processor"));
    }

    @Test
    void registryIsStillRequiredWithoutCustomProcessor() {
        assertThrows(IllegalStateException.class, () -> AguiWebFluxHandler.builder().build());
        assertThrows(
                IllegalStateException.class,
                () -> AguiWebFluxHandler.builder().requestProcessor(null).build());
    }

    @Test
    void errorEventStreamUsesFallbackForNullMessage() {
        AguiWebFluxHandler handler =
                AguiWebFluxHandler.builder().agentRegistry(new AguiAgentRegistry()).build();

        Flux<ServerSentEvent<String>> events =
                ReflectionTestUtils.invokeMethod(
                        handler, "createErrorEventStream", "thread-1", "run-1", null);
        List<ServerSentEvent<String>> sentEvents = events.collectList().block();

        assertEquals(2, sentEvents.size());
        assertTrue(sentEvents.get(0).data().contains("\"error\":\"Unknown error\""));
        assertTrue(sentEvents.get(1).data().contains("\"type\":\"RUN_FINISHED\""));
    }

    private static RunAgentInput input() {
        return RunAgentInput.builder()
                .threadId("thread-1")
                .runId("run-1")
                .messages(List.of(AguiMessage.userMessage("message-1", "hello")))
                .build();
    }
}

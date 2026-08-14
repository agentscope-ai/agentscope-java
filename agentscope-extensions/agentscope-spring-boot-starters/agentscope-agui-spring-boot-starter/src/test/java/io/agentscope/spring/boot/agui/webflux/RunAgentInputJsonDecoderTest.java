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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import io.agentscope.core.agui.model.MessageContent;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

class RunAgentInputJsonDecoderTest {

    private static final String REQUEST_BODY =
            """
            {
              "threadId": "thread-1",
              "runId": "run-1",
              "messages": [
                {
                  "id": "message-1",
                  "role": "user",
                  "content": "Hello"
                }
              ],
              "tools": []
            }
            """;

    private final RunAgentInputJsonDecoder decoder = new RunAgentInputJsonDecoder();

    @Test
    void shouldOnlyDecodeRunAgentInput() {
        assertTrue(
                decoder.canDecode(
                        ResolvableType.forClass(RunAgentInput.class), MediaType.APPLICATION_JSON));
        assertFalse(
                decoder.canDecode(
                        ResolvableType.forClass(String.class), MediaType.APPLICATION_JSON));
    }

    @Test
    void shouldBindRunAgentInputThroughWebFluxBodyToMono() {
        AtomicReference<RunAgentInput> capturedInput = new AtomicReference<>();
        RouterFunction<ServerResponse> routes =
                route(
                        POST("/agui/run"),
                        request ->
                                request.bodyToMono(RunAgentInput.class)
                                        .doOnNext(capturedInput::set)
                                        .then(ServerResponse.noContent().build()));
        WebFluxConfigurer configurer =
                new AgentscopeAguiWebFluxAutoConfiguration().aguiRunAgentInputWebFluxConfigurer();
        HandlerStrategies strategies =
                HandlerStrategies.builder().codecs(configurer::configureHttpMessageCodecs).build();
        WebTestClient client =
                WebTestClient.bindToRouterFunction(routes).handlerStrategies(strategies).build();

        client.post()
                .uri("/agui/run")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(REQUEST_BODY)
                .exchange()
                .expectStatus()
                .isNoContent();

        RunAgentInput input = capturedInput.get();
        assertNotNull(input);
        assertEquals("thread-1", input.getThreadId());
        assertEquals("run-1", input.getRunId());
        MessageContent.Text content =
                assertInstanceOf(
                        MessageContent.Text.class, input.getMessages().get(0).getContent());
        assertEquals("Hello", content.value());
    }
}

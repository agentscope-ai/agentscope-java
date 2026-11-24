/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.ollama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.extensions.model.ollama.dto.OllamaRequest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@Timeout(10)
class OllamaStreamParsingTest {

    private static final String FIRST =
            """
            {"model":"test-model","message":{"role":"assistant","content":"Hello"},"done":false}
            """;
    private static final String SECOND =
            """
            {"model":"test-model","message":{"role":"assistant","content":" world"},"done":false}
            """;
    private static final String DONE =
            """
            {"model":"test-model","done":true,"eval_count":2}
            """;

    private HttpTransport transport;
    private OllamaHttpClient client;
    private OllamaRequest request;

    @BeforeEach
    void setUp() {
        transport = mock(HttpTransport.class);
        client = new OllamaHttpClient(transport, "http://localhost:11434");
        request = new OllamaRequest();
        request.setModel("test-model");
        request.setMessages(List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{", "[]", "null", ""})
    void invalidRecordsDoNotTerminateLaterValidChunks(String invalid) {
        when(transport.stream(any(HttpRequest.class)))
                .thenReturn(Flux.just(FIRST, invalid, SECOND, DONE));

        StepVerifier.create(client.stream(request))
                .expectNextMatches(chunk -> "Hello".equals(chunk.getMessage().getContent()))
                .expectNextMatches(chunk -> " world".equals(chunk.getMessage().getContent()))
                .expectNextMatches(chunk -> Boolean.TRUE.equals(chunk.getDone()))
                .verifyComplete();
    }

    @Test
    void streamOfOnlyInvalidRecordsCompletesWithoutNullElements() {
        when(transport.stream(any(HttpRequest.class)))
                .thenReturn(Flux.just("not-json", "null", "[]"));

        StepVerifier.create(client.stream(request)).verifyComplete();
    }

    @Test
    void ollamaErrorRecordsAreStillErrors() {
        when(transport.stream(any(HttpRequest.class)))
                .thenReturn(Flux.just("{\"error\":\"model not found\"}"));

        StepVerifier.create(client.stream(request))
                .expectErrorMatches(
                        error ->
                                error instanceof OllamaHttpClient.OllamaHttpException
                                        && error.getMessage().contains("model not found"))
                .verify();
    }

    @Test
    void transportErrorAfterAnInvalidRecordIsNotHidden() {
        HttpTransportException failure = new HttpTransportException("connection lost");
        when(transport.stream(any(HttpRequest.class)))
                .thenReturn(Flux.concat(Flux.just(FIRST, "not-json"), Flux.error(failure)));

        StepVerifier.create(client.stream(request))
                .expectNextCount(1)
                .expectErrorSatisfies(error -> assertSame(failure, error))
                .verify();
    }

    @Test
    void invalidRecordsDoNotConsumeDownstreamDemand() {
        when(transport.stream(any(HttpRequest.class)))
                .thenReturn(Flux.just("not-json", FIRST, "null", SECOND, DONE));

        StepVerifier.create(client.stream(request), 0)
                .thenRequest(1)
                .expectNextMatches(chunk -> "Hello".equals(chunk.getMessage().getContent()))
                .thenRequest(1)
                .expectNextMatches(chunk -> " world".equals(chunk.getMessage().getContent()))
                .thenRequest(1)
                .expectNextMatches(chunk -> Boolean.TRUE.equals(chunk.getDone()))
                .verifyComplete();
    }

    @Test
    void cancellationStillReachesTheTransport() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(transport.stream(any(HttpRequest.class)))
                .thenReturn(
                        Flux.concat(Flux.just("not-json", FIRST), Flux.<String>never())
                                .doOnCancel(() -> cancelled.set(true)));

        StepVerifier.create(client.stream(request)).expectNextCount(1).thenCancel().verify();
        assertTrue(cancelled.get());
    }

    @Test
    void chatModelPreservesContentAndStableIdsAroundInvalidRecords() {
        when(transport.stream(any(HttpRequest.class)))
                .thenReturn(Flux.just(FIRST, "not-json", "null", SECOND, DONE));
        OllamaChatModel model =
                OllamaChatModel.builder().modelName("test-model").httpTransport(transport).build();
        Msg input = Msg.builder().role(MsgRole.USER).textContent("hello").build();

        List<ChatResponse> chunks =
                model.stream(List.of(input), null, null).collectList().block(Duration.ofSeconds(5));

        assertNotNull(chunks);
        assertEquals(3, chunks.size());
        assertEquals(1, chunks.stream().map(ChatResponse::getId).distinct().count());
        assertNotNull(chunks.get(0).getId());
        String text =
                chunks.stream()
                        .flatMap(chunk -> chunk.getContent().stream())
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .collect(Collectors.joining());
        assertEquals("Hello world", text);
    }
}

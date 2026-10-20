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

package io.agentscope.extensions.judge.openaidecisions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpMethod;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;

class OpenAIDecisionsClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUCCESS =
            """
            {"model":"fixture-model", "answers":[
              {"name":"severity", "type":"score", "score":0.8, "confidence":0.6,
               "probabilities":[{"value":1,"label":"blocked","probability":0.8},
                                {"value":0,"label":"minor","probability":0.2}]},
              {"name":"damaged", "type":"predicate", "probability":0},
              {"name":"route", "type":"choice", "choice":true, "confidence":0.7,
               "probabilities":[{"value":true,"probability":0.9},{"value":"true","probability":0.1}]}
            ], "usage":{
              "input_tokens":12,
              "input_tokens_details":{"cached_tokens":3,"cache_write_tokens":2},
              "output_tokens":0,
              "output_tokens_details":{"reasoning_tokens":1},
              "total_tokens":12
            },"future_field":42}
            """;

    private static DecisionRequest request() {
        return DecisionRequest.builder()
                .input("The screen is broken")
                .question("damaged", new DecisionQuestion.Predicate("Is the item damaged?"))
                .question(
                        "route",
                        new DecisionQuestion.Choice(
                                "Select",
                                List.of(
                                        new DecisionQuestion.Option("true"),
                                        new DecisionQuestion.Option(true, "Needs repair"))))
                .question(
                        "severity",
                        new DecisionQuestion.Score(
                                "Rate",
                                List.of(
                                        new DecisionQuestion.Level("minor"),
                                        new DecisionQuestion.Level("blocked"))))
                .build();
    }

    private static HttpResponse response(int status, String body) {
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers())
                .thenReturn(Headers.builder().put("Content-Type", "application/json").build());
        when(response.body())
                .thenAnswer(
                        call -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    private static HttpClient transport(String body) {
        HttpClient transport = mock(HttpClient.class);
        var response = response(200, body);
        when(transport.execute(any(), any())).thenReturn(response);
        return transport;
    }

    private static OpenAIClient sdkClient(HttpClient transport) {
        return new OpenAIClientImpl(
                ClientOptions.builder()
                        .apiKey("fixture-secret")
                        .httpClient(transport)
                        .maxRetries(0)
                        .build());
    }

    private static OpenAIDecisionsClient client(HttpClient transport) {
        return OpenAIDecisionsClient.builder().client(sdkClient(transport)).build();
    }

    private static String body(HttpRequest request) throws Exception {
        var output = new ByteArrayOutputStream();
        request.body().writeTo(output);
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void mapsAllTypesByNameAndPreservesProviderValues() throws Exception {
        HttpClient transport = transport(SUCCESS);
        var result = client(transport).decideBlocking(request());
        assertEquals("fixture-model", result.model());
        assertEquals(0, ((DecisionAnswer.Predicate) result.answers().get("damaged")).probability());
        var choice = (DecisionAnswer.Choice) result.answers().get("route");
        assertEquals(true, choice.choice());
        assertEquals(0.7, choice.confidence());
        assertEquals("true", choice.probabilities().get(1).value());
        assertEquals(0.8, ((DecisionAnswer.Score) result.answers().get("severity")).score());
        assertEquals(new DecisionUsage(12L, 0L, 12L, 3L, 2L, 1L), result.usage());
        assertThrows(UnsupportedOperationException.class, () -> result.answers().clear());
        var captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(captured.capture(), any());
        HttpRequest sent = captured.getValue();
        assertEquals(HttpMethod.POST, sent.method());
        assertEquals("https://api.openai.com/v1/decisions", sent.url());
        assertEquals("Bearer fixture-secret", sent.headers().values("Authorization").get(0));
        assertEquals("application/json", sent.body().contentType());
        JsonNode body = MAPPER.readTree(body(sent));
        assertEquals("The screen is broken", body.get("input").textValue());
        assertEquals(OpenAIDecisionsClient.DEFAULT_MODEL, body.get("model").textValue());
        assertEquals("predicate", body.at("/questions/0/type").textValue());
        assertTrue(body.at("/questions/1/choices/1/value").isBoolean());
        assertFalse(body.at("/questions/1/choices/0").has("description"));
        assertEquals("minor", body.at("/questions/2/levels/0/label").textValue());
        verify(transport, never()).close();
    }

    @Test
    void sendsImagesAndCombinedInputWithModelOverride() throws Exception {
        HttpClient transport = transport(SUCCESS);
        var input =
                new DecisionInput(
                        "Inspect",
                        List.of(
                                "data:image/png;base64,aW1hZ2U=",
                                "https://images.example/product.png"));
        var request = new DecisionRequest(input, "override-model", request().questions());
        client(transport).decideBlocking(request);
        var capture = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(capture.capture(), any());
        var body = MAPPER.readTree(body(capture.getValue()));
        assertEquals("override-model", body.get("model").textValue());
        assertEquals("user", body.at("/input/0/role").textValue());
        assertEquals("input_text", body.at("/input/0/content/0/type").textValue());
        assertEquals(
                "data:image/png;base64,aW1hZ2U=",
                body.at("/input/0/content/1/image_url").textValue());
        assertEquals(
                "https://images.example/product.png",
                body.at("/input/0/content/2/image_url").textValue());
    }

    @Test
    void sendsPromptCacheKeyWithoutCacheOptions() throws Exception {
        HttpClient transport = transport(SUCCESS);
        client(transport)
                .decideBlocking(
                        new DecisionRequest(
                                DecisionInput.text("The screen is broken"),
                                null,
                                request().questions(),
                                "tenant-42"));
        var capture = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(capture.capture(), any());
        var body = MAPPER.readTree(body(capture.getValue()));
        assertEquals("tenant-42", body.get("prompt_cache_key").textValue());
        assertFalse(body.has("prompt_cache_options"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void preservesPerQuestionRefusalAndOtherAnswers(int index) throws Exception {
        ObjectNode body = (ObjectNode) MAPPER.readTree(SUCCESS);
        String name = body.get("answers").get(index).get("name").textValue();
        ((ArrayNode) body.get("answers"))
                .set(index, MAPPER.createObjectNode().put("name", name).put("type", "refusal"));
        var result = client(transport(body.toString())).decideBlocking(request());
        assertInstanceOf(DecisionAnswer.Refusal.class, result.answers().get(name));
        assertEquals(3, result.answers().size());
        assertEquals(
                2L,
                result.answers().values().stream()
                        .filter(answer -> !(answer instanceof DecisionAnswer.Refusal))
                        .count());
        assertEquals(12L, result.usage().inputTokens());
    }

    @Test
    void preservesProviderChoiceWithoutAssumingItIsTheMostProbable() throws Exception {
        ObjectNode body = (ObjectNode) MAPPER.readTree(SUCCESS);
        ((ObjectNode) body.at("/answers/2")).put("choice", "true");
        var result = client(transport(body.toString())).decideBlocking(request());
        var choice = (DecisionAnswer.Choice) result.answers().get("route");
        assertEquals("true", choice.choice());
        assertEquals(0.7, choice.confidence());
        assertEquals(0.9, choice.probabilities().get(0).probability());
        assertEquals(0.1, choice.probabilities().get(1).probability());
    }

    @Test
    void enforcesDocumentedCollectionBoundaries() {
        var choices =
                java.util.stream.IntStream.range(0, 255)
                        .mapToObj(i -> new DecisionQuestion.Option(Integer.toString(i)))
                        .toList();
        assertEquals(255, new DecisionQuestion.Choice("Choose", choices).choices().size());
        var tooManyChoices = new ArrayList<>(choices);
        tooManyChoices.add(new DecisionQuestion.Option("extra"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionQuestion.Choice("Choose", tooManyChoices));
        var questions = new LinkedHashMap<String, DecisionQuestion>();
        for (int i = 0; i < 400; i++) {
            questions.put(Integer.toString(i), new DecisionQuestion.Predicate("Check"));
        }
        assertEquals(
                400,
                new DecisionRequest(DecisionInput.text("Input"), null, questions)
                        .questions()
                        .size());
        questions.put("extra", new DecisionQuestion.Predicate("Check"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionRequest(DecisionInput.text("Input"), null, questions));
        assertEquals(
                128,
                new DecisionInput(
                                null,
                                java.util.Collections.nCopies(
                                        128, "data:image/png;base64,aW1hZ2U="))
                        .imageUrls()
                        .size());
    }

    static Stream<Consumer<ObjectNode>> invalidAnswers() {
        return Stream.of(
                root -> root.remove("answers"),
                root -> ((ArrayNode) root.get("answers")).set(1, MAPPER.nullNode()),
                root -> ((ArrayNode) root.at("/answers/2/probabilities")).set(0, MAPPER.nullNode()),
                root -> ((ArrayNode) root.at("/answers/0/probabilities")).set(0, MAPPER.nullNode()),
                root -> ((ArrayNode) root.get("answers")).remove(0),
                root -> ((ArrayNode) root.get("answers")).add(root.get("answers").get(0)),
                root -> ((ObjectNode) root.at("/answers/1")).put("name", "unknown"),
                root -> ((ObjectNode) root.at("/answers/1")).put("name", "route"),
                root -> ((ObjectNode) root.at("/answers/1")).put("type", "choice"),
                root -> ((ObjectNode) root.at("/answers/1")).put("type", "future-type"),
                root -> ((ObjectNode) root.at("/answers/1")).remove("probability"),
                root -> ((ObjectNode) root.at("/answers/1")).putNull("probability"),
                root -> ((ObjectNode) root.at("/answers/1")).put("probability", "0"),
                root -> ((ObjectNode) root.at("/answers/1")).put("probability", -0.1),
                root -> ((ObjectNode) root.at("/answers/1")).put("probability", 1.1),
                root -> ((ObjectNode) root.at("/answers/2")).remove("confidence"),
                root -> ((ObjectNode) root.at("/answers/2")).putNull("confidence"),
                root -> ((ObjectNode) root.at("/answers/2")).put("confidence", 2),
                root -> ((ObjectNode) root.at("/answers/2")).put("choice", "unknown"),
                root -> ((ObjectNode) root.at("/answers/2")).put("choice", 5),
                root -> ((ObjectNode) root.at("/answers/2/probabilities/0")).put("value", "true"),
                root ->
                        ((ObjectNode) root.at("/answers/2/probabilities/0"))
                                .put("value", "unknown"),
                root ->
                        ((ObjectNode) root.at("/answers/2/probabilities/0"))
                                .put("probability", 0.2),
                root -> ((ArrayNode) root.at("/answers/2/probabilities")).remove(1),
                root -> ((ObjectNode) root.at("/answers/0")).remove("score"),
                root -> ((ObjectNode) root.at("/answers/0")).putNull("score"),
                root -> ((ObjectNode) root.at("/answers/0")).put("score", 0.1),
                root -> ((ObjectNode) root.at("/answers/0/probabilities/0")).put("value", 0),
                root -> ((ObjectNode) root.at("/answers/0/probabilities/0")).put("value", 3),
                root -> ((ObjectNode) root.at("/answers/0/probabilities/0")).put("value", 0.5),
                root -> ((ObjectNode) root.at("/answers/0/probabilities/0")).put("label", "wrong"),
                root -> ((ObjectNode) root.at("/answers/0/probabilities/0")).putNull("probability"),
                root -> root.put("usage", "wrong"),
                root -> ((ObjectNode) root.get("usage")).put("input_tokens", -1),
                root -> ((ObjectNode) root.get("usage")).put("input_tokens", 1.5),
                root ->
                        ((ObjectNode) root.at("/usage/input_tokens_details"))
                                .put("cached_tokens", -1),
                root ->
                        ((ObjectNode) root.at("/usage/input_tokens_details"))
                                .put("cache_write_tokens", 1.5),
                root ->
                        ((ObjectNode) root.at("/usage/output_tokens_details"))
                                .put("reasoning_tokens", -1),
                root -> root.remove("model"));
    }

    @ParameterizedTest
    @MethodSource("invalidAnswers")
    void rejectsInvalidResponsesWithoutPartialResults(Consumer<ObjectNode> mutate)
            throws Exception {
        ObjectNode body = (ObjectNode) MAPPER.readTree(SUCCESS);
        mutate.accept(body);
        StepVerifier.create(client(transport(body.toString())).decide(request()))
                .expectError(OpenAIDecisionsException.class)
                .verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{}", "{private", "{\"model\":\"a\",\"model\":\"b\"}"})
    void rejectsMalformedResponsesWithoutExposingBody(String body) {
        var error =
                assertThrows(
                        OpenAIDecisionsException.class,
                        () -> client(transport(body)).decideBlocking(request()));
        assertFalse(error.getMessage().contains("private"));
        assertNull(error.getCause());
    }

    @Test
    void preservesUnknownUsage() throws Exception {
        ObjectNode body = (ObjectNode) MAPPER.readTree(SUCCESS);
        body.remove("usage");
        assertNull(client(transport(body.toString())).decideBlocking(request()).usage());
        body.putObject("usage").put("output_tokens", 0);
        var usage = client(transport(body.toString())).decideBlocking(request()).usage();
        assertNull(usage.inputTokens());
        assertEquals(0L, usage.outputTokens());
        assertNull(usage.cachedTokens());
        assertNull(usage.cacheWriteTokens());
        assertNull(usage.reasoningTokens());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500, 503})
    void exposesHttpStatusAndRetryHintWithoutRetrying(int status) {
        HttpClient transport = mock(HttpClient.class);
        var response = response(status, "private provider body fixture-secret");
        when(transport.execute(any(), any())).thenReturn(response);
        var error =
                assertThrows(
                        OpenAIDecisionsException.class,
                        () -> client(transport).decideBlocking(request()));
        assertEquals(status, error.getStatusCode());
        assertEquals(status == 429 || status >= 500, error.isRetryable());
        assertFalse(error.getMessage().contains("private"));
        verify(transport, times(1)).execute(any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sanitizesRetryableSdkErrorsAndIsLazy(boolean sdkRetryHint) {
        HttpClient transport = mock(HttpClient.class);
        when(transport.execute(any(), any()))
                .thenThrow(
                        sdkRetryHint
                                ? new OpenAIRetryableException("fixture-secret")
                                : new OpenAIIoException("fixture-secret"));
        var pending = client(transport).decide(request());
        verifyNoInteractions(transport);
        StepVerifier.create(pending)
                .expectErrorSatisfies(
                        error -> {
                            assertInstanceOf(OpenAIDecisionsException.class, error);
                            assertTrue(((OpenAIDecisionsException) error).isRetryable());
                            assertFalse(error.getMessage().contains("fixture-secret"));
                            assertNull(error.getCause());
                        })
                .verify();
    }

    @Test
    void cancellationInterruptsTransportExecution() throws Exception {
        CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        HttpClient transport = mock(HttpClient.class);
        when(transport.execute(any(), any()))
                .thenAnswer(
                        call -> {
                            started.countDown();
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException error) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                            }
                            throw new OpenAIIoException("interrupted");
                        });
        StepVerifier.create(client(transport).decide(request()))
                .then(
                        () -> {
                            try {
                                assertTrue(started.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException e) {
                                throw new AssertionError(e);
                            }
                        })
                .thenCancel()
                .verify(Duration.ofSeconds(6));
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        verify(transport, never()).close();
    }

    @Test
    void timeoutTerminatesAndInterruptsExecution() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        HttpClient transport = mock(HttpClient.class);
        when(transport.execute(any(), any()))
                .thenAnswer(
                        call -> {
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException error) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                            }
                            throw new OpenAIIoException("interrupted");
                        });
        var client =
                OpenAIDecisionsClient.builder()
                        .apiKey("fixture-key")
                        .client(sdkClient(transport))
                        .timeout(Duration.ofSeconds(1))
                        .build();
        StepVerifier.create(client.decide(request()))
                .expectErrorSatisfies(
                        error -> {
                            assertInstanceOf(OpenAIDecisionsException.class, error);
                            assertTrue(((OpenAIDecisionsException) error).isRetryable());
                        })
                .verify(Duration.ofSeconds(5));
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
    }

    @Test
    void repeatedTimeoutSubscriptionsUseSeparateExceptions() {
        HttpClient transport = mock(HttpClient.class);
        when(transport.execute(any(), any()))
                .thenAnswer(
                        call -> {
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                            }
                            throw new OpenAIIoException("interrupted");
                        });
        var pending =
                OpenAIDecisionsClient.builder()
                        .apiKey("fixture-key")
                        .client(sdkClient(transport))
                        .timeout(Duration.ofMillis(50))
                        .build()
                        .decide(request());
        var first =
                assertThrows(
                        OpenAIDecisionsException.class, () -> pending.block(Duration.ofSeconds(5)));
        var second =
                assertThrows(
                        OpenAIDecisionsException.class, () -> pending.block(Duration.ofSeconds(5)));
        assertNotSame(first, second);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void usesOfficialSdkAgainstLocalFixture(boolean fullEndpoint) throws Exception {
        var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/prefix/v1/decisions",
                exchange -> {
                    captured.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] response = SUCCESS.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                });
        server.start();
        try (var client =
                OpenAIDecisionsClient.builder()
                        .apiKey("fixture-key")
                        .baseUrl(
                                "http://127.0.0.1:"
                                        + server.getAddress().getPort()
                                        + "/prefix/v1/"
                                        + (fullEndpoint ? "decisions/" : ""))
                        .build()) {
            assertEquals(3, client.decideBlocking(request()).answers().size());
            assertEquals(
                    "The screen is broken",
                    MAPPER.readTree(captured.get()).get("input").textValue());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsImageOnlyInput() throws Exception {
        var transport = transport(SUCCESS);
        client(transport)
                .decideBlocking(
                        new DecisionRequest(
                                DecisionInput.image("data:image/png;base64,aW1hZ2U="),
                                null,
                                request().questions()));
        var captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(captured.capture(), any());
        var body = MAPPER.readTree(body(captured.getValue()));
        assertEquals(1, body.at("/input/0/content").size());
        assertEquals("input_image", body.at("/input/0/content/0/type").textValue());
    }

    @Test
    void borrowsSdkClientAndPassesRequestTimeout() {
        var transport = transport(SUCCESS);
        var sdk = sdkClient(transport);
        try (var client =
                OpenAIDecisionsClient.builder()
                        .client(sdk)
                        .apiKey(null)
                        .timeout(Duration.ofSeconds(7))
                        .build()) {
            assertEquals(3, client.decideBlocking(request()).answers().size());
        }
        var options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(transport).execute(any(), options.capture());
        assertEquals(Duration.ofSeconds(7), options.getValue().getTimeout().request());
        verify(transport, never()).close();
        assertEquals(
                3,
                OpenAIDecisionsClient.builder()
                        .client(sdk)
                        .build()
                        .decideBlocking(request())
                        .answers()
                        .size());
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500})
    void defaultSdkClientDoesNotRetry(int status) throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/decisions",
                exchange -> {
                    calls.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    byte[] response =
                            "{\"error\":{\"message\":\"private fixture-secret\"}}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, response.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                });
        server.start();
        try (var client =
                OpenAIDecisionsClient.builder()
                        .apiKey("fixture-key")
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                        .build()) {
            var error =
                    assertThrows(
                            OpenAIDecisionsException.class, () -> client.decideBlocking(request()));
            assertEquals(status, error.getStatusCode());
            assertTrue(error.isRetryable());
            assertFalse(error.getMessage().contains("private"));
            assertNull(error.getCause());
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validatesConfigurationAndImmutableRequests() {
        for (String url :
                List.of(
                        "file:///tmp/a",
                        "https://user:secret@example.com/v1",
                        "https://example.com/v1?key=secret",
                        "https://example.com/v1#secret",
                        "invalid")) {
            var error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    OpenAIDecisionsClient.builder()
                                            .apiKey("key")
                                            .baseUrl(url)
                                            .build());
            assertFalse(error.getMessage().contains("secret"));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> OpenAIDecisionsClient.builder().apiKey("").build());
        assertThrows(
                IllegalArgumentException.class,
                () -> OpenAIDecisionsClient.builder().apiKey("key").model("").build());
        assertThrows(
                IllegalArgumentException.class,
                () -> OpenAIDecisionsClient.builder().apiKey("key").timeout(Duration.ZERO).build());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DecisionRequest.builder()
                                .input("x")
                                .question("a", new DecisionQuestion.Predicate("x"))
                                .promptCacheKey(" ")
                                .build());
        assertThrows(
                IllegalArgumentException.class, () -> DecisionRequest.builder().input("x").build());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DecisionRequest.builder()
                                .input("x")
                                .question("a", new DecisionQuestion.Predicate("x"))
                                .question("a", new DecisionQuestion.Predicate("y")));
        assertThrows(IllegalArgumentException.class, () -> new DecisionQuestion.Option(1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionQuestion.Choice("x", List.of(new DecisionQuestion.Option("a"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DecisionQuestion.Choice(
                                "x",
                                List.of(
                                        new DecisionQuestion.Option("a"),
                                        new DecisionQuestion.Option("a"))));
        assertThrows(
                IllegalArgumentException.class, () -> new DecisionQuestion.Score("x", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DecisionInput(null, List.of()));
        assertThrows(
                IllegalArgumentException.class, () -> DecisionInput.image("file:///private.png"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DecisionInput(
                                null,
                                java.util.Collections.nCopies(129, "https://example.com/a.png")));
        var questions = new LinkedHashMap<String, DecisionQuestion>();
        questions.put("a", new DecisionQuestion.Predicate("x"));
        var request = new DecisionRequest(DecisionInput.text("x"), null, questions);
        questions.clear();
        assertEquals(1, request.questions().size());
        var options =
                new ArrayList<>(
                        List.of(
                                new DecisionQuestion.Option("a"),
                                new DecisionQuestion.Option("b")));
        var choice = new DecisionQuestion.Choice("x", options);
        options.clear();
        assertEquals(2, choice.choices().size());
    }
}

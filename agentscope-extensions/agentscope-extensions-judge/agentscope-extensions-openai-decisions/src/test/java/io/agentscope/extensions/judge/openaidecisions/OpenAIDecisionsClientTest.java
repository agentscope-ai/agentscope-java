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
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.JdkHttpTransport;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
            ], "usage":{"input_tokens":12,"output_tokens":0,"total_tokens":12},"future_field":42}
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

    private static HttpTransport transport(String body) {
        HttpTransport transport = mock(HttpTransport.class);
        when(transport.execute(any()))
                .thenReturn(HttpResponse.builder().statusCode(200).body(body).build());
        return transport;
    }

    private static OpenAIDecisionsClient client(HttpTransport transport) {
        return OpenAIDecisionsClient.builder()
                .apiKey("fixture-secret")
                .transport(transport)
                .build();
    }

    @Test
    void mapsAllTypesByNameAndPreservesProviderValues() throws Exception {
        HttpTransport transport = transport(SUCCESS);
        var result = client(transport).decideBlocking(request());
        assertEquals("fixture-model", result.model());
        assertEquals(0, ((DecisionAnswer.Predicate) result.answers().get("damaged")).probability());
        var choice = (DecisionAnswer.Choice) result.answers().get("route");
        assertEquals(true, choice.choice());
        assertEquals(0.7, choice.confidence());
        assertEquals("true", choice.probabilities().get(1).value());
        assertEquals(0.8, ((DecisionAnswer.Score) result.answers().get("severity")).score());
        assertEquals(new DecisionUsage(12L, 0L, 12L), result.usage());
        assertThrows(UnsupportedOperationException.class, () -> result.answers().clear());
        var captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(captured.capture());
        HttpRequest sent = captured.getValue();
        assertEquals("POST", sent.getMethod());
        assertEquals("https://api.openai.com/v1/decisions", sent.getUrl());
        assertEquals("Bearer fixture-secret", sent.getHeaders().get("Authorization"));
        assertEquals("application/json", sent.getHeaders().get("Content-Type"));
        JsonNode body = MAPPER.readTree(sent.getBody());
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
        HttpTransport transport = transport(SUCCESS);
        var input =
                new DecisionInput(
                        "Inspect",
                        List.of(
                                "data:image/png;base64,aW1hZ2U=",
                                "https://images.example/product.png"));
        var request = new DecisionRequest(input, "override-model", request().questions());
        client(transport).decideBlocking(request);
        var capture = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(capture.capture());
        var body = MAPPER.readTree(capture.getValue().getBody());
        assertEquals("override-model", body.get("model").textValue());
        assertEquals("user", body.at("/input/0/role").textValue());
        assertEquals("input_text", body.at("/input/0/content/0/type").textValue());
        assertEquals(
                "data:image/png;base64,aW1hZ2U=",
                body.at("/input/0/content/1/image_url").textValue());
        assertEquals(
                "https://images.example/product.png",
                body.at("/input/0/content/2/image_url").textValue());
        assertEquals(
                "input_image",
                MAPPER.valueToTree(DecisionInput.image("https://example.com/a.png").toWire())
                        .at("/0/content/0/type")
                        .textValue());
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
        for (int i = 0; i < 200; i++) {
            questions.put(Integer.toString(i), new DecisionQuestion.Predicate("Check"));
        }
        assertEquals(
                200,
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
    void rejectsTrailingJsonAndPreservesUnknownUsage() throws Exception {
        assertThrows(
                OpenAIDecisionsException.class,
                () -> client(transport(SUCCESS + " {}")).decideBlocking(request()));
        ObjectNode body = (ObjectNode) MAPPER.readTree(SUCCESS);
        body.remove("usage");
        assertNull(client(transport(body.toString())).decideBlocking(request()).usage());
        body.putObject("usage").put("output_tokens", 0);
        var usage = client(transport(body.toString())).decideBlocking(request()).usage();
        assertNull(usage.inputTokens());
        assertEquals(0L, usage.outputTokens());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500, 503})
    void exposesHttpStatusAndRetryHintWithoutRetrying(int status) {
        HttpTransport transport = mock(HttpTransport.class);
        when(transport.execute(any()))
                .thenReturn(
                        HttpResponse.builder()
                                .statusCode(status)
                                .body("private provider body fixture-secret")
                                .build());
        var error =
                assertThrows(
                        OpenAIDecisionsException.class,
                        () -> client(transport).decideBlocking(request()));
        assertEquals(status, error.getStatusCode());
        assertEquals(status == 429 || status >= 500, error.isRetryable());
        assertFalse(error.getMessage().contains("private"));
        verify(transport, times(1)).execute(any());
    }

    @Test
    void sanitizesTransportErrorsAndIsLazy() {
        HttpTransport transport = mock(HttpTransport.class);
        when(transport.execute(any())).thenThrow(new HttpTransportException("fixture-secret"));
        var pending = client(transport).decide(request());
        verifyNoInteractions(transport);
        StepVerifier.create(pending)
                .expectErrorSatisfies(
                        error -> {
                            assertInstanceOf(OpenAIDecisionsException.class, error);
                            assertFalse(error.getMessage().contains("fixture-secret"));
                            assertNull(error.getCause());
                        })
                .verify();
    }

    @Test
    void cancellationInterruptsTransportExecution() throws Exception {
        CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        HttpTransport transport = mock(HttpTransport.class);
        when(transport.execute(any()))
                .thenAnswer(
                        call -> {
                            started.countDown();
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException error) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                            }
                            throw new HttpTransportException("interrupted");
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
        HttpTransport transport = mock(HttpTransport.class);
        when(transport.execute(any()))
                .thenAnswer(
                        call -> {
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException error) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                            }
                            throw new HttpTransportException("interrupted");
                        });
        var client =
                OpenAIDecisionsClient.builder()
                        .apiKey("fixture-key")
                        .transport(transport)
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
        HttpTransport transport = mock(HttpTransport.class);
        when(transport.execute(any()))
                .thenAnswer(
                        call -> {
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                            }
                            throw new HttpTransportException("interrupted");
                        });
        var pending =
                OpenAIDecisionsClient.builder()
                        .apiKey("fixture-key")
                        .transport(transport)
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

    @Test
    void usesRealHttpTransportAgainstLocalFixture() throws Exception {
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
        var transport = JdkHttpTransport.builder().build();
        try {
            var client =
                    OpenAIDecisionsClient.builder()
                            .apiKey("fixture-key")
                            .transport(transport)
                            .baseUrl(
                                    "http://127.0.0.1:"
                                            + server.getAddress().getPort()
                                            + "/prefix/v1/")
                            .build();
            assertEquals(3, client.decideBlocking(request()).answers().size());
            assertEquals(
                    "The screen is broken",
                    MAPPER.readTree(captured.get()).get("input").textValue());
        } finally {
            transport.close();
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

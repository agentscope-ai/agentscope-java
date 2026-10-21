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
package io.agentscope.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.netty.channel.ChannelOption;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

@SpringBootTest(
        classes = GatewayApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayUpstreamRecoveryTest {
    private static final AtomicReference<
                    BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>>>
            HANDLER = new AtomicReference<>();
    private static final DisposableServer UPSTREAM =
            HttpServer.create()
                    .host("127.0.0.1")
                    .port(0)
                    .handle((request, response) -> HANDLER.get().apply(request, response))
                    .bindNow();

    @Value("${local.server.port}")
    int port;

    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicInteger deliveries = new AtomicInteger();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry properties) {
        properties.add("builder.session-portal-url", () -> "http://127.0.0.1:" + UPSTREAM.port());
        // Exercise eviction promptly while deployment keeps its existing idle-pool policy.
        properties.add(
                "spring.cloud.gateway.server.webflux.httpclient.pool.max-idle-time", () -> "1s");
        properties.add(
                "spring.cloud.gateway.server.webflux.httpclient.pool.eviction-interval",
                () -> "1s");
    }

    @BeforeEach
    void reset() {
        HANDLER.set((request, response) -> response.status(404).send());
    }

    @AfterAll
    static void close() {
        UPSTREAM.disposeNow();
    }

    @Test
    void responseLostAfterCreationReplaysSameBodyAndKeyWithoutCreatingAnotherSession()
            throws Exception {
        var sessions = new ConcurrentHashMap<String, String>();
        HANDLER.set(
                (request, response) ->
                        request.receive()
                                .aggregate()
                                .asString()
                                .flatMap(
                                        body -> {
                                            int delivery = deliveries.incrementAndGet();
                                            bodies.add(body);
                                            String key =
                                                    request.requestHeaders().get("Idempotency-Key");
                                            keys.add(key);
                                            String id =
                                                    sessions.computeIfAbsent(
                                                            key, ignored -> "session-one");
                                            assertThat(
                                                            request.requestHeaders()
                                                                    .get(
                                                                            "X-Builder-Internal-Token"))
                                                    .isNull();
                                            if (delivery == 1) return disconnect(request);
                                            return response.status(201)
                                                    .sendString(Mono.just(id))
                                                    .then();
                                        }));
        String body = "{\"agent\":\"agent-one\",\"title\":\"same request\"}";
        var response = post("/v1/sessions", "stable-key", body);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).isEqualTo("session-one");
        assertThat(deliveries).hasValue(2);
        assertThat(bodies).containsExactly(body, body);
        assertThat(keys).containsExactly("stable-key", "stable-key");
        assertThat(sessions).hasSize(1);
    }

    @Test
    void resetWithoutIdempotencyKeyReturnsStructured502AndDoesNotReplay() throws Exception {
        failingUpstream();
        var response = post("/v1/sessions", null, "{\"agent\":\"one\"}");
        assertFailure(response);
        assertThat(deliveries).hasValue(1);
    }

    @Test
    void persistentResetsStopAfterOneRetry() throws Exception {
        failingUpstream();
        assertFailure(post("/v1/sessions", "stable-key", "{}"));
        assertThat(deliveries).hasValue(2);
    }

    @Test
    void legacySessionCreationAlsoSupportsProtectedReplay() throws Exception {
        HANDLER.set(
                (request, response) ->
                        request.receive()
                                .aggregate()
                                .asString()
                                .flatMap(
                                        body ->
                                                deliveries.incrementAndGet() == 1
                                                        ? disconnect(request)
                                                        : response.sendString(
                                                                        Mono.just("legacy-session"))
                                                                .then()));
        assertThat(post("/api/v1/agent-sessions", "legacy-key", "{}").body())
                .isEqualTo("legacy-session");
        assertThat(deliveries).hasValue(2);
    }

    @Test
    void business500KeepsItsOriginalStatusAndBodyWithoutReplay() throws Exception {
        HANDLER.set(
                (request, response) ->
                        request.receive()
                                .aggregate()
                                .asString()
                                .flatMap(
                                        body -> {
                                            deliveries.incrementAndGet();
                                            return response.status(500)
                                                    .sendString(Mono.just("backend-error"))
                                                    .then();
                                        }));
        var response = post("/v1/sessions", "stable-key", "{}");
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo("backend-error");
        assertThat(deliveries).hasValue(1);
    }

    @Test
    void keyedTurnRequestsAreNotReplayed() throws Exception {
        failingUpstream();
        assertFailure(post("/v1/sessions/one/turns", "turn-key", "{}"));
        assertThat(deliveries).hasValue(1);
    }

    @Test
    void chunkedRequestsKeepStreamingAndAreNotReplayed() throws Exception {
        failingUpstream();
        var request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/sessions"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Idempotency-Key", "stable-key")
                        .POST(
                                HttpRequest.BodyPublishers.ofInputStream(
                                        () ->
                                                new ByteArrayInputStream(
                                                        "{}".getBytes(StandardCharsets.UTF_8))))
                        .build();
        assertFailure(client.send(request, HttpResponse.BodyHandlers.ofString()));
        assertThat(deliveries).hasValue(1);
    }

    @Test
    void largeBodiesKeepStreamingAndAreNotReplayed() throws Exception {
        failingUpstream();
        assertFailure(post("/v1/sessions", "stable-key", "x".repeat(2 * 1024 * 1024 + 1)));
        assertThat(deliveries).hasValue(1);
    }

    @Test
    void idleUpstreamConnectionsAreEvictedWithoutAnotherRequest() throws Exception {
        var closed = new AtomicInteger();
        HANDLER.set(
                (request, response) -> {
                    request.withConnection(
                            connection ->
                                    connection
                                            .onDispose()
                                            .subscribe(
                                                    ignored -> {},
                                                    ignored -> {},
                                                    closed::incrementAndGet));
                    return request.receive()
                            .aggregate()
                            .asString()
                            .flatMap(body -> response.sendString(Mono.just("ok")).then());
                });
        assertThat(post("/v1/sessions", null, "{}").body()).isEqualTo("ok");
        await().atMost(Duration.ofSeconds(9)).untilAtomic(closed, Matchers.greaterThan(0));
    }

    private void failingUpstream() {
        HANDLER.set(
                (request, response) ->
                        request.receive()
                                .aggregate()
                                .asString()
                                .flatMap(
                                        body -> {
                                            deliveries.incrementAndGet();
                                            return disconnect(request);
                                        }));
    }

    private static Mono<Void> disconnect(HttpServerRequest request) {
        request.withConnection(
                connection -> {
                    connection.channel().config().setOption(ChannelOption.SO_LINGER, 0);
                    connection.dispose();
                });
        return Mono.empty();
    }

    private HttpResponse<String> post(String path, String key, String body) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("X-Builder-Internal-Token", "must-be-stripped")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) request.header("Idempotency-Key", key);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertFailure(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body())
                .contains("\"code\":\"upstream_unavailable\"", "same Idempotency-Key");
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
    }
}

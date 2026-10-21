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

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Regression for #3669: browser WebSocket to /api/v1/events through the gateway. */
@SpringBootTest(
        classes = GatewayApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayEventsWebSocketTest {

    // Stand-in for the control plane hub: same-origin check against the Host it receives.
    private static final DisposableServer CONTROL =
            HttpServer.create()
                    .host("127.0.0.1")
                    .port(0)
                    .route(
                            routes ->
                                    routes.get(
                                            "/api/v1/events",
                                            (request, response) -> {
                                                String origin =
                                                        request.requestHeaders()
                                                                .get(HttpHeaders.ORIGIN);
                                                String host =
                                                        request.requestHeaders()
                                                                .get(HttpHeaders.HOST);
                                                if (origin != null
                                                        && !origin.equals("http://" + host)) {
                                                    return response.status(403).send();
                                                }
                                                return response.sendWebsocket(
                                                        (in, out) ->
                                                                out.sendString(Mono.just("ready"))
                                                                        .then());
                                            }))
                    .bindNow();

    @LocalServerPort int port;

    @DynamicPropertySource
    static void planes(DynamicPropertyRegistry registry) {
        registry.add("builder.control-plane-url", () -> "http://127.0.0.1:" + CONTROL.port());
        registry.add("builder.data-plane-url", () -> "http://127.0.0.1:1");
        registry.add("builder.scheduler-plane-url", () -> "http://127.0.0.1:1");
    }

    @AfterAll
    static void stopControl() {
        CONTROL.disposeNow();
    }

    @Test
    void sameOriginBrowserSocketReachesControlPlane() {
        assertThat(firstMessage("http://localhost:" + port)).isEqualTo("ready");
    }

    @Test
    void crossOriginSocketIsStillRejected() {
        assertThat(firstMessage("http://evil.example")).isNull();
    }

    private String firstMessage(String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        AtomicReference<String> received = new AtomicReference<>();
        new ReactorNettyWebSocketClient()
                .execute(
                        URI.create(
                                "ws://localhost:"
                                        + port
                                        + "/api/v1/events?tenant=default&namespace=dev"),
                        headers,
                        session ->
                                session.receive()
                                        .map(WebSocketMessage::getPayloadAsText)
                                        .next()
                                        .doOnNext(received::set)
                                        .then())
                .onErrorResume(error -> Mono.empty())
                .block(Duration.ofSeconds(10));
        return received.get();
    }
}

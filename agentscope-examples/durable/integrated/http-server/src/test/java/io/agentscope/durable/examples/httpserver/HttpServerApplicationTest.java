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
package io.agentscope.durable.examples.httpserver;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.extensions.durable.http.DurableHttp;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(30)
class HttpServerApplicationTest {
    @TempDir static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("durable.http-server.data-directory", () -> directory.toString());
        registry.add("durable.http-server.namespace", () -> "test");
    }

    @LocalServerPort int port;

    @Autowired DurableRuntime host;

    @Test
    void standardWebApplicationSupportsIndependentClientsAndReplayableSse() throws Exception {
        assertThat(host).isNotNull();
        var base = URI.create("http://127.0.0.1:" + port);
        var key = new SessionKey("test", "http-server");
        String turnId;
        try (var clientA =
                DurableRuntime.builder().transport(new DurableHttp(base.resolve("/api"))).build()) {
            turnId =
                    clientA.createSession(key, "http-server-demo")
                            .submit("request", "hello")
                            .turnId();
        }

        try (var clientB =
                DurableRuntime.builder().transport(new DurableHttp(base.resolve("/api"))).build()) {
            var session = clientB.getSession(key);
            var turn = session.getTurn(turnId);
            assertThat(turn.status().status()).isEqualTo(TurnStatus.QUEUED);
            assertThat(session.history(null, 10).items()).hasSize(1);
            turn.cancel("cancel");
            assertThat(turn.result().status()).isEqualTo(TurnStatus.CANCELLED);
        }

        assertThat(readFirstSseCursor(base, key, turnId)).isNotBlank();
        assertThat(forbiddenNamespaceStatus(base, key, turnId)).isEqualTo(403);
    }

    private static String readFirstSseCursor(URI base, SessionKey key, String turnId)
            throws Exception {
        var response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(eventsUri(base, key, turnId))
                                        .timeout(Duration.ofSeconds(10))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        try (var reader =
                new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String cursor = null;
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.startsWith("id:")) cursor = line.substring(3).stripLeading();
                if (line.startsWith("event:")
                        && "durable".equals(line.substring(6).stripLeading())
                        && cursor != null) return cursor;
            }
        }
        throw new AssertionError("SSE stream contained no durable event");
    }

    private static int forbiddenNamespaceStatus(URI base, SessionKey key, String turnId)
            throws Exception {
        var forbidden = new SessionKey("forbidden", key.sessionId());
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(eventsUri(base, forbidden, turnId))
                                .timeout(Duration.ofSeconds(10))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private static URI eventsUri(URI base, SessionKey key, String turnId) {
        String query =
                "namespace="
                        + encode(key.namespace())
                        + "&sessionId="
                        + encode(key.sessionId())
                        + "&threadId=main&turnId="
                        + encode(turnId);
        return base.resolve("/events?" + query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

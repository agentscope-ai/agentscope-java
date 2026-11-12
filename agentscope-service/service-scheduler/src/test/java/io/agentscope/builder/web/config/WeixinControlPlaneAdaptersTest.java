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
package io.agentscope.builder.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.builder.web.auth.JwtService;
import io.jsonwebtoken.Claims;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.client.WebClient;

class WeixinControlPlaneAdaptersTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Configuration
    @EnableWebFlux
    static class WebConfig {}

    @Test
    void internalLoginResumesPortableSessionsAndRejectsBrowserAuthentication() throws Exception {
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> response = new AtomicReference<>("{\"status\":\"wait\"}");
        AtomicInteger calls = new AtomicInteger();
        provider.createContext(
                "/",
                exchange -> {
                    calls.incrementAndGet();
                    query.set(exchange.getRequestURI().getQuery());
                    String body =
                            exchange.getRequestURI().getPath().endsWith("get_bot_qrcode")
                                    ? "{\"qrcode\":\"private-qr\",\"qrcode_img_content\":\"https://qr\"}"
                                    : response.get();
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        provider.start();
        String url = "http://127.0.0.1:" + provider.getAddress().getPort();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "test", Map.of("builder.internal-token", "internal-secret")));
            JwtService jwt = mock(JwtService.class);
            Claims claims = mock(Claims.class);
            when(jwt.parse("browser-token")).thenReturn(claims);
            when(jwt.extractUserId(claims)).thenReturn("alice");
            when(jwt.extractRoles(claims)).thenReturn(List.of("user"));
            context.registerBean(JwtService.class, () -> jwt);
            context.registerBean(
                    WeixinLoginController.class, () -> new WeixinLoginController(url, 3000));
            context.register(WebConfig.class, SchedulerSecurityConfig.class);
            context.refresh();
            WebTestClient client =
                    WebTestClient.bindToApplicationContext(context)
                            .configureClient()
                            .responseTimeout(Duration.ofSeconds(10))
                            .build();
            String root = "/api/internal/channel-providers/weixin/login";
            client.post().uri(root + "/start").exchange().expectStatus().isUnauthorized();
            client.post()
                    .uri(root + "/start")
                    .header("Authorization", "Bearer browser-token")
                    .exchange()
                    .expectStatus()
                    .isForbidden();
            assertEquals(0, calls.get());
            client.post()
                    .uri(root + "/start")
                    .header("X-Builder-Internal-Token", "internal-secret")
                    .bodyValue(Map.of("botType", "3"))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals("Cache-Control", "no-store")
                    .expectBody()
                    .jsonPath("$.session.qrcode")
                    .isEqualTo("private-qr")
                    .jsonPath("$.session.pollingBaseUrl")
                    .isEqualTo(url)
                    .jsonPath("$.botToken")
                    .doesNotExist();
            // A fresh controller receives only the serialized session, with no login memory.
            var second = new WeixinLoginController(url, 3000);
            var poll =
                    second.poll(new WeixinLoginController.SessionRequest("private-qr", url))
                            .block(Duration.ofSeconds(5));
            assertEquals("wait", poll.getBody().get("status"));
            response.set(
                    "{\"status\":\"confirmed\",\"bot_token\":\"runtime-secret\",\"ilink_bot_id\":\"account\",\"ilink_user_id\":\"user\"}");
            client.post()
                    .uri(root + "/verify")
                    .header("X-Builder-Internal-Token", "internal-secret")
                    .bodyValue(
                            Map.of(
                                    "qrcode",
                                    "private-qr",
                                    "pollingBaseUrl",
                                    url,
                                    "verifyCode",
                                    "verify-secret"))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals("Cache-Control", "no-store")
                    .expectBody()
                    .jsonPath("$.botToken")
                    .isEqualTo("runtime-secret");
            assertTrue(query.get().contains("verify_code=verify-secret"));
            int before = calls.get();
            client.post()
                    .uri(root + "/poll")
                    .header("X-Builder-Internal-Token", "internal-secret")
                    .bodyValue(
                            Map.of(
                                    "qrcode",
                                    "private-qr",
                                    "pollingBaseUrl",
                                    "https://attacker.invalid"))
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody()
                    .jsonPath("$.errorCode")
                    .isEqualTo("weixin_login_operation_failed");
            assertEquals(before, calls.get());
            response.set("private-provider-error runtime-secret");
            var failure =
                    second.poll(new WeixinLoginController.SessionRequest("private-qr", url))
                            .block(Duration.ofSeconds(5));
            assertEquals(502, failure.getStatusCode().value());
            assertFalse(failure.toString().contains("runtime-secret"));
        } finally {
            provider.stop(0);
        }
    }

    @Test
    void credentialAdapterUsesExactChannelAndRevisionAndSanitizesFailures() throws Exception {
        HttpServer control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> response =
                new AtomicReference<>("{\"botToken\":\"runtime-secret\",\"revision\":4}");
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<JsonNode> body = new AtomicReference<>();
        control.createContext(
                "/",
                exchange -> {
                    path.set(exchange.getRequestURI().getRawPath());
                    body.set(JSON.readTree(exchange.getRequestBody()));
                    byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        control.start();
        try {
            var provider =
                    new WeixinControlPlaneCredentialProvider(
                            WebClient.create("http://127.0.0.1:" + control.getAddress().getPort()),
                            JSON,
                            "channel space",
                            4);
            assertEquals("runtime-secret", provider.current().botToken());
            assertEquals("/api/internal/channels/channel%20space/credential", path.get());
            assertEquals(4, body.get().path("revision").asLong());
            assertFalse(body.get().has("ownerId"));
            response.set("{runtime-secret malformed");
            var error = assertThrows(IllegalStateException.class, provider::current);
            assertEquals("Weixin credential unavailable", error.getMessage());
            assertNull(error.getCause());
        } finally {
            control.stop(0);
        }
    }
}

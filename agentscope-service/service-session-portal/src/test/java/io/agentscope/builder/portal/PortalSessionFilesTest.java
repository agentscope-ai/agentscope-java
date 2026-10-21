/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.portal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.builder.portal.api.PortalSessionFilesController;
import io.agentscope.builder.portal.persistence.PortalSessionEntity;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.portal.session.PortalSessionAccess;
import io.agentscope.builder.portal.session.PortalSessionFiles;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

class PortalSessionFilesTest {
    private final PortalSessionRepository sessions = mock(PortalSessionRepository.class);
    private final PortalSessionAccess access = mock(PortalSessionAccess.class);
    private final PortalSessionEntity session = new PortalSessionEntity();

    private PortalSessionFiles transport(HttpServer server, int maxBytes) {
        session.id = "session";
        session.ownerId = "frozen-owner";
        session.state = "ready";
        session.lifecycle = "active";
        session.runtimeKind = "managed";
        when(sessions.findById(session.id)).thenReturn(Optional.of(session));
        return new PortalSessionFiles(
                sessions,
                access,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "private-service-token",
                maxBytes,
                1);
    }

    @Test
    void binaryTransferUsesPrivateOwnerAndPreservesOnlyFileHeaders() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var bytes = "binary\u0000upload".getBytes(StandardCharsets.UTF_8);
        var received = new AtomicInteger();
        server.createContext(
                "/api/internal/runtime/managed/sessions/session/files",
                exchange -> {
                    assertEquals(
                            "private-service-token",
                            exchange.getRequestHeaders().getFirst("X-Builder-Internal-Token"));
                    assertEquals(
                            "frozen-owner",
                            exchange.getRequestHeaders().getFirst("X-Builder-Internal-User"));
                    assertEquals(
                            null, exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
                    assertEquals(null, exchange.getRequestHeaders().getFirst("X-API-Key"));
                    if (exchange.getRequestMethod().equals("POST")) {
                        assertArrayEquals(bytes, exchange.getRequestBody().readAllBytes());
                        assertEquals(
                                "file-key",
                                exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                        received.incrementAndGet();
                        byte[] reply = "{\"file_id\":\"file\"}".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(201, reply.length);
                        exchange.getResponseBody().write(reply);
                    } else {
                        exchange.getResponseHeaders()
                                .set("Content-Type", "application/octet-stream");
                        exchange.getResponseHeaders().set("ETag", "\"digest\"");
                        exchange.getResponseHeaders()
                                .set("Content-Disposition", "attachment; filename=file.bin");
                        exchange.getResponseHeaders().set("Set-Cookie", "private=secret");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                    exchange.close();
                });
        server.start();
        try {
            var client =
                    WebTestClient.bindToController(
                                    new PortalSessionFilesController(transport(server, 1024)))
                            .build();
            client.post()
                    .uri("/api/v1/agent-sessions/session/files")
                    .header("Idempotency-Key", "file-key")
                    .header("X-File-Name", "file.bin")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer original-human")
                    .bodyValue(bytes)
                    .exchange()
                    .expectStatus()
                    .isCreated();
            assertEquals(1, received.get());
            client.post()
                    .uri("/api/v1/agent-sessions/session/files")
                    .header("Idempotency-Key", "bad-name")
                    .header("X-File-Name", "%QQ")
                    .bodyValue(bytes)
                    .exchange()
                    .expectStatus()
                    .isBadRequest();
            assertEquals(1, received.get());
            client.get()
                    .uri("/api/v1/agent-sessions/session/files/file_" + "a".repeat(64) + "/content")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals("ETag", "\"digest\"")
                    .expectHeader()
                    .doesNotExist("Set-Cookie")
                    .expectBody()
                    .consumeWith(reply -> assertArrayEquals(bytes, reply.getResponseBody()));
            client.get()
                    .uri("/api/v1/agent-sessions/session/files?limit=101")
                    .exchange()
                    .expectStatus()
                    .isBadRequest();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void authorizationCapabilityAndKnownSizeFailBeforeRuntimeOrBodySubscription() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            var files = transport(server, 16);
            var headers = new HttpHeaders();
            headers.setContentLength(17);
            var subscriptions = new AtomicInteger();
            var body =
                    Flux.defer(
                            () -> {
                                subscriptions.incrementAndGet();
                                return Flux.<DataBuffer>just(
                                        DefaultDataBufferFactory.sharedInstance.wrap(new byte[17]));
                            });
            assertEquals(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            files.forward(
                                                            "session",
                                                            "",
                                                            0,
                                                            0,
                                                            HttpMethod.POST,
                                                            headers,
                                                            body,
                                                            new MockServerHttpResponse())
                                                    .block())
                            .getStatusCode());
            session.runtimeKind = "hosted-runtime";
            assertEquals(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            files.forward(
                                                            "session",
                                                            "",
                                                            0,
                                                            0,
                                                            HttpMethod.POST,
                                                            headers,
                                                            body,
                                                            new MockServerHttpResponse())
                                                    .block())
                            .getStatusCode());
            doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                    .when(access)
                    .authorize(eq("session"), any(), eq("interact"));
            assertEquals(
                    HttpStatus.FORBIDDEN,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            files.forward(
                                                            "session",
                                                            "",
                                                            0,
                                                            0,
                                                            HttpMethod.POST,
                                                            headers,
                                                            body,
                                                            new MockServerHttpResponse())
                                                    .block())
                            .getStatusCode());
            assertEquals(0, subscriptions.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancellationFreesTransferSlotAndUnknownLengthUploadsAreBounded() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newFixedThreadPool(2);
        server.setExecutor(executor);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        server.createContext(
                "/api/internal/runtime/managed/sessions/session/files",
                exchange -> {
                    if (calls.getAndIncrement() == 0) {
                        entered.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    try {
                        exchange.getRequestBody().readAllBytes();
                        exchange.sendResponseHeaders(200, -1);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {
            var files = transport(server, 32);
            var headers = new HttpHeaders();
            var pending =
                    files.forward(
                                    "session",
                                    "",
                                    0,
                                    1,
                                    HttpMethod.GET,
                                    headers,
                                    Flux.empty(),
                                    new MockServerHttpResponse())
                            .toFuture();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(
                    HttpStatus.TOO_MANY_REQUESTS,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            files.forward(
                                                            "session",
                                                            "",
                                                            0,
                                                            1,
                                                            HttpMethod.GET,
                                                            headers,
                                                            Flux.empty(),
                                                            new MockServerHttpResponse())
                                                    .block())
                            .getStatusCode());
            pending.cancel(true);
            files.forward(
                            "session",
                            "",
                            0,
                            1,
                            HttpMethod.GET,
                            headers,
                            Flux.empty(),
                            new MockServerHttpResponse())
                    .block(Duration.ofSeconds(5));
            headers.set("Idempotency-Key", "stream");
            headers.set("X-File-Name", "stream.bin");
            var factory = DefaultDataBufferFactory.sharedInstance;
            var body =
                    Flux.<DataBuffer>just(factory.wrap(new byte[16]), factory.wrap(new byte[17]));
            assertEquals(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            files.forward(
                                                            "session",
                                                            "",
                                                            0,
                                                            0,
                                                            HttpMethod.POST,
                                                            headers,
                                                            body,
                                                            new MockServerHttpResponse())
                                                    .block(Duration.ofSeconds(5)))
                            .getStatusCode());
            files.forward(
                            "session",
                            "",
                            0,
                            1,
                            HttpMethod.GET,
                            headers,
                            Flux.empty(),
                            new MockServerHttpResponse())
                    .block(Duration.ofSeconds(5));
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}

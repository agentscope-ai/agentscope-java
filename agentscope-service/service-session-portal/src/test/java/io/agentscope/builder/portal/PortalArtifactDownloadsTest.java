/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package io.agentscope.builder.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.builder.portal.api.PortalTurnArtifactsController;
import io.agentscope.builder.portal.persistence.PortalResourceEntity;
import io.agentscope.builder.portal.persistence.PortalResourceRepository;
import io.agentscope.builder.portal.persistence.PortalResourceVersionRepository;
import io.agentscope.builder.portal.persistence.PortalSessionEntity;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.portal.persistence.PortalSnapshotRepository;
import io.agentscope.builder.portal.persistence.PortalTurnRepository;
import io.agentscope.builder.portal.session.PortalArtifactDownloads;
import io.agentscope.builder.portal.session.PortalResourceProjection;
import io.agentscope.builder.portal.session.PortalResourceReads;
import io.agentscope.builder.portal.session.PortalRuntimeBackends;
import io.agentscope.builder.portal.session.PortalSessionAccess;
import io.agentscope.builder.portal.session.PortalSessionFiles;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class PortalArtifactDownloadsTest {
    private final PortalSessionRepository sessions = mock(PortalSessionRepository.class);
    private final PortalSessionAccess access = mock(PortalSessionAccess.class);
    private final PortalResourceRepository resources = mock(PortalResourceRepository.class);
    private final PortalResourceProjection projection =
            new PortalResourceProjection(
                    resources,
                    mock(PortalResourceVersionRepository.class),
                    mock(PortalSnapshotRepository.class),
                    new ObjectMapper(),
                    mock(PortalTurnRepository.class));
    private final PortalResourceEntity artifact = new PortalResourceEntity();
    private final PortalSessionEntity session = new PortalSessionEntity();

    private PortalArtifactDownloads transport(HttpServer server) {
        session.id = "session";
        session.runtimeKind = "team";
        artifact.turnId = "turn";
        artifact.data = "{\"data\":{\"artifact_id\":\"artifact\"}}";
        when(sessions.findById("session")).thenReturn(Optional.of(session));
        when(resources.findById(any())).thenReturn(Optional.of(artifact));
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new PortalArtifactDownloads(
                access,
                sessions,
                resources,
                projection,
                mock(PortalSessionFiles.class),
                new PortalRuntimeBackends(sessions, url, url),
                "service-token",
                1);
    }

    @Test
    void streamsBinaryThroughPrivateRouteAndChecksReaderAndTurn() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] bytes = new byte[2 * 1024 * 1024 + 17];
        bytes[bytes.length - 1] = 42;
        var requests = new AtomicInteger();
        server.createContext(
                "/api/internal/runtime/sessions/session/turns/turn/artifacts/artifact/content",
                exchange -> {
                    assertEquals(
                            "service-token",
                            exchange.getRequestHeaders().getFirst("X-Builder-Internal-Token"));
                    assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                    assertNull(exchange.getRequestHeaders().getFirst("X-API-Key"));
                    requests.incrementAndGet();
                    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                    exchange.getResponseHeaders().set("Set-Cookie", "secret=value");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            var downloads = transport(server);
            var client =
                    WebTestClient.bindToController(
                                    new PortalTurnArtifactsController(
                                            mock(PortalResourceReads.class), downloads))
                            .configureClient()
                            .codecs(
                                    codecs ->
                                            codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                            .build();
            client.get()
                    .uri("/api/v1/agent-sessions/session/turns/turn/artifacts/artifact/content")
                    .header("Authorization", "Bearer original")
                    .header("X-API-Key", "application")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .doesNotExist("Set-Cookie")
                    .expectBody(byte[].class)
                    .isEqualTo(bytes);
            assertEquals(1, requests.get());
            client.get()
                    .uri("/api/v1/agent-sessions/session/turns/wrong/artifacts/artifact/content")
                    .exchange()
                    .expectStatus()
                    .isNotFound();
            doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                    .when(access)
                    .authorize(eq("session"), any(), eq("read"));
            client.get()
                    .uri("/api/v1/agent-sessions/session/turns/turn/artifacts/artifact/content")
                    .exchange()
                    .expectStatus()
                    .isForbidden();
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void holdsTransferSlotUntilWriteCompletesOrCancels() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.sendResponseHeaders(200, 1);
                    exchange.getResponseBody().write(1);
                    exchange.close();
                });
        server.start();
        try {
            var downloads = transport(server);
            var held = new CountDownLatch(1);
            var slow = new MockServerHttpResponse();
            slow.setWriteHandler(
                    body -> Mono.<Void>never().doOnRequest(demand -> held.countDown()));
            var first =
                    downloads
                            .download("session", "turn", "artifact", new HttpHeaders(), slow)
                            .subscribe();
            assertTrue(held.await(5, TimeUnit.SECONDS));
            var error =
                    assertThrows(
                            ResponseStatusException.class,
                            () ->
                                    downloads
                                            .download(
                                                    "session",
                                                    "turn",
                                                    "artifact",
                                                    new HttpHeaders(),
                                                    new MockServerHttpResponse())
                                            .block(Duration.ofSeconds(5)));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatusCode());
            first.dispose();
            downloads
                    .download(
                            "session",
                            "turn",
                            "artifact",
                            new HttpHeaders(),
                            new MockServerHttpResponse())
                    .block(Duration.ofSeconds(5));
        } finally {
            server.stop(0);
        }
    }
}

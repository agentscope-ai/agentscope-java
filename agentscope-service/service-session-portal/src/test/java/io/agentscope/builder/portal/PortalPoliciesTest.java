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
package io.agentscope.builder.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.builder.portal.persistence.PortalPolicyChangeRepository;
import io.agentscope.builder.portal.persistence.PortalPolicyEntity;
import io.agentscope.builder.portal.persistence.PortalPolicyRepository;
import io.agentscope.builder.portal.persistence.PortalSessionPermissionRepository;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.portal.session.PortalPolicies;
import io.agentscope.builder.portal.session.PortalPolicyChanges;
import io.agentscope.builder.portal.session.PortalRegistryReads;
import io.agentscope.builder.portal.session.PortalSessionAccess;
import io.agentscope.builder.portal.session.PortalSessionProvisioning;
import io.agentscope.builder.portal.session.PortalTurnAdmission;
import io.agentscope.builder.portal.session.PortalTurnControl;
import io.agentscope.builder.runtime.protocol.RuntimeSessionAdmission;
import io.agentscope.builder.runtime.protocol.RuntimeSessionBootstrap;
import io.agentscope.builder.web.auth.JwtService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        classes = SessionPortalApp.class,
        properties = {
            "builder.local-dev=false",
            "builder.internal-token=shared-policy-fixture-internal-token-at-least-32",
            "builder.portal.provisioning-poll-ms=3600000",
            "builder.portal.turn-delivery-poll-ms=3600000",
            "builder.portal.interaction-delivery-poll-ms=3600000",
            "builder.portal.policy-refresh-poll-ms=3600000",
            "builder.portal.policy-changes-poll-ms=3600000",
            "spring.datasource.url=jdbc:h2:mem:portal-policies;DB_CLOSE_DELAY=-1"
        })
@ActiveProfiles("test")
class PortalPoliciesTest extends PortalInfrastructureTestSupport {
    private static final ObjectMapper WIRE = new ObjectMapper();
    private static final AtomicReference<Map<String, Object>> RESPONSE = new AtomicReference<>();
    private static final AtomicReference<Map<String, Object>> RESOLVED = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> STARTED = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> RELEASE = new AtomicReference<>();
    private static final AtomicInteger STATUS = new AtomicInteger(200);
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final HttpServer CONTROL = control();

    @Autowired PortalPolicies policies;
    @Autowired PortalPolicyRepository policyRows;
    @Autowired PortalSessionPermissionRepository grants;
    @Autowired PortalSessionRepository sessions;
    @Autowired PortalSessionProvisioning provisioning;
    @Autowired PortalSessionAccess access;
    @Autowired PortalRegistryReads registry;
    @Autowired PortalTurnAdmission admission;
    @Autowired PortalTurnControl controls;
    @Autowired ObjectMapper mapper;
    @Autowired TransactionTemplate tx;
    @Autowired JwtService jwt;

    @DynamicPropertySource
    static void controlUrl(DynamicPropertyRegistry properties) {
        properties.add(
                "builder.control-plane-url",
                () -> "http://127.0.0.1:" + CONTROL.getAddress().getPort());
    }

    @AfterAll
    static void stopControl() {
        CONTROL.stop(0);
    }

    @Test
    void oneRefreshUpdatesEverySessionAndExpiredPoliciesFailClosedWithoutExtendingValidity()
            throws Exception {
        String scope = "a".repeat(64);
        long policyCount = policyRows.count();
        long grantCount = grants.count();
        var original = policy(scope, List.of("read", "invoke", "cancel"));
        String first = ready(original);
        String second = ready(original);
        assertEquals(scope, sessions.findById(first).orElseThrow().policyId);
        assertEquals(scope, sessions.findById(second).orElseThrow().policyId);
        assertEquals(policyCount + 1, policyRows.count());
        assertEquals(grantCount + 1, grants.count());
        var headers = human(scope);
        assertEquals(
                2,
                ((List<?>) registry.sessions(null, 10, "active", null, headers).get("data"))
                        .size());
        headers.set("Idempotency-Key", "shared-policy-turn");
        String turn =
                (String)
                        admission
                                .accept(first, headers, Map.of("message", "before revoke"))
                                .get("id");
        RESPONSE.set(policy(scope, List.of("read", "cancel")));
        markDue(scope, false);
        int calls = CALLS.get();
        policies.refresh(scope);
        assertEquals(calls + 1, CALLS.get());
        assertEquals(2, policyRows.findById(scope).orElseThrow().policyVersion);
        assertEquals(grantCount + 1, grants.count());
        assertNull(policyRows.findById(scope).orElseThrow().failureCode);
        for (String session : List.of(first, second)) {
            access.authorize(session, headers, "read");
            assertEquals(
                    HttpStatus.NOT_FOUND,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () -> access.authorize(session, headers, "invoke"))
                            .getStatusCode());
        }
        assertEquals("cancel", controls.cancel(first, turn, headers).kind);
        markDue(scope, true);
        STATUS.set(503);
        calls = CALLS.get();
        policies.refresh(scope);
        assertEquals(calls + 1, CALLS.get());
        var expired = policyRows.findById(scope).orElseThrow();
        assertEquals(0, expired.validUntil);
        assertEquals(2, expired.policyVersion);
        assertEquals("policy_refresh_failed", expired.failureCode);
        assertTrue(expired.nextAttemptAt > System.currentTimeMillis());
        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(
                                ResponseStatusException.class,
                                () -> access.authorize(first, headers, "read"))
                        .getStatusCode());
        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(
                                ResponseStatusException.class,
                                () -> registry.sessions(null, 10, "active", null, headers))
                        .getStatusCode());
        STATUS.set(200);
    }

    @Test
    void resourceOverlayIsSharedEncryptedAndAdvancesWithThePolicyRevision() throws Exception {
        String scope = "d".repeat(64);
        var original = policy(scope, List.of("read", "invoke", "cancel"));
        String first = ready(original);
        String second = ready(original);
        var configuration = new LinkedHashMap<>(resolvedFixture());
        configuration.put(
                "vaultCredentials",
                List.of(Map.of("id", "rotated", "revision", 2, "secret", "new-resource-secret")));
        try {
            RESPONSE.set(original);
            RESOLVED.set(configuration);
            STATUS.set(200);
            markDue(scope, false);
            int before = CALLS.get();
            policies.refresh(scope);
            assertEquals(before + 1, CALLS.get());
            assertEquals(2, policyRows.findById(scope).orElseThrow().policyVersion);
            assertFalse(
                    policyRows
                            .findById(scope)
                            .orElseThrow()
                            .policy
                            .contains("new-resource-secret"));
            assertFalse(
                    policyRows
                            .findById(scope)
                            .orElseThrow()
                            .configurationOverlay
                            .contains("new-resource-secret"));
            for (String id : List.of(first, second)) {
                var effective = policies.read(sessions.findById(id).orElseThrow());
                assertEquals(2L, effective.get("policy_version"));
                assertFalse(effective.containsKey("configuration_overlay"));
                var overlay = policies.configurationOverlay(sessions.findById(id).orElseThrow(), 2);
                assertEquals(
                        configuration.get("vaultCredentials"),
                        overlay.configuration().get("vaultCredentials"));
                assertEquals(3, overlay.configuration().size());
                assertFalse(overlay.configuration().containsKey("agentSnapshot"));
            }
        } finally {
            RESOLVED.set(null);
        }
    }

    @Test
    void lateRefreshCannotOverwriteAReplacementClaim() throws Exception {
        String scope = "b".repeat(64);
        ready(policy(scope, List.of("read", "invoke", "cancel")));
        RESPONSE.set(policy(scope, List.of("read")));
        STATUS.set(200);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        STARTED.set(started);
        RELEASE.set(release);
        var worker = Executors.newSingleThreadExecutor();
        try {
            markDue(scope, false);
            var pending = worker.submit(() -> policies.refresh(scope));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            int calls = CALLS.get();
            policies.refresh(scope);
            assertEquals(calls, CALLS.get(), "Another replica must observe the durable claim");
            tx.executeWithoutResult(
                    ignored -> {
                        var row = policyRows.lockById(scope).orElseThrow();
                        row.claimToken = "replacement-worker";
                        row.claimUntil = System.currentTimeMillis() + 20000;
                        policyRows.saveAndFlush(row);
                    });
            release.countDown();
            pending.get(5, TimeUnit.SECONDS);
            var retained = policyRows.findById(scope).orElseThrow();
            assertEquals(1, retained.policyVersion);
            assertEquals("replacement-worker", retained.claimToken);
        } finally {
            release.countDown();
            STARTED.set(null);
            RELEASE.set(null);
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Autowired PortalPolicyChanges changes;
    @Autowired PortalPolicyChangeRepository changeRows;

    @Test
    void invalidationIsScopedBoundedAndRedeliveryCannotRevokeARefreshedPolicy() throws Exception {
        String scope = "c".repeat(64);
        ready(policy(scope, List.of("read", "invoke", "cancel")));
        var original = policyRows.findById(scope).orElseThrow();
        var batch = new ArrayList<PortalPolicyEntity>();
        for (int i = 1; i <= 130; i++) {
            var row = new PortalPolicyEntity();
            row.id = String.format("%064x", i);
            row.tenant = original.tenant;
            row.namespace = original.namespace;
            row.applicationId = "batch-app";
            row.policy = original.policy;
            row.policyVersion = 1;
            row.validUntil = original.validUntil;
            row.nextAttemptAt = Long.MAX_VALUE;
            row.createdAt = original.createdAt;
            row.updatedAt = original.updatedAt;
            batch.add(row);
        }
        tx.executeWithoutResult(ignored -> policyRows.saveAllAndFlush(batch));
        String rawId = UUID.randomUUID().toString();
        var event =
                Map.of(
                        "source",
                        "control",
                        "id",
                        rawId,
                        "schema_version",
                        1,
                        "tenant",
                        original.tenant,
                        "namespace",
                        original.namespace,
                        "application_id",
                        "batch-app");
        String id = changes.receive(event);
        assertEquals(id, changes.receive(event));
        changes.apply(id);
        assertEquals("pending", changeRows.findById(id).orElseThrow().state);
        assertEquals(0, policyRows.findById(batch.get(127).id).orElseThrow().validUntil);
        assertTrue(policyRows.findById(batch.get(128).id).orElseThrow().validUntil > 0);
        assertEquals(original.validUntil, policyRows.findById(scope).orElseThrow().validUntil);
        changes.apply(id);
        assertEquals("applied", changeRows.findById(id).orElseThrow().state);
        assertEquals(0, policyRows.findById(batch.get(129).id).orElseThrow().validUntil);
        tx.executeWithoutResult(
                ignored -> {
                    var row = policyRows.lockById(batch.get(0).id).orElseThrow();
                    row.validUntil = 99999;
                    policyRows.saveAndFlush(row);
                });
        assertEquals(id, changes.receive(event));
        changes.apply(id);
        assertEquals(99999, policyRows.findById(batch.get(0).id).orElseThrow().validUntil);
        var conflict = new LinkedHashMap<String, Object>(event);
        conflict.put("application_id", "other-app");
        assertEquals(
                409,
                assertThrows(ResponseStatusException.class, () -> changes.receive(conflict))
                        .getStatusCode()
                        .value());
    }

    @Test
    void scheduledRefreshRecoversInvalidatedManagedSessionsInCreatedState() throws Exception {
        String scope = "e".repeat(64);
        String session = ready(policy(scope, List.of("read", "invoke", "cancel")));
        tx.executeWithoutResult(
                ignored -> {
                    var row = sessions.lockById(session).orElseThrow();
                    row.state = "created";
                    sessions.saveAndFlush(row);
                });
        markDue(scope, true);
        RESPONSE.set(policy(scope, List.of("read", "invoke", "cancel")));
        STATUS.set(200);
        policies.refreshDue();
        var refreshed = policyRows.findById(scope).orElseThrow();
        assertEquals(2, refreshed.policyVersion);
        assertTrue(refreshed.validUntil > System.currentTimeMillis());
        assertNull(refreshed.failureCode);
        access.authorize(session, human(scope), "read");
    }

    private void markDue(String scope, boolean expired) {
        tx.executeWithoutResult(
                ignored -> {
                    var row = policyRows.lockById(scope).orElseThrow();
                    row.nextAttemptAt = 0;
                    if (expired) row.validUntil = 0;
                    policyRows.saveAndFlush(row);
                });
    }

    private String ready(Map<String, Object> policy) throws Exception {
        var wire =
                (ObjectNode)
                        mapper.readTree(
                                Files.readString(
                                        Path.of(
                                                "..",
                                                "service-common",
                                                "src",
                                                "test",
                                                "resources",
                                                "runtime-protocol",
                                                "v1",
                                                "bootstrap.json")));
        String id = UUID.randomUUID().toString();
        ((ObjectNode) wire.get("context")).put("sessionId", id).put("commandId", "bootstrap:" + id);
        ((ObjectNode) wire.get("configuration").get("session")).put("id", id);
        ((ObjectNode) wire.get("configuration"))
                .set(
                        "environment",
                        mapper.valueToTree(
                                Map.of(
                                        "id",
                                        "environment-1",
                                        "type",
                                        "local",
                                        "config",
                                        Map.of())));
        provisioning.stage(
                new RuntimeSessionAdmission(
                        mapper.treeToValue(wire, RuntimeSessionBootstrap.class),
                        policy,
                        Map.of("id", id)));
        tx.executeWithoutResult(
                ignored -> {
                    var row = sessions.lockById(id).orElseThrow();
                    row.state = "ready";
                    row.bootstrapPayload = null;
                    sessions.saveAndFlush(row);
                });
        return id;
    }

    private static Map<String, Object> policy(String scope, List<String> scopes) {
        return Map.of(
                "scope_id",
                scope,
                "tenant",
                "tenant-1",
                "namespace",
                "default",
                "enabled",
                true,
                "valid_until",
                System.currentTimeMillis() + 60000,
                "humans",
                Map.of("shared-reader-" + scope.charAt(0), scopes),
                "credentials",
                List.of(),
                "application",
                Map.of(
                        "id",
                        "app-1",
                        "version",
                        1,
                        "maxConcurrent",
                        1,
                        "tokenBudget",
                        1000,
                        "tokensUsed",
                        0));
    }

    private HttpHeaders human(String scope) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(
                jwt.generate("shared-reader-" + scope.charAt(0), "fixture", List.of("user")));
        return headers;
    }

    private static HttpServer control() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/api/internal/runtime-sessions/",
                    exchange -> {
                        if (!"shared-policy-fixture-internal-token-at-least-32"
                                        .equals(
                                                exchange.getRequestHeaders()
                                                        .getFirst("X-Builder-Internal-Token"))
                                || !exchange.getRequestURI()
                                        .getQuery()
                                        .contains("configuration=true")) {
                            exchange.sendResponseHeaders(401, -1);
                            exchange.close();
                            return;
                        }
                        CALLS.incrementAndGet();
                        var started = STARTED.get();
                        var release = RELEASE.get();
                        if (started != null && release != null) {
                            started.countDown();
                            try {
                                release.await(4, TimeUnit.SECONDS);
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        byte[] body =
                                WIRE.writeValueAsBytes(
                                        Map.of(
                                                "managed",
                                                true,
                                                "policy",
                                                RESPONSE.get(),
                                                "resolved",
                                                RESOLVED.get() == null
                                                        ? resolvedFixture()
                                                        : RESOLVED.get()));
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(STATUS.get(), body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
            server.start();
            return server;
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolvedFixture() throws IOException {
        var configuration =
                (Map<String, Object>)
                        WIRE.readValue(
                                        Files.readString(
                                                Path.of(
                                                        "..",
                                                        "service-common",
                                                        "src",
                                                        "test",
                                                        "resources",
                                                        "runtime-protocol",
                                                        "v1",
                                                        "bootstrap.json")),
                                        Map.class)
                                .get("configuration");
        configuration.put(
                "environment", Map.of("id", "environment-1", "type", "local", "config", Map.of()));
        return configuration;
    }
}

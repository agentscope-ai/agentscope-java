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
package io.agentcore.managed.server.agentservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.agentservice.session.AgentServicePolicies;
import io.agentcore.managed.server.agentservice.session.AgentServiceRuntimeBackends;
import io.agentcore.managed.server.agentservice.session.AgentServiceSessionPermissions;
import io.agentcore.managed.server.agentservice.session.AgentServiceSessionProvisioning;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.RedisManagedInfrastructure;
import io.agentcore.managed.server.runtime.managed.RocketMqManagedMessageBus;
import io.agentcore.managed.server.runtime.protocol.RuntimeBootstrapDigest;
import io.agentcore.managed.server.runtime.protocol.RuntimeProtocol;
import io.agentcore.managed.server.runtime.protocol.RuntimeSessionAdmission;
import io.agentcore.managed.server.runtime.protocol.RuntimeSessionBootstrap;
import io.agentcore.managed.server.runtime.protocol.RuntimeSessionReady;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import jakarta.persistence.EntityManager;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@TestPropertySource(locations = "classpath:application-test.properties")
@SpringBootTest(
        classes = AgentServiceApp.class,
        properties = {
            "managed-agents.internal-token=provision-test-internal-token-at-least-32-characters",
            "managed-agents.agent-service.provisioning-poll-ms=3600000",
            "spring.datasource.url=jdbc:h2:mem:portal-provisioning;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        })
@ActiveProfiles("test")
class AgentServiceSessionProvisioningTest {
    private static final ObjectMapper WIRE = new ObjectMapper();
    private static final AtomicInteger DELIVERIES = new AtomicInteger();
    private static final Map<String, RuntimeSessionReady> COMMITTED = new ConcurrentHashMap<>();
    private static final HttpServer RUNTIME = runtime();

    @Autowired AgentServiceSessionProvisioning sessions;
    @Autowired AgentServiceSessionRepository repository;
    @Autowired TransactionTemplate tx;
    @Autowired VaultCrypto crypto;
    @Autowired ObjectMapper mapper;
    @Autowired AgentServiceSessionPermissions permissions;
    @Autowired AgentServicePolicies policies;
    @Autowired ManagedAdmissionStore managed;
    @Autowired EntityManager entityManager;
    @MockitoBean RocketMqManagedMessageBus managedMessageBus;
    @MockitoBean RedisManagedInfrastructure managedRedis;

    @DynamicPropertySource
    static void runtimeUrl(DynamicPropertyRegistry properties) {
        properties.add(
                "managed-agents.agent-harness-runtime-url",
                () -> "http://127.0.0.1:" + RUNTIME.getAddress().getPort());
    }

    @AfterAll
    static void stopRuntime() {
        RUNTIME.stop(0);
    }

    @Test
    void concurrentManagedRegistrationCannotReplaceTheWinnerCiphertext() throws Exception {
        var admission = admission();
        String id = admission.bootstrap().context().sessionId();
        var observed = new CountDownLatch(2);
        var racedRepository =
                (AgentServiceSessionRepository)
                        Proxy.newProxyInstance(
                                AgentServiceSessionRepository.class.getClassLoader(),
                                new Class<?>[] {AgentServiceSessionRepository.class},
                                (proxy, method, arguments) -> {
                                    Object result;
                                    try {
                                        result = method.invoke(repository, arguments);
                                    } catch (InvocationTargetException failure) {
                                        throw failure.getCause();
                                    }
                                    if ("findById".equals(method.getName())
                                            && id.equals(arguments[0])
                                            && ((Optional<?>) result).isEmpty()) {
                                        observed.countDown();
                                        if (!observed.await(10, TimeUnit.SECONDS))
                                            throw new IllegalStateException(
                                                    "registration race did not start");
                                    }
                                    return result;
                                });
        var concurrent =
                new AgentServiceSessionProvisioning(
                        racedRepository,
                        tx,
                        crypto,
                        mapper,
                        permissions,
                        policies,
                        new AgentServiceRuntimeBackends(
                                repository,
                                "http://127.0.0.1:" + RUNTIME.getAddress().getPort(),
                                "http://127.0.0.1:" + RUNTIME.getAddress().getPort()),
                        managed,
                        entityManager,
                        "provision-test-internal-token-at-least-32-characters");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> concurrent.provision(admission));
            var second = executor.submit(() -> concurrent.provision(admission));
            assertEquals("created", first.get(20, TimeUnit.SECONDS).get("state"));
            assertEquals("created", second.get(20, TimeUnit.SECONDS).get("state"));
            var scope =
                    new ManagedScope(
                            admission.bootstrap().context().tenant(),
                            admission.bootstrap().context().namespace(),
                            id);
            String fixed = managed.session(scope).configuration();
            sessions.provision(admission);
            assertEquals(fixed, managed.session(scope).configuration());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void managedSessionSavesConfigurationWithoutCallingRuntimeOrWaitingForReady() throws Exception {
        var admission = admission();
        String id = admission.bootstrap().context().sessionId();
        assertEquals("created", sessions.provision(admission).get("state"));
        assertNotNull(repository.findById(id).orElseThrow().bootstrapPayload);
        var scope =
                new ManagedScope(
                        admission.bootstrap().context().tenant(),
                        admission.bootstrap().context().namespace(),
                        id);
        var saved = managed.session(scope);
        assertEquals(0, saved.executionEpoch());
        assertNull(saved.currentTurnId());
        assertEquals("created", sessions.readyView(id).get("state"));
        var anotherAgentService =
                new AgentServiceSessionProvisioning(
                        repository,
                        tx,
                        crypto,
                        mapper,
                        permissions,
                        policies,
                        new AgentServiceRuntimeBackends(
                                repository,
                                "http://127.0.0.1:" + RUNTIME.getAddress().getPort(),
                                "http://127.0.0.1:" + RUNTIME.getAddress().getPort()),
                        managed,
                        entityManager,
                        "provision-test-internal-token-at-least-32-characters");
        anotherAgentService.provision(admission);
        assertEquals(saved.configuration(), managed.session(scope).configuration());
        ObjectNode changed = mapper.valueToTree(admission.bootstrap());
        ((ObjectNode) changed.get("configuration").get("agentSnapshot")).put("name", "changed");
        var incompatible =
                new RuntimeSessionAdmission(
                        mapper.treeToValue(changed, RuntimeSessionBootstrap.class),
                        admission.policy(),
                        admission.publicView());
        assertEquals(
                HttpStatus.CONFLICT,
                assertThrows(
                                ResponseStatusException.class,
                                () -> anotherAgentService.stage(incompatible))
                        .getStatusCode());
        assertEquals(
                0,
                DELIVERIES.get(),
                "Managed creation must not contact a runtime bootstrap endpoint");
    }

    private RuntimeSessionAdmission admission() throws Exception {
        ObjectNode fixture =
                (ObjectNode)
                        mapper.readTree(
                                Files.readString(
                                        Path.of(
                                                "..",
                                                "common",
                                                "src",
                                                "test",
                                                "resources",
                                                "runtime-protocol",
                                                "v1",
                                                "bootstrap.json")));
        String id = UUID.randomUUID().toString();
        ((ObjectNode) fixture.get("context")).put("sessionId", id);
        ((ObjectNode) fixture.get("context")).put("commandId", UUID.randomUUID().toString());
        ((ObjectNode) fixture.get("configuration").get("session")).put("id", id);
        return new RuntimeSessionAdmission(
                mapper.treeToValue(fixture, RuntimeSessionBootstrap.class),
                Map.of("tenant", "tenant-1", "namespace", "default"),
                Map.of("id", id));
    }

    private static HttpServer runtime() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/api/internal/runtime/sessions/bootstrap",
                    exchange -> {
                        if (!"provision-test-internal-token-at-least-32-characters"
                                .equals(
                                        exchange.getRequestHeaders()
                                                .getFirst("X-Managed-Agents-Internal-Token"))) {
                            exchange.sendResponseHeaders(401, -1);
                            exchange.close();
                            return;
                        }
                        var request =
                                WIRE.readValue(
                                        exchange.getRequestBody(), RuntimeSessionBootstrap.class);
                        var receipt =
                                new RuntimeSessionReady(
                                        RuntimeProtocol.VERSION,
                                        request.context().sessionId(),
                                        request.context().commandId(),
                                        request.configurationRevision(),
                                        RuntimeBootstrapDigest.of(WIRE, request));
                        COMMITTED.putIfAbsent(receipt.sessionId(), receipt);
                        // Simulate a successful commit followed by a lost acknowledgement.
                        if (DELIVERIES.incrementAndGet() == 1) {
                            exchange.sendResponseHeaders(503, -1);
                        } else {
                            byte[] body =
                                    WIRE.writeValueAsBytes(COMMITTED.get(receipt.sessionId()));
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, body.length);
                            exchange.getResponseBody().write(body);
                        }
                        exchange.close();
                    });
            server.start();
            return server;
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}

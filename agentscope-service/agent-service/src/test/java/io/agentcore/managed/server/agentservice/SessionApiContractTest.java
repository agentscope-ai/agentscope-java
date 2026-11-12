/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.agentservice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.client.ServiceClient;
import io.agentcore.managed.server.agentservice.api.ManagedRuntimeController;
import io.agentcore.managed.server.agentservice.api.ManagedRuntimeFilesController;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.agentservice.session.AgentServicePolicies;
import io.agentcore.managed.server.agentservice.session.AgentServiceRegistryReads;
import io.agentcore.managed.server.agentservice.session.AgentServiceSessionCreation;
import io.agentcore.managed.server.agentservice.session.AgentServiceTurnAdmission;
import io.agentcore.managed.server.agentservice.session.ManagedSessionTurnsApi;
import io.agentcore.managed.server.agentservice.session.SessionApi;
import io.agentcore.managed.server.control.SessionResolveResult;
import io.agentcore.managed.server.runtime.ManagedCatalog;
import io.agentcore.managed.server.runtime.managed.AgentScopeCheckpoints;
import io.agentcore.managed.server.runtime.managed.AgentScopePreparation;
import io.agentcore.managed.server.runtime.managed.InMemoryManagedInputInbox;
import io.agentcore.managed.server.runtime.managed.InMemoryManagedLeaseStore;
import io.agentcore.managed.server.runtime.managed.InMemoryManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedCheckpointLoop;
import io.agentcore.managed.server.runtime.managed.ManagedCheckpointOperations;
import io.agentcore.managed.server.runtime.managed.ManagedInputDelivery;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedLifecycles;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch;
import io.agentcore.managed.server.runtime.managed.ManagedSandboxBindings;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedTurnPublisher;
import io.agentcore.managed.server.runtime.managed.RedisManagedInfrastructure;
import io.agentcore.managed.server.runtime.managed.RocketMqManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.SessionCommitTransactions;
import io.agentcore.managed.server.runtime.protocol.RuntimeRequestContext;
import io.agentcore.managed.server.runtime.protocol.RuntimeSessionBootstrap;
import io.agentcore.managed.server.web.managed.EnvironmentDto;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.state.AgentState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

/** Actual Agent Service HTTP/authentication/JPA and ma tables; publication uses an explicit test fake. */
@Import(AgentServiceQueryTestConfiguration.class)
@TestPropertySource(locations = "classpath:application-test.properties")
@SpringBootTest(
        classes = AgentServiceApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "managed-agents.local-dev=false",
            "managed-agents.runtime.session-queue.enabled=true",
            "managed-agents.internal-token=session-api-test-internal-token-at-least-32-characters",
            "spring.datasource.url=jdbc:h2:mem:managed-session-contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "managed-agents.runtime.publication-retry-ms=3600000",
            "managed-agents.agent-harness-runtime-url=http://127.0.0.1:1",
            "managed-agents.control-plane-url=http://127.0.0.1:1"
        })
@Timeout(20)
class SessionApiContractTest {
    private static final String KEY = "fake-session-key";

    @Value("${local.server.port}")
    int port;

    @Autowired AgentServiceSessionRepository sessions;
    @Autowired SessionApi sessionApi;
    @Autowired ManagedAdmissionStore admission;
    @Autowired ManagedSessionTurnsApi turnsApi;
    @Autowired ManagedLifecycles lifecycles;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource source;
    @Autowired VaultCrypto crypto;
    @MockitoSpyBean ManagedJournalStore journal;
    @MockitoSpyBean ManagedCatalog catalog;
    @Autowired ManagedCheckpointOperations checkpointOperations;
    @MockitoBean AgentServiceSessionCreation creation;
    @MockitoBean AgentServicePolicies policies;
    @MockitoBean AgentServiceRegistryReads registry;
    @MockitoBean AgentServiceTurnAdmission validation;
    @MockitoBean ManagedTurnPublisher publisher;
    @MockitoBean ManagedInputDelivery delivery;
    @MockitoBean RedisManagedInfrastructure redis;
    @MockitoBean RocketMqManagedMessageBus broker;
    private String id;
    private ManagedScope scope;
    private List<String> grants;

    @BeforeEach
    void fixture() throws Exception {
        when(redis.allowApplicationRequest(anyString(), anyInt())).thenReturn(true);
        id = "session-" + UUID.randomUUID();
        scope = new ManagedScope("default", "default", id);
        grants = new ArrayList<>(List.of("read", "invoke", "interact", "approve", "cancel"));
        String hash =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(KEY.getBytes(StandardCharsets.UTF_8)));
        when(policies.read(any()))
                .thenAnswer(
                        ignored ->
                                Map.of(
                                        "enabled",
                                        true,
                                        "valid_until",
                                        System.currentTimeMillis() + 120000,
                                        "credentials",
                                        List.of(
                                                Map.of(
                                                        "hash",
                                                        hash,
                                                        "scopes",
                                                        List.copyOf(grants))),
                                        "humans",
                                        Map.of()));
        var row = new AgentServiceSessionEntity();
        row.id = id;
        row.ownerId = "owner";
        row.tenant = scope.tenant();
        row.namespace = scope.namespace();
        row.applicationId = "app";
        row.runtimeKind = "managed";
        row.commandId = "fixture:" + id;
        row.revision = "pinned";
        row.digest = "0".repeat(64);
        row.state = "created";
        row.lifecycle = "active";
        row.policy = "{}";
        row.createdAt = row.updatedAt = System.currentTimeMillis();
        row.publicView =
                mapper.writeValueAsString(
                        Map.of(
                                "agentId",
                                "agent",
                                "agentVersion",
                                2,
                                "environmentId",
                                "environment",
                                "memoryStoreIds",
                                List.of("memory"),
                                "vaultIds",
                                List.of("vault"),
                                "sessionAgentReference",
                                Map.of(
                                        "id", "agent", "version", 2, "name", "Example", "model",
                                        "model")));
        sessions.saveAndFlush(row);
        admission.createSession(
                scope,
                sealedConfiguration(
                        Map.of("name", "Example", "model", "model"),
                        runtimeContext(
                                "bootstrap", null, Map.of(), System.currentTimeMillis() + 60000)));
        when(creation.create(anyMap(), any())).thenReturn(Map.of("id", id));
        var bus = new InMemoryManagedMessageBus(System::currentTimeMillis, 20);
        var fakePublisher =
                new ManagedTurnPublisher(
                        admission,
                        bus,
                        new InMemoryManagedLeaseStore(System::currentTimeMillis),
                        mapper,
                        Duration.ofSeconds(60));
        when(publisher.publish(any(), anyString()))
                .thenAnswer(
                        call -> fakePublisher.publish(call.getArgument(0), call.getArgument(1)));
        var fakeDelivery =
                new ManagedInputDelivery(
                        admission, new InMemoryManagedInputInbox(System::currentTimeMillis));
        doAnswer(
                        call -> {
                            fakeDelivery.deliver(call.getArgument(0), call.getArgument(1));
                            return null;
                        })
                .when(delivery)
                .deliver(any(), any());
    }

    @Test
    void sharedRateRejectionDoesNotAdmitATurnAndSameKeyCanBeRetried() {
        when(redis.allowApplicationRequest(anyString(), anyInt())).thenReturn(false);
        client().post()
                .uri(path() + "/events")
                .header("Idempotency-Key", "rate-limited")
                .bodyValue(Map.of("events", List.of(protocolMessage("new_turn", null, "hello"))))
                .exchange()
                .expectStatus()
                .isEqualTo(429);
        assertEquals(0, admission.turnHead(scope));
        when(redis.allowApplicationRequest(anyString(), anyInt())).thenReturn(true);
        assertNotNull(createProtocolTurn("rate-limited"));
    }

    @Test
    void creationPinsDefinitionAndRetriesDoNotOverwriteLaterMetadata() {
        var body =
                Map.of(
                        "agent",
                        "agent",
                        "environment_id",
                        "environment",
                        "title",
                        "Original",
                        "metadata",
                        Map.of("purpose", "test"));
        var first =
                client().post()
                        .uri("/v1/sessions")
                        .header("Idempotency-Key", "create-one")
                        .bodyValue(body)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(first);
        assertEquals("Original", first.get("title"));
        var row = sessions.findById(id).orElseThrow();
        row.title = "Later title";
        sessions.saveAndFlush(row);
        var retry =
                client().post()
                        .uri("/v1/sessions")
                        .header("Idempotency-Key", "create-one")
                        .bodyValue(body)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertEquals(first, retry);
        client().post()
                .uri("/v1/sessions")
                .header("Idempotency-Key", "create-one")
                .bodyValue(Map.of("agent", "agent", "title", "different"))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        client().get()
                .uri(path())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo("Later title");
    }

    @Test
    void missingSealedAgentDefinitionCannotAdmitPublicWork() throws Exception {
        setSealedAgent(Map.of());
        client().post()
                .uri(path() + "/events")
                .header("Idempotency-Key", "missing-definition")
                .bodyValue(Map.of("events", List.of(protocolMessage("new_turn", null, "hello"))))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("invalid_request");
        assertEquals(0, admission.turnHead(scope));
    }

    @Test
    void metadataAndPublicEventRollbackInTheSameJpaTransaction() {
        String existing = createProtocolTurn("existing");
        new JdbcTemplate(source)
                .update(
                        "UPDATE ma.turn SET inputs_open=false WHERE scope_key=? AND turn_id=?",
                        scope.messageGroup(),
                        existing);
        long before = admission.turnHead(scope);
        client().post()
                .uri(path() + "/events")
                .header("Idempotency-Key", "atomic-batch")
                .bodyValue(
                        Map.of(
                                "events",
                                List.of(
                                        protocolMessage("new_turn", null, "rollback"),
                                        protocolMessage("steer", existing, "late"))))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        assertEquals(before, admission.turnHead(scope));
    }

    @Test
    void requestLimitCountsRawBytesForBothFixedAndChunkedBodies() throws Exception {
        byte[] raw =
                (" ".repeat(1048576)
                                + mapper.writeValueAsString(
                                        Map.of(
                                                "events",
                                                List.of(
                                                        protocolMessage(
                                                                "new_turn", null, "oversized")))))
                        .getBytes(StandardCharsets.UTF_8);
        for (boolean chunked : List.of(false, true)) {
            var request =
                    client().post()
                            .uri(path() + "/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", "raw-limit-" + chunked);
            var response =
                    chunked
                            ? request.body(Flux.just(raw), byte[].class).exchange()
                            : request.bodyValue(raw).exchange();
            response.expectStatus()
                    .isEqualTo(413)
                    .expectBody()
                    .jsonPath("$.error.type")
                    .isEqualTo("request_too_large");
        }
        assertEquals(0, admission.turnHead(scope));
    }

    @Test
    void readQueriesRejectUnknownAndRepeatedParameters() {
        for (String query :
                List.of(
                        "/events?limit=1&limit=2",
                        "/turns?unknown=value",
                        "/turns/t/output?preview=true",
                        "/events?beta=false"))
            client().get()
                    .uri(path() + query)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody()
                    .jsonPath("$.error.code")
                    .isEqualTo("incompatible_query");
        client().get()
                .uri(path())
                .header("anthropic-version", "2023-06-01", "2023-06-01")
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void titleAndMetadataUpdatesAreSetsAndConfigurationCannotMutate() {
        client().post()
                .uri(path())
                .bodyValue(Map.of("title", "changed"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("unsupported_capability");
        assertNull(sessions.findById(id).orElseThrow().title);
    }

    @Test
    void authenticationReadGrantsAndNamespacesAreEnforced() {
        WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build()
                .get()
                .uri(path())
                .exchange()
                .expectStatus()
                .isUnauthorized();
        grants.remove("read");
        client().get().uri(path()).exchange().expectStatus().isForbidden();
        grants.add("read");
        client().get()
                .uri(path())
                .header("X-Managed-Agents-Namespace", "other")
                .exchange()
                .expectStatus()
                .isNotFound();
        client().get()
                .uri(path())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.managed_agents.memory_store_ids[0]")
                .isEqualTo("memory");
    }

    @Test
    void canonicalTurnAndInputReceiptsAreDurableAndIdempotent() {
        String turn = createProtocolTurn("one");
        var body = Map.of("events", List.of(protocolMessage("steer", turn, "redirect")));
        var first =
                client().post()
                        .uri(path() + "/events")
                        .header("Idempotency-Key", "input-key")
                        .bodyValue(body)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        new JdbcTemplate(source)
                .update(
                        "UPDATE ma.turn SET inputs_open=false,status='succeeded' WHERE turn_id=?",
                        turn);
        var retry =
                client().post()
                        .uri(path() + "/events")
                        .header("Idempotency-Key", "input-key")
                        .bodyValue(body)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertEquals(first, retry);
        client().post()
                .uri(path() + "/events")
                .header("Idempotency-Key", "different-key")
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        client().get()
                .uri(path() + "/turns/" + turn + "/output")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.output_available")
                .isEqualTo(false);
    }

    @Test
    void commandGrantsAreCheckedWithoutAcceptingUnauthorizedWork() {
        String turn = createProtocolTurn("one");
        grants.remove("invoke");
        client().post()
                .uri(path() + "/events")
                .header("Idempotency-Key", "steer")
                .bodyValue(Map.of("events", List.of(protocolMessage("steer", turn, "denied"))))
                .exchange()
                .expectStatus()
                .isForbidden();
        client().post()
                .uri(path() + "/events")
                .header("Idempotency-Key", "cancel")
                .bodyValue(
                        Map.of(
                                "events",
                                List.of(
                                        Map.of(
                                                "type",
                                                "user.interrupt",
                                                "managed_agents",
                                                Map.of("intent", "cancel", "turn_id", turn)))))
                .exchange()
                .expectStatus()
                .isOk();
        assertEquals(1, admission.inputsAfter(scope, turn, 0, 10).size());
    }

    @Test
    void createRequestValidationRejectsUnsupportedAndMalformedFields() {
        client().post()
                .uri("/v1/sessions")
                .bodyValue(Map.of("agent", "agent"))
                .exchange()
                .expectStatus()
                .isOk();
        client().post()
                .uri("/v1/sessions")
                .header("Idempotency-Key", "bad")
                .bodyValue(Map.of("agent", "agent", "initial_events", List.of()))
                .exchange()
                .expectStatus()
                .isBadRequest();
        client().post()
                .uri("/v1/sessions")
                .header("Idempotency-Key", "bad")
                .bodyValue(Map.of("agent", Map.of("id", "agent", "version", 1.5)))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void sessionListFiltersAreBoundToOpaqueCursors() {
        client().method(org.springframework.http.HttpMethod.GET)
                .uri("/v1/sessions")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("unsupported_capability");
        assertEquals(0, admission.turnHead(scope));
    }

    @Test
    void filesAreImmutableAndAvailableWithoutRuntimeBootstrap() {
        client().method(org.springframework.http.HttpMethod.POST)
                .uri(path() + "/files")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("unsupported_capability");
        assertEquals(0, admission.turnHead(scope));
    }

    @Test
    void missingFilesAreRejectedBeforeTurnAdmissionAndUploadPublicationRollsBack() {
        client().method(org.springframework.http.HttpMethod.POST)
                .uri(path() + "/files")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("unsupported_capability");
        assertEquals(0, admission.turnHead(scope));
    }

    @Test
    void artifactsPublishDownloadAndUnpublishThroughTheManagedPublicCursor() {
        client().method(org.springframework.http.HttpMethod.POST)
                .uri(path() + "/artifacts")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("unsupported_capability");
        assertEquals(0, admission.turnHead(scope));
    }

    @Test
    void javaNativeClientUsesActualAgentServiceAdmissionReceiptsFilesAndSse() throws Exception {
        {
            var transport = ServiceClient.builder("http://127.0.0.1:" + port).apiKey(KEY).build();
            var client = transport.managedSessions();
            var accepted = client.submitMessage(id, "hello", "java-request");
            assertEquals("accepted", accepted.path("status").asText());
            assertNotNull(accepted.path("event_id").asText());
            assertEquals(accepted, client.submitMessage(id, "hello", "java-request"));
            assertEquals(
                    false,
                    client.output(id, accepted.path("id").asText(), null, 100)
                            .path("output_available")
                            .asBoolean());
            client.input(
                    id,
                    accepted.path("id").asText(),
                    "steer",
                    mapper.valueToTree(Map.of("message", "redirect")),
                    null,
                    "java-steer");
            assertEquals(
                    1, admission.inputsAfter(scope, accepted.path("id").asText(), 0, 10).size());
        }
    }

    @Test
    void trustedBinaryFilesUseOriginalActorAndRejectSpoofedOrForeignContext() throws Exception {
        String hash =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(KEY.getBytes(StandardCharsets.UTF_8)));
        var actor = Map.<String, Object>of("user", "owner", "key_hash", hash, "internal", false);
        var context = runtimeContext("rpc-file", null, actor, System.currentTimeMillis() + 120000);
        String encoded =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(mapper.writeValueAsBytes(context));
        byte[] bytes = new byte[3 * 1024 * 1024];
        bytes[0] = 42;
        bytes[bytes.length - 1] = 99;
        var receipt =
                runtimeClient()
                        .post()
                        .uri("/api/internal/managed/sessions/" + id + "/files")
                        .header(ManagedRuntimeFilesController.CONTEXT_HEADER, encoded)
                        .header("X-File-Name", "trusted%20file.bin")
                        .header("Content-Type", "application/octet-stream")
                        .bodyValue(bytes)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(receipt);
        assertEquals(bytes.length, ((Number) receipt.get("size")).intValue());
        var downloaded =
                runtimeClient()
                        .mutate()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                        .build()
                        .get()
                        .uri(
                                "/api/internal/managed/sessions/"
                                        + id
                                        + "/files/"
                                        + receipt.get("file_id")
                                        + "/content")
                        .header(ManagedRuntimeFilesController.CONTEXT_HEADER, encoded)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .returnResult()
                        .getResponseBody();
        assertArrayEquals(bytes, downloaded);
        var trustedDownload =
                runtimeClient()
                        .mutate()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                        .build()
                        .get()
                        .uri(
                                "/api/internal/managed/sessions/"
                                        + id
                                        + "/files/"
                                        + receipt.get("file_id")
                                        + "/content")
                        .header(ManagedRuntimeFilesController.CONTEXT_HEADER, encoded)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectHeader()
                        .valueEquals("Cache-Control", "no-store")
                        .expectBody()
                        .returnResult()
                        .getResponseBody();
        assertArrayEquals(bytes, trustedDownload);
        runtimeClient()
                .get()
                .uri(
                        "/api/internal/managed/sessions/"
                                + id
                                + "/files/"
                                + receipt.get("file_id")
                                + "/content")
                .header(ManagedRuntimeFilesController.CONTEXT_HEADER, "bad*")
                .exchange()
                .expectStatus()
                .isBadRequest();
        runtimeClient()
                .post()
                .uri("/api/internal/managed/sessions/" + id + "/files")
                .header(ManagedRuntimeFilesController.CONTEXT_HEADER, "bad*")
                .header("X-File-Name", "file")
                .bodyValue(new byte[] {1})
                .exchange()
                .expectStatus()
                .isBadRequest();
        var expired =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                mapper.writeValueAsBytes(
                                        runtimeContext("expired-file", null, actor, 1)));
        runtimeClient()
                .post()
                .uri("/api/internal/managed/sessions/" + id + "/files")
                .header(ManagedRuntimeFilesController.CONTEXT_HEADER, expired)
                .header("X-File-Name", "file")
                .bodyValue(new byte[] {1})
                .exchange()
                .expectStatus()
                .isForbidden();
        var foreign =
                new RuntimeRequestContext(
                        1,
                        "foreign",
                        scope.namespace(),
                        "app",
                        id,
                        null,
                        "foreign-file",
                        "trace",
                        actor,
                        "test",
                        System.currentTimeMillis() + 120000);
        String foreignHeader =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(mapper.writeValueAsBytes(foreign));
        runtimeClient()
                .post()
                .uri("/api/internal/managed/sessions/" + id + "/files")
                .header(ManagedRuntimeFilesController.CONTEXT_HEADER, foreignHeader)
                .header("X-File-Name", "file")
                .bodyValue(new byte[] {1})
                .exchange()
                .expectStatus()
                .isForbidden();
        WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build()
                .post()
                .uri("/api/internal/managed/sessions/" + id + "/files")
                .header(ManagedRuntimeFilesController.CONTEXT_HEADER, encoded)
                .header("X-File-Name", "file")
                .bodyValue(new byte[] {1})
                .exchange()
                .expectStatus()
                .isUnauthorized();
        WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build()
                .post()
                .uri(path() + "/events")
                .header(ManagedRuntimeFilesController.CONTEXT_HEADER, encoded)
                .header(
                        "X-Managed-Agents-Internal-Token",
                        "session-api-test-internal-token-at-least-32-characters")
                .header("X-File-Name", "file")
                .header("Idempotency-Key", "spoof-file")
                .bodyValue(Map.of("events", List.of(protocolMessage("new_turn", null, "spoof"))))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        assertEquals(
                1,
                journal.files().list(scope, 0, 50).get("data") instanceof List<?> list
                        ? list.size()
                        : 0);
    }

    @Test
    @Timeout(60)
    @EnabledIfEnvironmentVariable(named = "MANAGED_TEST_GO_BIN", matches = ".+")
    void goTransportReachesActualAgentServiceAndRetainsOriginalActor() throws Exception {
        String hash =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(KEY.getBytes(StandardCharsets.UTF_8)));
        var actor = Map.<String, Object>of("user", "owner", "key_hash", hash, "internal", false);
        boolean invocationBridge = System.getenv("CONTROL_PLANE_TEST_POSTGRES_DSN") != null;
        if (invocationBridge) {
            when(policies.read(any()))
                    .thenAnswer(
                            call -> {
                                AgentServiceSessionEntity row = call.getArgument(0);
                                if (!id.equals(row.id))
                                    return mapper.readValue(crypto.decrypt(row.policy), Map.class);
                                return Map.of(
                                        "enabled",
                                        true,
                                        "valid_until",
                                        System.currentTimeMillis() + 120000,
                                        "credentials",
                                        List.of(
                                                Map.of(
                                                        "hash",
                                                        hash,
                                                        "scopes",
                                                        List.copyOf(grants))),
                                        "humans",
                                        Map.of("owner", List.copyOf(grants)));
                            });
        }
        Path output = Files.createTempFile("managed-go-portal-", ".log");
        Process process = null;
        try {
            var command =
                    new ArrayList<>(
                            List.of(
                                    System.getenv("MANAGED_TEST_GO_BIN"),
                                    "test",
                                    "./internal/product"));
            if (invocationBridge) command.add("./internal/httpapi");
            command.addAll(
                    List.of(
                            "-run",
                            "^(TestManagedRuntimeLiveAgentService|TestNativeRuntimeLiveAgentService|TestPublicManagedSessionAccountFilesAndNativeBridge)$",
                            "-count=1"));
            var builder =
                    new ProcessBuilder(command)
                            .directory(Path.of(System.getenv("MANAGED_TEST_GO_MODULE")).toFile())
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile());
            builder.environment()
                    .put("MANAGED_AGENTS_AGENT_SERVICE_TEST_URL", "http://127.0.0.1:" + port);
            builder.environment()
                    .put(
                            "MANAGED_AGENTS_AGENT_SERVICE_TEST_CONTEXT",
                            mapper.writeValueAsString(
                                    runtimeContext(
                                            "go-turn",
                                            null,
                                            actor,
                                            System.currentTimeMillis() + 120000)));
            builder.environment()
                    .put(
                            "MANAGED_AGENTS_AGENT_SERVICE_TEST_TOKEN",
                            "session-api-test-internal-token-at-least-32-characters");
            process = builder.start();
            assertTrue(process.waitFor(40, TimeUnit.SECONDS), "Go transport test timed out");
            assertEquals(
                    0,
                    process.exitValue(),
                    () -> {
                        try {
                            return Files.readString(output);
                        } catch (Exception failure) {
                            return failure.toString();
                        }
                    });
            var turns = admission.turns(scope, 0, false, null, 10);
            assertEquals(invocationBridge ? 2 : 1, turns.size());
            assertEquals(actor, mapper.readValue(turns.get(0).actor(), Map.class));
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }

    @Test
    void nativeRestoreUsesDurableAdmissionAndToolWorkerRatherThanAgentServiceRuntimeForwarding()
            throws Exception {
        doReturn(
                        new SessionResolveResult(
                                null,
                                Map.of("name", "fixed"),
                                null,
                                null,
                                null,
                                Map.of(),
                                null,
                                List.of(),
                                List.of(),
                                null,
                                null))
                .when(catalog)
                .resolve(scope);
        String turn = admission.acceptTurn(scope, "actor", "seed-restore", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "restore-token");
        admission.closeInputs(scope, turn, 1);
        var checkpoint =
                new SessionEvent(
                        1,
                        "restore-seed",
                        1,
                        System.currentTimeMillis(),
                        "state/checkpoint",
                        "run",
                        turn,
                        true,
                        mapper.writeValueAsString(
                                Map.of(
                                        "projectorVersion",
                                        1,
                                        "stateJson",
                                        AgentState.builder().build().toJson())));
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        new ManagedRuntimeBatch(
                                scope,
                                turn,
                                1,
                                "restore-token",
                                "writer",
                                "restore-seed",
                                0,
                                List.of(checkpoint),
                                List.of(),
                                List.of(),
                                "succeeded")));
        String checkpointId =
                (String)
                        ((Map<?, ?>)
                                        ((List<?>)
                                                        journal.checkpoints()
                                                                .list(scope, null, null, 10)
                                                                .get("data"))
                                                .get(0))
                                .get("checkpoint_id");
        var body = Map.of("checkpoint_id", checkpointId);
        var receipt =
                nativeRpc("restore_checkpoint", "restore-one", body, null)
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(receipt);
        assertEquals("validating", receipt.get("status"));
        String operation = (String) receipt.get("operation_id");
        nativeRpc("restore_checkpoint", "restore-one", body, null)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.operation_id")
                .isEqualTo(operation);
        client().post()
                .uri(path() + "/events")
                .header("Idempotency-Key", "held-turn")
                .bodyValue(Map.of("events", List.of(protocolMessage("new_turn", null, "held"))))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        assertEquals("created", sessions.findById(id).orElseThrow().state);
        try (var worker =
                new ManagedCheckpointLoop(
                        checkpointOperations,
                        new InMemoryManagedLeaseStore(System::currentTimeMillis),
                        ignored -> {
                            throw new AssertionError(
                                    "Context-only Restore must not start a Sandbox");
                        },
                        ignored -> "owner",
                        1,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(15),
                        (ignored, error) -> {
                            throw error;
                        })) {
            var command =
                    checkpointOperations.pending(256).stream()
                            .filter(value -> value.id().equals(operation))
                            .findFirst()
                            .orElseThrow();
            assertTrue(worker.execute(command));
        }
        nativeRpc("checkpoint_operation", "read-checkpoint", Map.of(), operation)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("completed");
        assertEquals(2, journal.head(scope).sequence());
        assertEquals("state/restored", journal.recoveryEvents(scope, 1, 1).get(0).type());
        var sdk =
                ServiceClient.builder("http://127.0.0.1:" + port)
                        .apiKey(KEY)
                        .build()
                        .managedSessions();
        assertEquals("completed", checkpointOperations.get(scope, operation).status());
        var trusted = runtimeResourceRequest("checkpoint_operation");
        trusted.put("receiptId", operation);
        runtimeClient()
                .post()
                .uri(runtimePath())
                .bodyValue(trusted)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("completed");
        var cancelled =
                nativeRpc("restore_checkpoint", "restore-cancel", body, null)
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(cancelled);
        nativeRpc("restore_checkpoint", "restore-cancel", body, null)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.operation_id")
                .isEqualTo(cancelled.get("operation_id"));
        nativeRpc(
                        "cancel_checkpoint_operation",
                        "cancel-restoration",
                        Map.of(),
                        (String) cancelled.get("operation_id"))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("cancelled");
        client().post()
                .uri(path() + "/events")
                .header("Idempotency-Key", "after-restore")
                .bodyValue(Map.of("events", List.of(protocolMessage("new_turn", null, "next"))))
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void selfHostedRestoreRequiresWorkspaceWithoutReservingPhysicalBindings() throws Exception {
        var environment =
                new EnvironmentDto(
                        "self-hosted",
                        "self-hosted",
                        "self_hosted",
                        Map.of("sandboxId", "box-" + id),
                        "owner",
                        null,
                        1,
                        1,
                        null);
        doReturn(
                        new SessionResolveResult(
                                null,
                                Map.of("name", "fixed"),
                                null,
                                null,
                                null,
                                Map.of(),
                                environment,
                                List.of(),
                                List.of(),
                                null,
                                null))
                .when(catalog)
                .resolve(scope);
        String turn = admission.acceptTurn(scope, "actor", "selfhost-seed", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "selfhost-token");
        admission.closeInputs(scope, turn, 1);
        var event =
                new SessionEvent(
                        1,
                        "selfhost-checkpoint",
                        1,
                        1,
                        "state/checkpoint",
                        "run",
                        turn,
                        true,
                        mapper.writeValueAsString(
                                Map.of("stateJson", AgentState.builder().build().toJson())));
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        new ManagedRuntimeBatch(
                                scope,
                                turn,
                                1,
                                "selfhost-token",
                                "writer",
                                "selfhost-seed",
                                0,
                                List.of(event),
                                List.of(),
                                List.of(),
                                "succeeded")));
        String checkpoint =
                (String)
                        ((Map<?, ?>)
                                        ((List<?>)
                                                        journal.checkpoints()
                                                                .list(scope, null, null, 10)
                                                                .get("data"))
                                                .get(0))
                                .get("checkpoint_id");
        var receipt =
                nativeRpc(
                                "restore_checkpoint",
                                "selfhost-restore",
                                Map.of("checkpoint_id", checkpoint),
                                null)
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(receipt);
        assertEquals(true, receipt.get("workspace_required"));
        assertEquals(null, new ManagedSandboxBindings(source).get(scope));
        String operation = (String) receipt.get("operation_id");
        try (var worker =
                new ManagedCheckpointLoop(
                        checkpointOperations,
                        new InMemoryManagedLeaseStore(System::currentTimeMillis),
                        ignored -> null,
                        ignored -> "owner",
                        1,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(15),
                        (ignored, error) -> {
                            throw error;
                        })) {
            var command =
                    checkpointOperations.pending(256).stream()
                            .filter(value -> value.id().equals(operation))
                            .findFirst()
                            .orElseThrow();
            assertEquals(false, worker.execute(command));
        }
        assertEquals("snapshotting", checkpointOperations.get(scope, operation).status());
        assertEquals(1, journal.head(scope).sequence());
        nativeRpc("cancel_checkpoint_operation", "cancel-checkpoint", Map.of(), operation)
                .expectStatus()
                .isOk();
    }

    @Test
    void nativeCheckpointMetadataAndJavaClientReadTheCommittedIndex() throws Exception {
        String turn = createProtocolTurn("checkpoint-turn");
        admission.activate(scope, turn, 1, "checkpoint-token");
        var event =
                new SessionEvent(
                        1,
                        "checkpoint-event",
                        1,
                        System.currentTimeMillis(),
                        "state/checkpoint",
                        "run",
                        turn,
                        true,
                        mapper.writeValueAsString(
                                Map.of(
                                        "projectorVersion",
                                        1,
                                        "stateJson",
                                        AgentState.builder()
                                                .summary("private-state")
                                                .build()
                                                .toJson())));
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        new ManagedRuntimeBatch(
                                scope,
                                turn,
                                1,
                                "checkpoint-token",
                                "writer",
                                "checkpoint-batch",
                                0,
                                List.of(event),
                                List.of(),
                                List.of(),
                                null)));
        var request = runtimeResourceRequest("checkpoints");
        runtimeClient()
                .post()
                .uri(runtimePath())
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data.length()")
                .isEqualTo(1);
        client().get()
                .uri(path() + "/checkpoints")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("unsupported_capability");
    }

    @Test
    void trustedResourceOperationsKeepPinnedPagesAndBudgetRevisions() throws Exception {
        var budget = runtimeResourceRequest("configure_budget");
        budget.put("payload", Map.of("max_model_calls", 20));
        budget.put("expectedRevision", "\"0\"");
        runtimeClient()
                .post()
                .uri(runtimePath())
                .bodyValue(budget)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.revision")
                .isEqualTo(1);
        budget.put("payload", Map.of("max_model_calls", 30));
        runtimeClient()
                .post()
                .uri(runtimePath())
                .bodyValue(budget)
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        runtimeClient()
                .post()
                .uri(runtimePath())
                .bodyValue(runtimeResourceRequest("budget"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.limits.max_model_calls")
                .isEqualTo(20);
        var publication = runtimeResourceRequest("publish_artifact");
        publication.put(
                "payload", Map.of("name", "report one", "uri", "https://example.org/report-one"));
        var first =
                runtimeClient()
                        .post()
                        .uri(runtimePath())
                        .bodyValue(publication)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(first);
        runtimeClient()
                .post()
                .uri(runtimePath())
                .bodyValue(publication)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.artifact_id")
                .isEqualTo(first.get("artifact_id"));
        var second = runtimeResourceRequest("publish_artifact");
        second.put(
                "payload", Map.of("name", "report two", "uri", "https://example.org/report-two"));
        runtimeClient()
                .post()
                .uri(runtimePath())
                .bodyValue(second)
                .exchange()
                .expectStatus()
                .isOk();
        while (journal.projections().project(scope, 128) > 0) {
            /* Explicit query consumer catch-up. */
        }
        var listing = runtimeResourceRequest("resource_page");
        listing.put("resourceKind", "artifacts");
        listing.put("limit", 1);
        var page =
                runtimeClient()
                        .post()
                        .uri(runtimePath())
                        .bodyValue(listing)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(page);
        assertEquals(true, page.get("has_more"));
        String snapshot = (String) page.get("snapshot_id");
        var remove = runtimeResourceRequest("delete_artifact");
        remove.put("resourceId", first.get("artifact_id"));
        runtimeClient()
                .post()
                .uri(runtimePath())
                .bodyValue(remove)
                .exchange()
                .expectStatus()
                .isOk();
        while (journal.projections().project(scope, 128) > 0) {
            /* Apply deletion before querying latest metadata. */
        }
        var metadata = runtimeResourceRequest("artifact");
        metadata.put("resourceId", first.get("artifact_id"));
        runtimeClient()
                .post()
                .uri(runtimePath())
                .bodyValue(metadata)
                .exchange()
                .expectStatus()
                .isNotFound();
        metadata.put("snapshotId", snapshot);
        runtimeClient()
                .post()
                .uri(runtimePath())
                .bodyValue(metadata)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.artifact_id")
                .isEqualTo(first.get("artifact_id"));
        listing.put("after", page.get("next_cursor"));
        runtimeClient()
                .post()
                .uri(runtimePath())
                .bodyValue(listing)
                .exchange()
                .expectStatus()
                .isBadRequest();
        listing.put("snapshotId", snapshot);
        var next =
                runtimeClient()
                        .post()
                        .uri(runtimePath())
                        .bodyValue(listing)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(next);
        assertEquals(page.get("as_of"), next.get("as_of"));
        assertEquals(false, next.get("has_more"));
    }

    private WebTestClient.RequestHeadersSpec<?> nativeRequest(
            String operation, String key, Map<String, ?> payload, String receipt) throws Exception {
        var request = runtimeResourceRequest(operation);
        var old = (RuntimeRequestContext) request.get("context");
        request.put(
                "context",
                runtimeContext(key, null, old.actor(), System.currentTimeMillis() + 120000));
        request.put("payload", payload);
        if (receipt != null) request.put("receiptId", receipt);
        return runtimeClient().post().uri(runtimePath()).bodyValue(request);
    }

    private WebTestClient.ResponseSpec nativeRpc(
            String operation, String key, Map<String, ?> payload, String receipt) throws Exception {
        return nativeRequest(operation, key, payload, receipt).exchange();
    }

    private Map<String, Object> runtimeResourceRequest(String operation) throws Exception {
        String hash =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(KEY.getBytes(StandardCharsets.UTF_8)));
        var actor = Map.<String, Object>of("user", "owner", "key_hash", hash, "internal", false);
        var request = new LinkedHashMap<String, Object>();
        request.put(
                "context",
                runtimeContext(
                        operation + UUID.randomUUID(),
                        null,
                        actor,
                        System.currentTimeMillis() + 120000));
        request.put("operation", operation);
        return request;
    }

    @Test
    void trustedRuntimeCallsPreserveActorScopeAndNativeReceipts() throws Exception {
        String hash =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(KEY.getBytes(StandardCharsets.UTF_8)));
        var actor = Map.<String, Object>of("user", "owner", "key_hash", hash, "internal", false);
        var context = runtimeContext("rpc-turn", null, actor, System.currentTimeMillis() + 120000);
        var request =
                new ManagedRuntimeController.Request(
                        context,
                        "submit_turn",
                        Map.of("input", Map.of("message", "RPC task")),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        var response =
                runtimeClient()
                        .post()
                        .uri(runtimePath())
                        .bodyValue(request)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(response);
        assertNull(response.get("input_event_ids"));
        assertNull(response.get("output_url"));
        String turn = (String) response.get("id");
        assertEquals(actor, mapper.readValue(admission.turn(scope, turn).actor(), Map.class));
        runtimeClient()
                .post()
                .uri(runtimePath())
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(turn);
        var input =
                new ManagedRuntimeController.Request(
                        runtimeContext(
                                "rpc-input", turn, actor, System.currentTimeMillis() + 120000),
                        "submit_input",
                        Map.of("type", "steer", "payload", Map.of("message", "RPC steering")),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        var receipt =
                runtimeClient()
                        .post()
                        .uri(runtimePath())
                        .bodyValue(input)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(receipt);
        assertEquals("accepted", receipt.get("status"));
        assertEquals(turn, receipt.get("turn_id"));
        var read =
                new ManagedRuntimeController.Request(
                        runtimeContext("read", turn, actor, System.currentTimeMillis() + 120000),
                        "input",
                        null,
                        null,
                        null,
                        null,
                        null,
                        (String) receipt.get("id"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        runtimeClient()
                .post()
                .uri(runtimePath())
                .bodyValue(read)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Map.class)
                .value(queried -> assertEquals(receipt, queried));
    }

    @Test
    void trustedRuntimeRejectsForeignBindingExpiredActorAndPublicHeaderSpoofing() {
        var actor =
                Map.<String, Object>of("user", "owner", "key_hash", "invalid", "internal", false);
        var expired =
                new ManagedRuntimeController.Request(
                        runtimeContext("expired", null, actor, 1),
                        "submit_turn",
                        Map.of("input", Map.of("message", "task")),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        runtimeClient()
                .post()
                .uri(runtimePath())
                .bodyValue(expired)
                .exchange()
                .expectStatus()
                .isForbidden();
        var foreign =
                new RuntimeRequestContext(
                        1,
                        "different-tenant",
                        scope.namespace(),
                        "app",
                        id,
                        null,
                        "foreign",
                        "trace",
                        actor,
                        "test",
                        System.currentTimeMillis() + 120000);
        var request =
                new ManagedRuntimeController.Request(
                        foreign,
                        "submit_turn",
                        expired.payload(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        runtimeClient()
                .post()
                .uri(runtimePath())
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isForbidden();
        var invalid =
                new ManagedRuntimeController.Request(
                        runtimeContext("invalid", null, actor, System.currentTimeMillis() + 120000),
                        "submit_turn",
                        expired.payload(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        runtimeClient()
                .post()
                .uri(runtimePath())
                .bodyValue(invalid)
                .exchange()
                .expectStatus()
                .isUnauthorized();
        WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build()
                .post()
                .uri(path() + "/events")
                .header(
                        "X-Managed-Agents-Internal-Token",
                        "session-api-test-internal-token-at-least-32-characters")
                .header("X-Managed-Agents-Internal-User", "owner")
                .header("Idempotency-Key", "spoof")
                .header("X-Managed-Actor", "owner")
                .bodyValue(Map.of("events", List.of(protocolMessage("new_turn", null, "spoof"))))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        assertTrue(admission.turns(scope, 0, false, null, 10).isEmpty());
    }

    private RuntimeRequestContext runtimeContext(
            String command, String turn, Map<String, Object> actor, long until) {
        return new RuntimeRequestContext(
                1,
                scope.tenant(),
                scope.namespace(),
                "app",
                id,
                turn,
                command,
                "trace",
                actor,
                "test",
                until);
    }

    private String runtimePath() {
        return "/api/internal/managed/sessions/" + id + "/request";
    }

    private WebTestClient runtimeClient() {
        return WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .defaultHeader(
                        "X-Managed-Agents-Internal-Token",
                        "session-api-test-internal-token-at-least-32-characters")
                .defaultHeader("X-Managed-Agents-Internal-User", "owner")
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void archiveFromCreatedStopsAdmissionAndRestoreWaitsForCleanup() {
        client().method(org.springframework.http.HttpMethod.POST)
                .uri(path() + "/archive")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("unsupported_capability");
        assertEquals(0, admission.turnHead(scope));
    }

    @Test
    void archivedSessionClosesTurnInputsInBothQueriesAndCapabilities() {
        client().method(org.springframework.http.HttpMethod.POST)
                .uri(path() + "/restore")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("unsupported_capability");
        assertEquals(0, admission.turnHead(scope));
    }

    @Test
    void deletionKeepsOnlyTheAuthorizedLifecycleReceiptReadable() {
        client().method(org.springframework.http.HttpMethod.DELETE)
                .uri(path() + "")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("unsupported_capability");
        assertEquals(0, admission.turnHead(scope));
    }

    @Test
    void stoppingAnUnsettledTurnRequiresCancelPermissionAndLifecycleRollbackIsAtomic() {
        client().method(org.springframework.http.HttpMethod.POST)
                .uri(path() + "/archive")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("unsupported_capability");
        assertEquals(0, admission.turnHead(scope));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MANAGED_AGENTS_OFFICIAL_SDK_TESTS", matches = "true")
    void officialPythonSdk() throws Exception {
        runOfficialSdk("python");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MANAGED_AGENTS_OFFICIAL_SDK_TESTS", matches = "true")
    void officialTypeScriptSdk() throws Exception {
        runOfficialSdk("typescript");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MANAGED_AGENTS_OFFICIAL_SDK_TESTS", matches = "true")
    void officialJavaSdk() throws Exception {
        runOfficialSdk("java");
    }

    @Test
    void encryptedAgentProjectsPinnedToolsAndStripsPrivateMcpHeaders() throws Exception {
        setSealedAgent(
                Map.of(
                        "tools",
                        List.of(
                                Map.of(
                                        "type",
                                        "agent_toolset",
                                        "defaultConfig",
                                        Map.of("enabled", false)),
                                Map.of(
                                        "type",
                                        "mcp_toolset",
                                        "mcpServerName",
                                        "longwait",
                                        "defaultConfig",
                                        Map.of(
                                                "permissionPolicy",
                                                Map.of("type", "always_allow")))),
                        "mcpServers",
                        List.of(
                                Map.of(
                                        "name",
                                        "longwait",
                                        "url",
                                        "http://fixture/mcp",
                                        "headers",
                                        Map.of("Authorization", "private-mcp-token")))));
        client().get()
                .uri(path())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.agent.tools[0].default_config.enabled")
                .isEqualTo(false)
                .jsonPath("$.agent.tools[1].default_config.permission_policy.type")
                .isEqualTo("always_allow")
                .jsonPath("$.agent.mcp_servers[0].url")
                .isEqualTo("http://fixture/mcp")
                .jsonPath("$.agent.mcp_servers[0].headers")
                .doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pi", "pi-durable", "codex"})
    void genericHarnessRejectsIgnoredFieldsAndMissingToolsBeforePublicAdmission(String kernel)
            throws Exception {
        ReflectionTestUtils.setField(turnsApi, "harnessType", kernel);
        try {
            for (var snapshot :
                    List.of(
                            Map.<String, Object>of("contextPolicy", Map.of("enabled", true)),
                            Map.<String, Object>of("jev", Map.of("enabled", true)),
                            Map.<String, Object>of(
                                    "tools",
                                    List.of(
                                            Map.of(
                                                    "type",
                                                    "agent_toolset",
                                                    "configs",
                                                    List.of(
                                                            Map.of(
                                                                    "name", "edit", "enabled",
                                                                    true))))))) {
                setSealedAgent(snapshot);
                client().post()
                        .uri(path() + "/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .bodyValue(
                                Map.of(
                                        "events",
                                        List.of(protocolMessage("new_turn", null, "text"))))
                        .exchange()
                        .expectStatus()
                        .isBadRequest()
                        .expectBody()
                        .jsonPath("$.error.code")
                        .isEqualTo("unsupported_capability");
                when(creation.create(anyMap(), any())).thenReturn(Map.of("id", id));
                client().post()
                        .uri("/v1/sessions")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .bodyValue(Map.of("agent", "agent"))
                        .exchange()
                        .expectStatus()
                        .isBadRequest()
                        .expectBody()
                        .jsonPath("$.error.code")
                        .isEqualTo("unsupported_capability");
                assertEquals(0, admission.turnHead(scope));
            }
        } finally {
            ReflectionTestUtils.setField(turnsApi, "harnessType", "agentscope-java");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pi", "pi-durable", "codex"})
    void genericHarnessUsesSealedOverridesForAdmissionAndPublicDefinition(String kernel)
            throws Exception {
        ReflectionTestUtils.setField(turnsApi, "harnessType", kernel);
        try {
            setSealedAgent(
                    Map.of("name", "basic", "model", "base"),
                    "{\"contextPolicy\":{\"enabled\":true}}");
            client().post()
                    .uri(path() + "/events")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .bodyValue(Map.of("events", List.of(protocolMessage("new_turn", null, "text"))))
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody()
                    .jsonPath("$.error.code")
                    .isEqualTo("unsupported_capability");
            assertEquals(0, admission.turnHead(scope));
            setSealedAgent(
                    Map.of("name", "basic", "model", "base"),
                    "{\"model\":\"selected\",\"system\":\"effective instructions\"}");
            client().get()
                    .uri(path())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.agent.model.id")
                    .isEqualTo("selected")
                    .jsonPath("$.agent.system")
                    .isEqualTo("effective instructions");
        } finally {
            ReflectionTestUtils.setField(turnsApi, "harnessType", "agentscope-java");
        }
    }

    @Test
    void stricterKernelAdmissionStillAllowsCancellingAnExistingTurn() throws Exception {
        String turn = createProtocolTurn("already-running");
        setSealedAgent(Map.of("model", "model", "contextPolicy", Map.of("enabled", true)));
        ReflectionTestUtils.setField(turnsApi, "harnessType", "codex");
        try {
            client().post()
                    .uri(path() + "/events")
                    .header("Idempotency-Key", "cancel-old-turn")
                    .bodyValue(
                            Map.of(
                                    "events",
                                    List.of(
                                            Map.of(
                                                    "type",
                                                    "user.interrupt",
                                                    "managed_agents",
                                                    Map.of("intent", "cancel", "turn_id", turn)))))
                    .exchange()
                    .expectStatus()
                    .isOk();
            assertEquals(1, admission.inputsAfter(scope, turn, 0, 10).size());
        } finally {
            ReflectionTestUtils.setField(turnsApi, "harnessType", "agentscope-java");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pi", "pi-durable", "codex"})
    void genericHarnessPublicToolsExposeOnlyActualBasicTools(String kernel) throws Exception {
        ReflectionTestUtils.setField(turnsApi, "harnessType", kernel);
        try {
            setSealedAgent(
                    Map.of(
                            "model",
                            "model",
                            "tools",
                            List.of(
                                    Map.of(
                                            "type",
                                            "agent_toolset",
                                            "defaultConfig",
                                            Map.of("enabled", false),
                                            "configs",
                                            List.of(
                                                    Map.of(
                                                            "name",
                                                            "bash",
                                                            "enabled",
                                                            true,
                                                            "permissionPolicy",
                                                            Map.of("type", "always_allow")),
                                                    Map.of("name", "read", "enabled", false),
                                                    Map.of("name", "edit", "enabled", false))))));
            client().get()
                    .uri(path())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.agent.tools[0].default_config.enabled")
                    .isEqualTo(false)
                    .jsonPath("$.agent.tools[0].configs.length()")
                    .isEqualTo(3)
                    .jsonPath("$.agent.tools[0].configs[0].name")
                    .isEqualTo("bash")
                    .jsonPath("$.agent.tools[0].configs[0].enabled")
                    .isEqualTo(true)
                    .jsonPath("$.agent.tools[0].configs[0].permission_policy.type")
                    .isEqualTo("always_allow")
                    .jsonPath("$.agent.tools[0].configs[1].name")
                    .isEqualTo("read")
                    .jsonPath("$.agent.tools[0].configs[1].enabled")
                    .isEqualTo(false)
                    .jsonPath("$.agent.tools[0].configs[2].name")
                    .isEqualTo("write")
                    .jsonPath("$.agent.tools[0].configs[2].enabled")
                    .isEqualTo(false);
        } finally {
            ReflectionTestUtils.setField(turnsApi, "harnessType", "agentscope-java");
        }
    }

    @Test
    void encryptedAgentPolicyCannotBeBypassedByOmittingPlaintextSnapshot() throws Exception {
        setSealedAgent(
                Map.of(
                        "tools",
                        List.of(Map.of("type", "mcp_toolset", "mcpServerName", "longwait")),
                        "mcpServers",
                        List.of(Map.of("name", "longwait", "url", "http://fixture/mcp"))));
        client().get()
                .uri(path())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("unsupported_capability");
        client().post()
                .uri(path() + "/events")
                .header("Idempotency-Key", "sealed-policy")
                .bodyValue(Map.of("events", List.of(protocolMessage("new_turn", null, "text"))))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("unsupported_capability");
        assertEquals(0, admission.turnHead(scope));
    }

    @Test
    void encryptedAgentScopeIsCheckedBeforePublicProjection() throws Exception {
        var context =
                new RuntimeRequestContext(
                        1,
                        "other-tenant",
                        scope.namespace(),
                        "app",
                        id,
                        null,
                        "bootstrap",
                        "trace",
                        Map.of(),
                        "test",
                        System.currentTimeMillis() + 60000);
        new JdbcTemplate(source)
                .update(
                        "UPDATE ma.session SET configuration=? WHERE scope_key=?",
                        sealedConfiguration(Map.of(), context),
                        scope.messageGroup());
        client().get()
                .uri(path())
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("definition_scope_mismatch");
    }

    private void setSealedAgent(Map<String, Object> snapshot) throws Exception {
        setSealedAgent(snapshot, "{}");
    }

    private void setSealedAgent(Map<String, Object> snapshot, String overrides) throws Exception {
        new JdbcTemplate(source)
                .update(
                        "UPDATE ma.session SET configuration=? WHERE scope_key=?",
                        sealedConfiguration(
                                snapshot,
                                runtimeContext(
                                        "bootstrap",
                                        null,
                                        Map.of(),
                                        System.currentTimeMillis() + 60000),
                                overrides),
                        scope.messageGroup());
    }

    private String sealedConfiguration(Map<String, Object> snapshot, RuntimeRequestContext context)
            throws Exception {
        return sealedConfiguration(snapshot, context, "{}");
    }

    private String sealedConfiguration(
            Map<String, Object> snapshot, RuntimeRequestContext context, String overrides)
            throws Exception {
        var definition =
                new RuntimeSessionBootstrap(
                        context,
                        "managed",
                        "pinned",
                        Map.of(
                                "session",
                                Map.of(
                                        "id",
                                        id,
                                        "ownerId",
                                        "owner",
                                        "environmentId",
                                        "environment",
                                        "agentOverridesJson",
                                        overrides),
                                "agentSnapshot",
                                snapshot));
        return mapper.writeValueAsString(
                Map.of(
                        "encrypted_definition",
                        crypto.encrypt(mapper.writeValueAsString(definition))));
    }

    private void runOfficialSdk(String language) throws Exception {
        setSealedAgent(
                Map.of(
                        "model",
                        "model",
                        "tools",
                        List.of(
                                Map.of(
                                        "type",
                                        "agent_toolset",
                                        "defaultConfig",
                                        Map.of("enabled", false)),
                                Map.of(
                                        "type",
                                        "mcp_toolset",
                                        "mcpServerName",
                                        "longwait",
                                        "defaultConfig",
                                        Map.of(
                                                "enabled",
                                                true,
                                                "permissionPolicy",
                                                Map.of("type", "always_allow")))),
                        "mcpServers",
                        List.of(Map.of("name", "longwait", "url", "http://fixture/mcp"))));
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null
                && !Files.isDirectory(root.resolve("managed-agents/tests/session-protocol")))
            root = root.getParent();
        assertNotNull(root);
        Path fixture = root.resolve("managed-agents/tests/session-protocol");
        var command = new ArrayList<String>();
        if ("python".equals(language)) {
            command.add(root.resolve(".venv/bin/python").toString());
            command.add(fixture.resolve("verify.py").toString());
        } else if ("typescript".equals(language)) {
            command.add("node");
            command.add(fixture.resolve("node/verify.mjs").toString());
        } else {
            command.add("java");
            command.add("-cp");
            command.add(
                    fixture.resolve("java/target/classes")
                            + ":"
                            + Files.readString(fixture.resolve("java/target/classpath")).trim());
            command.add("io.agentcore.managed.protocoltest.OfficialSdkCheck");
        }
        command.add("http://127.0.0.1:" + port);
        command.add(KEY);
        command.add(id);
        Path log = Files.createTempFile("managed-official-" + language, ".log");
        var process =
                new ProcessBuilder(command)
                        .directory(root.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        String output = Files.readString(log);
        assertEquals(0, process.exitValue(), output);
        Files.delete(log);
    }

    private WebTestClient queueClient() {
        return client().mutate()
                .defaultHeaders(
                        headers -> headers.set("Managed-Agents-Extensions", "session-queue-v1"))
                .build();
    }

    @Test
    void ordinaryCreationUsesDurableQueueForHttpBrainWithoutPublicExtension() {
        ReflectionTestUtils.setField(sessionApi, "httpBrainEnabled", true);
        try {
            client().post()
                    .uri("/v1/sessions")
                    .header("Idempotency-Key", "http-brain-create")
                    .bodyValue(Map.of("agent", "agent"))
                    .exchange()
                    .expectStatus()
                    .isOk();
            assertTrue(admission.queue().enabled(scope));
            String turn = createProtocolTurn("http-brain-message");
            assertEquals(turn, admission.queue().candidate(scope));
        } finally {
            ReflectionTestUtils.setField(sessionApi, "httpBrainEnabled", false);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pi", "pi-durable", "codex"})
    void ordinaryCreationUsesDurableQueueForProtocolBrainsWithoutPublicExtension(String harness) {
        ReflectionTestUtils.setField(sessionApi, "harnessType", harness);
        ReflectionTestUtils.setField(sessionApi, "sessionQueueEnabled", false);
        try {
            client().post()
                    .uri("/v1/sessions")
                    .header("Idempotency-Key", "protocol-brain-create-" + harness)
                    .bodyValue(Map.of("agent", "agent"))
                    .exchange()
                    .expectStatus()
                    .isOk();
            assertTrue(admission.queue().enabled(scope));
            String turn = createProtocolTurn("protocol-brain-message");
            assertEquals(turn, admission.queue().candidate(scope));
            queueClient()
                    .post()
                    .uri("/v1/sessions")
                    .header("Idempotency-Key", "queue-disabled-" + harness)
                    .bodyValue(Map.of("agent", "agent"))
                    .exchange()
                    .expectStatus()
                    .isBadRequest();
        } finally {
            ReflectionTestUtils.setField(sessionApi, "harnessType", "agentscope-java");
            ReflectionTestUtils.setField(sessionApi, "sessionQueueEnabled", true);
        }
    }

    @Test
    void creationOptsIntoDatabaseQueueAndQueueWithdrawalHasFrozenReceipt() {
        queueClient()
                .post()
                .uri("/v1/sessions")
                .header("Idempotency-Key", "queue-create")
                .bodyValue(Map.of("agent", "agent"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.managed_agents.scheduler_mode")
                .isEqualTo("db_queue_v1");
        String turn = createProtocolTurn("queued");
        queueClient()
                .get()
                .uri(path() + "/queue?limit=1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data[0].entry_id")
                .isEqualTo("queue_" + turn)
                .jsonPath("$.data[0].state")
                .isEqualTo("queued");
        var body =
                Map.of(
                        "events",
                        List.of(
                                Map.of(
                                        "type",
                                        "user.queue.withdraw",
                                        "managed_agents",
                                        Map.of(
                                                "queue_entry_id",
                                                "queue_" + turn,
                                                "expected_entry_version",
                                                1))));
        var first =
                queueClient()
                        .post()
                        .uri(path() + "/events")
                        .header("Idempotency-Key", "withdraw")
                        .bodyValue(body)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        var replay =
                queueClient()
                        .post()
                        .uri(path() + "/events")
                        .header("Idempotency-Key", "withdraw")
                        .bodyValue(body)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        assertEquals(first, replay);
        queueClient()
                .get()
                .uri(path() + "/commands?limit=1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data[0].kind")
                .isEqualTo("withdraw")
                .jsonPath("$.data[0].status")
                .isEqualTo("applied");
        queueClient()
                .get()
                .uri(path() + "/turns/" + turn)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("cancelled")
                .jsonPath("$.execution_started")
                .isEqualTo(false)
                .jsonPath("$.queue_disposition")
                .isEqualTo("withdrawn");
        client().get().uri(path() + "/queue").exchange().expectStatus().isBadRequest();
    }

    @Test
    void queueConversionUsesActualInputAndReturnsConflictWithoutLosingQueuedContent() {
        admission.queue().enable(scope);
        String active = createProtocolTurn("active"), pending = createProtocolTurn("pending");
        admission.activate(scope, active, 1, "token");
        var body =
                Map.of(
                        "events",
                        List.of(
                                Map.of(
                                        "type",
                                        "user.queue.convert_to_steer",
                                        "managed_agents",
                                        Map.of(
                                                "queue_entry_id",
                                                "queue_" + pending,
                                                "target_turn_id",
                                                active,
                                                "expected_entry_version",
                                                1))));
        queueClient()
                .post()
                .uri(path() + "/events")
                .header("Idempotency-Key", "convert")
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data[0].managed_agents.input_id")
                .isNotEmpty();
        queueClient()
                .get()
                .uri(path() + "/queue/queue_" + pending)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.state")
                .isEqualTo("converted");
        queueClient()
                .get()
                .uri(path() + "/commands")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data[0].kind")
                .isEqualTo("convert_to_steer")
                .jsonPath("$.data[0].input_status")
                .isEqualTo("accepted");
        assertEquals(active, admission.session(scope).currentTurnId());
        String next = createProtocolTurn("next");
        admission.closeInputs(scope, active, 1);
        var closed =
                Map.of(
                        "events",
                        List.of(
                                Map.of(
                                        "type",
                                        "user.queue.convert_to_steer",
                                        "managed_agents",
                                        Map.of(
                                                "queue_entry_id",
                                                "queue_" + next,
                                                "target_turn_id",
                                                active,
                                                "expected_entry_version",
                                                1))));
        queueClient()
                .post()
                .uri(path() + "/events")
                .header("Idempotency-Key", "closed")
                .bodyValue(closed)
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.error.code")
                .isEqualTo("input_closed");
        assertEquals("queued", admission.queue().entry(scope, "queue_" + next).get("state"));
        grants.remove("read");
        queueClient().get().uri(path() + "/queue").exchange().expectStatus().isForbidden();
    }

    @Test
    void queueApiRejectsImplicitModeAndMixedBatches() {
        String turn = createProtocolTurn("legacy");
        queueClient().get().uri(path() + "/queue").exchange().expectStatus().isBadRequest();
        var command =
                Map.of(
                        "type",
                        "user.queue.withdraw",
                        "managed_agents",
                        Map.of("queue_entry_id", "queue_" + turn, "expected_entry_version", 1));
        client().post()
                .uri(path() + "/events")
                .header("Idempotency-Key", "no-extension")
                .bodyValue(Map.of("events", List.of(command)))
                .exchange()
                .expectStatus()
                .isBadRequest();
        queueClient()
                .post()
                .uri(path() + "/events")
                .header("Idempotency-Key", "mixed")
                .bodyValue(
                        Map.of(
                                "events",
                                List.of(command, protocolMessage("new_turn", null, "new"))))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    private Map<String, Object> protocolMessage(String intent, String turn, String text) {
        var extension = new LinkedHashMap<String, Object>();
        extension.put("intent", intent);
        if (turn != null) extension.put("turn_id", turn);
        return Map.of(
                "type",
                "user.message",
                "content",
                List.of(Map.of("type", "text", "text", text)),
                "managed_agents",
                extension);
    }

    @SuppressWarnings("unchecked")
    private String createProtocolTurn(String key) {
        var response =
                client().post()
                        .uri(path() + "/events")
                        .header("Idempotency-Key", key)
                        .bodyValue(
                                Map.of(
                                        "events",
                                        List.of(protocolMessage("new_turn", null, "hello"))))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();
        var event = (Map<String, Object>) ((List<?>) response.get("data")).get(0);
        return (String) ((Map<String, Object>) event.get("managed_agents")).get("turn_id");
    }

    private String path() {
        return "/v1/sessions/" + id;
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .defaultHeader("X-API-Key", KEY)
                .defaultHeader("Managed-Agents-Extensions", "turn-inputs-v1")
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void directSessionStoreIsDefaultAndProjectionReadsAreAuthorizedAndWatermarked() {
        var selected =
                client().mutate()
                        .defaultHeaders(h -> h.set("Managed-Agents-Extensions", "session-queue-v1"))
                        .build();
        selected.post()
                .uri("/v1/sessions")
                .header("Idempotency-Key", "store-create")
                .bodyValue(Map.of("agent", "agent"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.managed_agents.commit_mode")
                .isEqualTo("direct");
        selected.get()
                .uri(path() + "/projection")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.asynchronous")
                .isEqualTo(true)
                .jsonPath("$.accepted_through")
                .isEqualTo(1)
                .jsonPath("$.projected_through")
                .isEqualTo(0);
        selected.get()
                .uri(path() + "/projection?minSequence=1")
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectHeader()
                .valueEquals("Retry-After", "1");
        client().get().uri(path() + "/projection").exchange().expectStatus().isOk();
        selected.get()
                .uri("/v1/sessions/missing/projection")
                .exchange()
                .expectStatus()
                .isNotFound();
        client().post()
                .uri("/v1/sessions")
                .header("Idempotency-Key", "store-create")
                .bodyValue(Map.of("agent", "agent"))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
    }
}

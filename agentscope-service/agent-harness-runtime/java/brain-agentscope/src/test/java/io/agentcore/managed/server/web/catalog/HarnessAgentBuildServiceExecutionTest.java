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
package io.agentcore.managed.server.web.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.agentcore.managed.harness.runtime.CanonicalJson;
import io.agentcore.managed.harness.runtime.RunConfigurationContract;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentcore.managed.server.control.ControlPlaneClient;
import io.agentcore.managed.server.control.SessionResolveResult;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ExecutionOwner;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedTurnExecution;
import io.agentcore.managed.server.web.catalog.spec.EffectiveAgentDefinition;
import io.agentcore.managed.server.web.managed.ManagedSessionDto;
import io.agentcore.managed.server.web.managed.MemoryMountService;
import io.agentcore.managed.server.web.managed.SessionAgentBuildSpec;
import io.agentcore.managed.server.web.managed.SessionResourceMountService;
import io.agentcore.managed.server.web.managed.VaultCredentialResolver;
import io.agentcore.managed.server.web.toolbus.ToolConfirmationMiddleware;
import io.agentcore.managed.server.web.toolbus.ToolEventBus;
import io.agentcore.managed.server.web.workspace.SharedWorkspacePaths;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class HarnessAgentBuildServiceExecutionTest {
    @TempDir Path root;

    @Test
    void managedFactoryConsumesPreparedBindingAndRejectsMismatchedDefinition() throws Exception {
        var mapper = new ObjectMapper();
        String overrides = "{\"model\":\"selected\",\"system\":\"session instructions\"}";
        var session =
                mapper.convertValue(
                        Map.of(
                                "id",
                                "session",
                                "ownerId",
                                "owner",
                                "agentId",
                                "agent",
                                "agentVersion",
                                3,
                                "agentOverridesJson",
                                overrides),
                        ManagedSessionDto.class);
        var snapshot =
                Map.<String, Object>of(
                        "name",
                        "managed-model-test",
                        "model",
                        "original",
                        "system",
                        "base instructions",
                        "maxIters",
                        3,
                        "tools",
                        List.of(
                                Map.of(
                                        "type",
                                        "agent_toolset",
                                        "defaultConfig",
                                        Map.of("enabled", false))));
        var resolved =
                new SessionResolveResult(
                        session, snapshot, null, null, null, Map.of(), null, List.of(), List.of(),
                        Map.of(), Map.of());
        var control = mock(ControlPlaneClient.class);
        when(control.resolveSession("session")).thenReturn(resolved);
        var scope = new ManagedScope("tenant", "space", "session");
        var execution = mock(ManagedTurnExecution.class);
        when(execution.scope()).thenReturn(scope);
        when(execution.turnId()).thenReturn("turn");
        when(execution.epoch()).thenReturn(2L);
        when(execution.owner()).thenReturn(new ExecutionOwner(scope, "turn", 2, "owner-token"));
        var reply = new AtomicReference<JsonNode>();
        var modelRequest = new AtomicReference<JsonNode>();
        var coordinationRequest = new AtomicReference<JsonNode>();
        var key = new AtomicReference<String>();
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext(
                "/prepared/coordination",
                exchange -> {
                    coordinationRequest.set(mapper.readTree(exchange.getRequestBody()));
                    byte[] bytes =
                            mapper.writeValueAsBytes(
                                    Map.of("protocolVersion", "0.1.0", "result", reply.get()));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        http.createContext(
                "/model",
                exchange -> {
                    modelRequest.set(mapper.readTree(exchange.getRequestBody()));
                    key.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] bytes =
                            ManagedAgentModelsTest.openaiResponse()
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        http.start();
        var builds =
                new HarnessAgentBuildService(
                        Optional.empty(),
                        mock(ToolEventBus.class),
                        new SharedWorkspacePaths(root),
                        mock(ToolConfirmationMiddleware.class),
                        mock(MemoryMountService.class),
                        mock(VaultCredentialResolver.class),
                        new InMemoryAgentStateStore(),
                        mock(SessionResourceMountService.class),
                        mock(DefinitionStore.class),
                        control);
        try {
            String base = "http://127.0.0.1:" + http.getAddress().getPort();
            var response =
                    mapper.createObjectNode().put("protocolVersion", "0.1.0").put("turnId", "turn");
            response.set("scope", mapper.valueToTree(scope));
            response.putObject("identity")
                    .put("formatVersion", RunConfigurationContract.FORMAT)
                    .put("harnessType", "agentscope-java")
                    .put("agentId", "agent")
                    .put("agentVersion", 3)
                    .put("sourceDigest", CanonicalJson.hash(mapper.valueToTree(snapshot)))
                    .put(
                            "effectiveDigest",
                            CanonicalJson.hash(
                                    mapper.valueToTree(
                                            EffectiveAgentDefinition.resolve(
                                                    snapshot, overrides))));
            response.putObject("configuration")
                    .put("mode", "binding")
                    .set(
                            "model",
                            new ManagedAgentModelsTest()
                                    .binding(
                                            "native-selected",
                                            base + "/model",
                                            "MANAGED_MODEL_SELECTED",
                                            "openai-completions",
                                            96));
            reply.set(RunConfigurationContract.sealModelBinding(response));
            var models =
                    new ManagedAgentModels(
                            new SessionServiceClient(
                                    URI.create(base),
                                    "internal-token",
                                    mapper,
                                    Duration.ofSeconds(5)),
                            mapper,
                            name ->
                                    "MANAGED_MODEL_SELECTED".equals(name)
                                            ? "selected-secret"
                                            : null);
            ReflectionTestUtils.setField(builds, "managedAgentModels", models);
            var spec = new SessionAgentBuildSpec(3, "local", null, overrides, null, null);
            for (int i = 0; i < 2; i++) {
                try (var agent = builds.buildExecutionAgent(session, spec, execution)) {
                    assertThat(agent.getDelegate().getModel().getModelName())
                            .isEqualTo("native-selected");
                    assertThat(agent.getDelegate().getModel().getContextWindowSize())
                            .isEqualTo(8192);
                    var answer =
                            agent.call(
                                            Msg.builder()
                                                    .role(MsgRole.USER)
                                                    .content(
                                                            TextBlock.builder()
                                                                    .text("hello")
                                                                    .build())
                                                    .build(),
                                            RuntimeContext.builder()
                                                    .sessionId("session")
                                                    .userId("owner")
                                                    .build())
                                    .block(Duration.ofSeconds(10));
                    assertThat(answer.getTextContent()).contains("bound answer");
                    assertThat(modelRequest.get().required("model").asText())
                            .isEqualTo("native-selected");
                    assertThat(modelRequest.get().required("max_tokens").asInt()).isEqualTo(96);
                    assertThat(modelRequest.get().required("messages").toString())
                            .contains("session instructions");
                    assertThat(key.get()).isEqualTo("Bearer selected-secret");
                }
            }
            assertThat(coordinationRequest.get().path("operation").asText())
                    .isEqualTo("model-configuration");
            assertThat(coordinationRequest.get().path("data").path("token").asText())
                    .isEqualTo("owner-token");
            assertThat(coordinationRequest.get().path("data").path("epoch").asLong()).isEqualTo(2L);
            var changed = (ObjectNode) reply.get().deepCopy();
            ((ObjectNode) changed.required("identity")).put("agentVersion", 4);
            reply.set(RunConfigurationContract.sealModelBinding(changed));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> builds.buildExecutionAgent(session, spec, execution));
        } finally {
            builds.close();
            http.stop(0);
        }
    }

    @Test
    void segmentsReconstructTheirAgentWithStableIdentityAndPersistentWorkspace() {
        var session =
                new ManagedSessionDto(
                        "session", "owner", "agent", "owner", 1, "latest", null, "local", null,
                        null, null, null, "idle", null, 0, 0, null);
        var control = mock(ControlPlaneClient.class);
        when(control.resolveSession("session"))
                .thenReturn(
                        new SessionResolveResult(
                                session,
                                Map.of(
                                        "name",
                                        "execution-test",
                                        "system",
                                        "Answer the user.",
                                        "maxIters",
                                        2),
                                null,
                                null,
                                null,
                                Map.of(),
                                null,
                                List.of(),
                                List.of(),
                                null,
                                null));
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("test-model");
        var builds =
                new HarnessAgentBuildService(
                        Optional.of(model),
                        mock(ToolEventBus.class),
                        new SharedWorkspacePaths(root),
                        mock(ToolConfirmationMiddleware.class),
                        mock(MemoryMountService.class),
                        mock(VaultCredentialResolver.class),
                        new InMemoryAgentStateStore(),
                        mock(SessionResourceMountService.class),
                        mock(DefinitionStore.class),
                        control);
        var spec = new SessionAgentBuildSpec(1, "local", null, null, null, null);
        try {
            var first = builds.buildExecutionAgent(session, spec);
            String id = first.getAgentId();
            try (first) {
                first.getWorkspaceManager()
                        .getFilesystem()
                        .write(RuntimeContext.empty(), "/MEMORY.md", "remembered");
            }
            try (var second = builds.buildExecutionAgent(session, spec)) {
                assertThat(second).isNotSameAs(first);
                assertThat(second.getAgentId()).isEqualTo(id);
                assertThat(
                                second.getWorkspaceManager()
                                        .getFilesystem()
                                        .read(RuntimeContext.empty(), "/MEMORY.md", 0, 100)
                                        .fileData()
                                        .content())
                        .isEqualTo("remembered");
            }
            var cached = builds.getOrBuildAgent(session, spec);
            try (var segment = builds.buildExecutionAgent(session, spec)) {
                assertThat(segment).isNotSameAs(cached);
            }
            assertThat(builds.getOrBuildAgent(session, spec)).isSameAs(cached);
            ReflectionTestUtils.setField(builds, "modelTimeoutSeconds", 60L);
            try (var bounded = builds.buildExecutionAgent(session, spec)) {
                assertThat(
                                bounded.getDelegate()
                                        .getGenerateOptions()
                                        .getExecutionConfig()
                                        .getTimeout())
                        .isEqualTo(Duration.ofSeconds(60));
            }
            ReflectionTestUtils.setField(
                    builds,
                    "loadTestModels",
                    new LoadTestModels(
                            true, "{\"short-v1\":{\"chunks\":4}}", (MeterRegistry) null));
            try (var synthetic =
                    builds.buildExecutionAgent(
                            session,
                            new SessionAgentBuildSpec(
                                    1,
                                    "local",
                                    null,
                                    "{\"model\":\"loadtest:short-v1\"}",
                                    null,
                                    null,
                                    null))) {
                assertThat(synthetic.getDelegate().getModel().getModelName())
                        .isEqualTo("loadtest:short-v1");
            }
        } finally {
            builds.close();
        }
    }
}

/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.agentservice.sessionservice.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentcore.managed.harness.runtime.RunConfigurationContract;
import io.agentcore.managed.server.control.SessionResolveResult;
import io.agentcore.managed.server.runtime.ManagedCatalog;
import io.agentcore.managed.server.runtime.managed.FileRuntimeObjectStore;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedMcpCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedSessionProtocol;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import io.agentcore.managed.server.runtime.managed.ManagedSubagents;
import io.agentcore.managed.server.runtime.protocol.RuntimeBootstrapDigest;
import io.agentcore.managed.server.runtime.protocol.RuntimeProtocol;
import io.agentcore.managed.server.runtime.protocol.RuntimeRequestContext;
import io.agentcore.managed.server.runtime.protocol.RuntimeSessionBootstrap;
import io.agentcore.managed.server.web.catalog.spec.AgentSpecCodec;
import io.agentcore.managed.server.web.managed.ManagedSessionDto;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

class HarnessRunConfigurationTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"pi", "pi-durable", "codex"})
    void compilationRejectsUnmappedDefinitionsBeforeSealingToolsOrConfiguration(String kernel)
            throws Exception {
        for (String field : List.of("contextPolicy", "jev")) {
            var fixture =
                    configurationFixture(
                            kernel,
                            Map.of("model", "model"),
                            "{\"" + field + "\":{\"enabled\":true}}");
            assertThrows(
                    ManagedStateException.class,
                    () -> fixture.compiler().resolve(fixture.scope(), fixture.turn()));
            verifyNoInteractions(fixture.mcp());
            assertEquals(
                    0,
                    fixture.jdbc()
                            .queryForObject(
                                    "SELECT COUNT(*) FROM ma.harness_configuration",
                                    Integer.class));
            assertEquals(
                    0,
                    fixture.jdbc()
                            .queryForObject(
                                    "SELECT COUNT(*) FROM ma.harness_model_configuration",
                                    Integer.class));
        }
        var fixture =
                configurationFixture(
                        kernel,
                        Map.of(
                                "model",
                                "model",
                                "tools",
                                List.of(
                                        Map.of(
                                                "type",
                                                "agent_toolset",
                                                "configs",
                                                List.of(Map.of("name", "edit", "enabled", true))))),
                        null);
        assertThrows(
                ManagedStateException.class,
                () -> fixture.compiler().resolve(fixture.scope(), fixture.turn()));
        verifyNoInteractions(fixture.mcp());
    }

    @ParameterizedTest
    @CsvSource({
        "pi,true",
        "pi,false",
        "pi-durable,true",
        "pi-durable,false",
        "codex,true",
        "codex,false"
    })
    void declaredAvailabilityMatchesCompiledToolsAndNativePermissionNames(
            String kernel, boolean publicCompatible) throws Exception {
        String bashPolicy = publicCompatible ? "always_allow" : "always_ask";
        var snapshot =
                Map.<String, Object>of(
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
                                                        Map.of("type", bashPolicy)),
                                                Map.of(
                                                        "name",
                                                        "read",
                                                        "enabled",
                                                        !publicCompatible,
                                                        "permissionPolicy",
                                                        Map.of(
                                                                "type",
                                                                publicCompatible
                                                                        ? "always_allow"
                                                                        : "deny")),
                                                Map.of("name", "write", "enabled", true),
                                                Map.of("name", "edit", "enabled", false)))));
        var fixture = configurationFixture(kernel, snapshot, null);
        var response = fixture.compiler().resolve(fixture.scope(), fixture.turn());
        RunConfigurationContract.validate(response);
        var tools = response.required("configuration").required("tools");
        assertEquals(2, tools.size());
        assertEquals("execute", tools.get(0).required("name").asText());
        assertEquals(bashPolicy, tools.get(0).required("permission").asText());
        assertEquals("write_file", tools.get(1).required("name").asText());
        assertEquals("always_allow", tools.get(1).required("permission").asText());
        if (!publicCompatible) {
            assertThrows(
                    ManagedStateException.class,
                    () -> ManagedSessionProtocol.agentToolsets(snapshot, kernel));
            return;
        }
        var publicConfigs =
                new ObjectMapper()
                        .valueToTree(ManagedSessionProtocol.agentToolsets(snapshot, kernel))
                        .get(0)
                        .required("configs");
        for (var declared : publicConfigs) {
            String nativeName =
                    AgentSpecCodec.toHarnessToolName(declared.required("name").asText());
            boolean found = false;
            for (var tool : tools)
                if (nativeName.equals(tool.required("name").asText())) {
                    found = true;
                    assertEquals(
                            declared.required("permission_policy").required("type"),
                            tool.required("permission"));
                }
            assertEquals(declared.required("enabled").asBoolean(), found);
        }
    }

    private ConfigurationFixture configurationFixture(
            String kernel, Map<String, Object> snapshot, String overrides) throws Exception {
        var mapper = new ObjectMapper();
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var admission = new ManagedAdmissionStore(source, mapper);
        var scope = new ManagedScope("tenant", "test", "session");
        admission.createSession(scope, "{}");
        String turn = admission.acceptTurn(scope, "actor", "input", "{\"text\":\"hello\"}").id();
        var jdbc = new JdbcTemplate(source);
        jdbc.update(
                "INSERT INTO ma.harness_binding(scope_key,harness_type) VALUES(?,?)",
                scope.messageGroup(),
                kernel);
        var sessionJson =
                mapper.createObjectNode()
                        .put("id", "session")
                        .put("ownerId", "owner")
                        .put("agentId", "agent")
                        .put("agentVersion", 1)
                        .put("agentOverridesJson", overrides);
        var resolved =
                new SessionResolveResult(
                        mapper.treeToValue(sessionJson, ManagedSessionDto.class),
                        snapshot,
                        "/unused",
                        null,
                        null,
                        Map.of(),
                        null,
                        List.of(),
                        List.of(),
                        Map.of(),
                        Map.of());
        var catalog = mock(ManagedCatalog.class);
        when(catalog.resolve(scope)).thenReturn(resolved);
        var mcp = mock(ManagedMcpCatalog.class);
        when(mcp.seal(eq(resolved), any())).thenReturn("{}");
        when(mcp.require(eq(scope), any(), eq(resolved), eq(turn)))
                .thenReturn(new ManagedMcpCatalog.Description(List.of(), List.of()));
        var compiler =
                new HarnessRunConfiguration(
                        source,
                        catalog,
                        mcp,
                        admission,
                        new FileRuntimeObjectStore("test", root),
                        mapper,
                        "openai");
        ReflectionTestUtils.setField(
                compiler,
                "modelBindings",
                mapper.writeValueAsString(
                        Map.of(
                                "model",
                                Map.of(
                                        "provider",
                                        "openai",
                                        "id",
                                        "model",
                                        "api",
                                        "openai-responses",
                                        "baseUrl",
                                        "https://example.invalid/v1",
                                        "credentialEnv",
                                        "MANAGED_MODEL_TEST",
                                        "contextWindow",
                                        8192,
                                        "outputLimit",
                                        1024))));
        return new ConfigurationFixture(compiler, scope, turn, jdbc, mcp);
    }

    private record ConfigurationFixture(
            HarnessRunConfiguration compiler,
            ManagedScope scope,
            String turn,
            JdbcTemplate jdbc,
            ManagedMcpCatalog mcp) {}

    @Test
    void verifiesTheSharedConfigurationVectorUsedByTypescriptAndRust() throws Exception {
        Path repo = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        String fixture =
                "managed-agents/agent-harness-runtime/protocol/examples/runtime-operations.json";
        while (repo != null && !Files.isRegularFile(repo.resolve(fixture))) repo = repo.getParent();
        if (repo == null) throw new IllegalStateException("Missing shared protocol fixture");
        var response =
                new ObjectMapper()
                        .readTree(Files.readAllBytes(repo.resolve(fixture)))
                        .required("configurationResponse");
        RunConfigurationContract.validate(response);
        assertEquals(response, RunConfigurationContract.seal((ObjectNode) response));
        var modified = response.deepCopy();
        ((ObjectNode) modified.required("identity")).put("agentVersion", 4);
        assertThrows(
                IllegalArgumentException.class, () -> RunConfigurationContract.validate(modified));
    }

    @ParameterizedTest
    @CsvSource({"always_allow,true,3", "always_ask,true,3", "deny,true,0", "always_allow,false,0"})
    void compilesActualDefinitionPermissionsAndPinsEffectiveOverrides(
            String policy, boolean enabled, int toolCount) throws Exception {
        var mapper = new ObjectMapper();
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var admission = new ManagedAdmissionStore(source, mapper);
        var scope = new ManagedScope("tenant", "test", "session");
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
                                "{\"system\":\"session"
                                    + " instructions\",\"model\":\"session-model\",\"maxIters\":7}"),
                        ManagedSessionDto.class);
        var resolved =
                new SessionResolveResult(
                        session,
                        Map.of(
                                "name",
                                "agent",
                                "system",
                                "original",
                                "model",
                                "base-model",
                                "maxIters",
                                20,
                                "tools",
                                List.of(
                                        Map.of(
                                                "type",
                                                "agent_toolset",
                                                "defaultConfig",
                                                Map.of(
                                                        "enabled",
                                                        enabled,
                                                        "permissionPolicy",
                                                        Map.of("type", policy))))),
                        "/unused",
                        null,
                        null,
                        Map.of(),
                        null,
                        List.of(),
                        List.of(),
                        Map.of(),
                        Map.of());
        var crypto = new VaultCrypto(new MockEnvironment(), "configuration-fixture");
        Map<String, Object> sealed = mapper.convertValue(resolved, new TypeReference<>() {});
        var bootstrap =
                new RuntimeSessionBootstrap(
                        new RuntimeRequestContext(
                                RuntimeProtocol.VERSION,
                                scope.tenant(),
                                scope.namespace(),
                                null,
                                scope.sessionId(),
                                null,
                                "bootstrap",
                                "trace",
                                Map.of(),
                                "initial",
                                Long.MAX_VALUE),
                        "managed",
                        RuntimeBootstrapDigest.configurationRevision(mapper, sealed),
                        sealed);
        admission.createSession(
                scope,
                mapper.writeValueAsString(
                        Map.of(
                                "encrypted_definition",
                                crypto.encrypt(mapper.writeValueAsString(bootstrap)))));
        String turn = admission.acceptTurn(scope, "actor", "input", "{\"text\":\"hello\"}").id();
        var jdbc = new JdbcTemplate(source);
        jdbc.update(
                "INSERT INTO ma.harness_binding(scope_key,harness_type) VALUES(?,?)",
                scope.messageGroup(),
                "pi");
        var catalog =
                spy(new ManagedCatalog(admission, mapper, crypto, mock(ManagedSubagents.class)));
        var mcp = mock(ManagedMcpCatalog.class);
        when(mcp.seal(eq(resolved), any())).thenReturn("{}");
        when(mcp.require(eq(scope), any(), eq(resolved), eq(turn)))
                .thenReturn(new ManagedMcpCatalog.Description(List.of(), List.of()));
        var compiler =
                new HarnessRunConfiguration(
                        source,
                        catalog,
                        mcp,
                        admission,
                        new FileRuntimeObjectStore("test", root),
                        mapper,
                        "openai");
        ReflectionTestUtils.setField(
                compiler,
                "modelBindings",
                "{\"session-model\":{\"provider\":\"bound-provider\",\"id\":\"native-session-model\",\"credentialEnv\":\"MANAGED_MODEL_SESSION\"}}");
        var envelope = compiler.resolve(scope, turn);
        RunConfigurationContract.validate(envelope);
        var identity = envelope.path("identity");
        assertEquals("agent", identity.path("agentId").asText());
        assertEquals(3, identity.path("agentVersion").asInt());
        assertNotEquals(identity.path("sourceDigest"), identity.path("effectiveDigest"));
        var configuration = envelope.path("configuration");
        assertEquals("session instructions", configuration.path("system").asText());
        assertEquals("native-session-model", configuration.path("model").path("id").asText());
        assertEquals("bound-provider", configuration.path("model").path("provider").asText());
        assertEquals(
                "MANAGED_MODEL_SESSION",
                configuration.path("model").path("credentialEnv").asText());
        assertEquals(7, configuration.path("maxModelCalls").asInt());
        assertEquals("hello", configuration.path("prompt").asText());
        assertEquals(toolCount, configuration.path("tools").size());
        for (var tool : configuration.path("tools"))
            assertEquals(policy, tool.path("permission").asText());
        assertEquals(envelope, compiler.resolve(scope, turn));
        // A fresh compiler with changed deployment defaults must reuse the sealed Turn config.
        assertEquals(
                envelope,
                new HarnessRunConfiguration(
                                source,
                                catalog,
                                mcp,
                                admission,
                                new FileRuntimeObjectStore("test", root),
                                mapper,
                                "different-provider")
                        .resolve(scope, turn));
        verify(catalog, times(1)).resolve(scope);
        var corrupt = envelope.deepCopy();
        ((ObjectNode) corrupt.required("configuration")).put("system", "changed after admission");
        jdbc.update(
                "UPDATE ma.harness_configuration SET payload=? WHERE scope_key=? AND turn_id=?",
                corrupt.toString(),
                scope.messageGroup(),
                turn);
        assertThrows(IllegalArgumentException.class, () -> compiler.resolve(scope, turn));
        // Even a valid envelope cannot be moved to another Turn's cache key.
        jdbc.update(
                "UPDATE ma.harness_configuration SET payload=? WHERE scope_key=? AND turn_id=?",
                envelope.toString(),
                scope.messageGroup(),
                turn);
        jdbc.update(
                "INSERT INTO ma.harness_configuration(scope_key,turn_id,payload) VALUES(?,?,?)",
                scope.messageGroup(),
                "another-turn",
                envelope.toString());
        assertThrows(IllegalArgumentException.class, () -> compiler.resolve(scope, "another-turn"));
    }

    @Test
    void sealsOnlyDeclaredModelOptionsAndUsesAdmittedIdentity() {
        var mapper = new ObjectMapper();
        var model =
                HarnessRunConfiguration.modelConfiguration(
                        mapper,
                        "managed",
                        "admitted-model",
                        "{\"provider\":\"ignored\",\"id\":\"ignored\",\"api\":\"openai-completions\",\"baseUrl\":\"https://model.example/v1\",\"credentialEnv\":\"PI_MODEL_KEY\",\"parameters\":{\"temperature\":0.2,\"maxTokens\":512}}");
        assertEquals("managed", model.path("provider").asText());
        assertEquals("admitted-model", model.path("id").asText());
        assertEquals(512, model.path("parameters").path("maxTokens").asInt());
        for (String invalid :
                new String[] {
                    "[]",
                    "{\"apiKey\":\"secret\"}",
                    "{\"credentialEnv\":\"MANAGED_AGENTS_INTERNAL_TOKEN\"}",
                    "{\"parameters\":{\"maxTokens\":0}}"
                }) {
            var error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    HarnessRunConfiguration.modelConfiguration(
                                            mapper, "managed", "model", invalid));
            assertTrue(error.getMessage().startsWith("Invalid managed model"));
        }
    }

    @Test
    void resolvesIndependentBindingsAndRejectsUnknownAliasesAndUnsupportedCodexModels()
            throws Exception {
        var mapper = new ObjectMapper();
        String bindings =
                """
                {"alpha":{"provider":"first","id":"native-a","api":"openai-responses",
                  "baseUrl":"https://alpha.example/v1","credentialEnv":"MANAGED_MODEL_ALPHA",
                  "contextWindow":32000,"outputLimit":2048,"parameters":{"maxTokens":512}},
                 "beta":{"provider":"second","id":"native-b","api":"anthropic-messages",
                  "baseUrl":"https://beta.example/v1","credentialEnv":"PI_MODEL_BETA",
                  "contextWindow":64000,"outputLimit":4096,"reasoning":false}}
                """;
        var alpha =
                HarnessRunConfiguration.modelConfiguration(
                        mapper, "ignored", "alpha", "{}", bindings, "codex");
        var beta =
                HarnessRunConfiguration.modelConfiguration(
                        mapper, "ignored", "beta", "{}", bindings, "pi");
        assertEquals(mapper.readTree(bindings).get("alpha"), alpha);
        assertEquals(mapper.readTree(bindings).get("beta"), beta);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HarnessRunConfiguration.modelConfiguration(
                                mapper, "ignored", "missing", "{}", bindings, "pi"));
        assertEquals(
                "Codex requires model.api openai-responses",
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        HarnessRunConfiguration.modelConfiguration(
                                                mapper, "ignored", "beta", "{}", bindings, "codex"))
                        .getMessage());
        for (String options :
                new String[] {
                    "{\"baseUrl\":\"https://user:secret@example.invalid\",\"credentialEnv\":\"MANAGED_MODEL_KEY\"}",
                    "{\"baseUrl\":\"https://models.example/v1\"}",
                    "{\"contextWindow\":100,\"outputLimit\":101}",
                    "{\"outputLimit\":100,\"parameters\":{\"maxTokens\":101}}",
                    "{\"reasoning\":false,\"parameters\":{\"reasoning\":\"low\"}}"
                }) {
            var error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    HarnessRunConfiguration.modelConfiguration(
                                            mapper, "managed", "native", options));
            assertTrue(
                    !error.getMessage().contains("secret")
                            && !error.getMessage().contains("example"));
        }
    }
}

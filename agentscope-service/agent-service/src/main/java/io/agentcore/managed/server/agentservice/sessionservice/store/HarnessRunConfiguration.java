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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentcore.managed.harness.runtime.CanonicalJson;
import io.agentcore.managed.harness.runtime.ModelConfigurationContract;
import io.agentcore.managed.harness.runtime.RunConfigurationContract;
import io.agentcore.managed.server.control.SessionResolveResult;
import io.agentcore.managed.server.runtime.ManagedCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedMcpCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedSessionProtocol;
import io.agentcore.managed.server.runtime.managed.RuntimeObjectStore;
import io.agentcore.managed.server.web.catalog.spec.AgentSpecCodec;
import io.agentcore.managed.server.web.catalog.spec.EffectiveAgentDefinition;
import io.agentcore.managed.server.web.managed.AgentVersionSnapshot;
import io.agentcore.managed.server.web.managed.VaultCredentialResolver;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses the admitted, sealed definition; a Brain cannot provide its own tool credentials. */
@Component
public final class HarnessRunConfiguration {
    private final ManagedCatalog catalog;
    private final ManagedMcpCatalog mcp;
    private final ManagedAdmissionStore admission;
    private final RuntimeObjectStore objects;
    private final ObjectMapper mapper;
    private final String provider;

    @Value("${managed-agents.runtime.model-options:${managed-agents.runtime.pi.model-options:{}}}")
    private String modelOptions = "{}";

    @Value("${managed-agents.runtime.model-bindings:{}}")
    private String modelBindings = "{}";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public HarnessRunConfiguration(
            DataSource source,
            ManagedCatalog catalog,
            ManagedMcpCatalog mcp,
            ManagedAdmissionStore admission,
            RuntimeObjectStore objects,
            ObjectMapper mapper,
            @Value(
                            "${managed-agents.runtime.model-provider:${managed-agents.runtime.pi.provider:openai}}")
                    String provider) {
        this.jdbc = new JdbcTemplate(source);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.tx.setTimeout(15);
        this.catalog = catalog;
        this.mcp = mcp;
        this.admission = admission;
        this.objects = objects;
        this.mapper = mapper;
        this.provider = provider;
    }

    public JsonNode resolve(ManagedScope scope, String turn) {
        var cached = configuration(scope, turn);
        if (cached != null) return cached;
        // Discovery/sealing may perform I/O. Never hold the Session row lock during it.
        var candidate = prepare(scope, turn);
        return tx.execute(
                ignored -> {
                    jdbc.queryForObject(
                            "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE",
                            String.class,
                            scope.messageGroup());
                    var prior = configuration(scope, turn);
                    if (prior != null) return prior;
                    jdbc.update(
                            "INSERT INTO ma.harness_configuration(scope_key,turn_id,payload)"
                                    + " VALUES(?,?,?)",
                            scope.messageGroup(),
                            turn,
                            candidate.toString());
                    return candidate;
                });
    }

    private JsonNode configuration(ManagedScope scope, String turn) {
        var values =
                jdbc.queryForList(
                        "SELECT payload FROM ma.harness_configuration WHERE scope_key=? AND"
                                + " turn_id=?",
                        String.class,
                        scope.messageGroup(),
                        turn);
        if (values.isEmpty()) return null;
        var value = parse(values.get(0));
        RunConfigurationContract.validate(value);
        if (!mapper.valueToTree(scope).equals(value.required("scope"))
                || !turn.equals(value.required("turnId").asText())
                || !harnessType(scope)
                        .equals(value.required("identity").required("harnessType").asText()))
            throw new IllegalArgumentException("Cached configuration binding differs");
        return value;
    }

    private String harnessType(ManagedScope scope) {
        return jdbc.queryForObject(
                "SELECT harness_type FROM ma.harness_binding WHERE scope_key=?",
                String.class,
                scope.messageGroup());
    }

    /** Both execution profiles use this immutable, credential-free model selection. */
    public JsonNode resolveModel(ManagedScope scope, String turn, String harnessType) {
        var cached = modelBinding(scope, turn, harnessType);
        if (cached != null) return cached;
        return resolveModel(scope, turn, harnessType, catalog.resolve(scope));
    }

    private JsonNode resolveModel(
            ManagedScope scope, String turn, String harnessType, SessionResolveResult resolved) {
        var cached = modelBinding(scope, turn, harnessType);
        if (cached != null) return cached;
        var effective =
                EffectiveAgentDefinition.resolve(
                        resolved.agentSnapshot(), resolved.session().agentOverridesJson());
        String alias = (String) effective.get("model");
        var response = mapper.createObjectNode();
        response.put("protocolVersion", "0.1.0").put("turnId", turn);
        response.set("scope", mapper.valueToTree(scope));
        var selection = response.putObject("configuration");
        var bindings = modelObject(modelBindings);
        var defaults = modelObject(modelOptions);
        if ("agentscope-java".equals(harnessType) && bindings.isEmpty() && defaults.isEmpty()) {
            selection.put("mode", "native");
            if (alias != null && !alias.isBlank()) selection.put("name", alias);
        } else {
            selection.put("mode", "binding");
            selection.set(
                    "model",
                    modelConfiguration(
                            mapper, provider, alias, modelOptions, modelBindings, harnessType));
        }
        response.putObject("identity")
                .put("formatVersion", RunConfigurationContract.FORMAT)
                .put("harnessType", harnessType)
                .put("agentId", resolved.session().agentId())
                .put("agentVersion", resolved.session().agentVersion())
                .put(
                        "sourceDigest",
                        CanonicalJson.hash(mapper.valueToTree(resolved.agentSnapshot())))
                .put("effectiveDigest", CanonicalJson.hash(mapper.valueToTree(effective)));
        var sealed = RunConfigurationContract.sealModelBinding(response);
        return tx.execute(
                ignored -> {
                    jdbc.queryForObject(
                            "SELECT scope_key FROM ma.session WHERE scope_key=? FOR UPDATE",
                            String.class,
                            scope.messageGroup());
                    var types =
                            jdbc.queryForList(
                                    "SELECT harness_type FROM ma.harness_binding WHERE scope_key=?",
                                    String.class,
                                    scope.messageGroup());
                    if (types.isEmpty()) {
                        jdbc.update(
                                "INSERT INTO ma.harness_binding(scope_key,harness_type)"
                                        + " VALUES(?,?)",
                                scope.messageGroup(),
                                harnessType);
                    } else if (!harnessType.equals(types.get(0))) {
                        throw new IllegalArgumentException(
                                "Session is bound to a different harness");
                    }
                    var prior = modelBinding(scope, turn, harnessType);
                    if (prior != null) return prior;
                    jdbc.update(
                            "INSERT INTO ma.harness_model_configuration(scope_key,turn_id,payload)"
                                    + " VALUES(?,?,?)",
                            scope.messageGroup(),
                            turn,
                            sealed.toString());
                    return sealed;
                });
    }

    private JsonNode modelBinding(ManagedScope scope, String turn, String harnessType) {
        var types =
                jdbc.queryForList(
                        "SELECT harness_type FROM ma.harness_binding WHERE scope_key=?",
                        String.class,
                        scope.messageGroup());
        if (!types.isEmpty() && !harnessType.equals(types.get(0)))
            throw new IllegalArgumentException("Session is bound to a different harness");
        var rows =
                jdbc.queryForList(
                        "SELECT payload FROM ma.harness_model_configuration WHERE scope_key=? AND"
                                + " turn_id=?",
                        String.class,
                        scope.messageGroup(),
                        turn);
        if (rows.isEmpty()) return null;
        var response = parse(rows.get(0));
        RunConfigurationContract.validateModelBinding(response);
        if (types.isEmpty()
                || !mapper.valueToTree(scope).equals(response.required("scope"))
                || !turn.equals(response.required("turnId").asText())
                || !harnessType.equals(
                        response.required("identity").required("harnessType").asText()))
            throw new IllegalArgumentException("Cached model binding differs");
        return response;
    }

    private JsonNode prepare(ManagedScope scope, String turn) {
        var resolved = catalog.resolve(scope);
        var effective =
                EffectiveAgentDefinition.resolve(
                        resolved.agentSnapshot(), resolved.session().agentOverridesJson());
        String harnessType = harnessType(scope);
        ManagedSessionProtocol.validateHarnessDefinition(effective, harnessType);
        var snapshot = mapper.convertValue(effective, AgentVersionSnapshot.class);
        var model =
                resolveModel(scope, turn, harnessType, resolved)
                        .required("configuration")
                        .required("model");
        var toolsets =
                snapshot.tools() == null ? AgentSpecCodec.defaultToolsets() : snapshot.tools();
        var tools =
                VaultCredentialResolver.resolveSessionToolsConfig(
                        AgentSpecCodec.toToolsConfig(toolsets, snapshot.mcpServers()),
                        resolved.vaultCredentials());
        String sealed = mcp.seal(resolved, tools);
        byte[] bytes = sealed.getBytes(StandardCharsets.UTF_8);
        String key = RuntimeObjectStore.sha256(bytes);
        var object =
                objects.put(
                        scope.messageGroup(),
                        "harness-object",
                        ManagedScope.fingerprint(key),
                        bytes);
        var value = mapper.createObjectNode();
        var input = parse(admission.turn(scope, turn).input());
        value.put(
                "prompt",
                input.path("text").isTextual()
                        ? input.get("text").asText()
                        : input.path("message").isTextual()
                                ? input.get("message").asText()
                                : input.toString());
        value.put("system", snapshot.system() == null ? "" : snapshot.system());
        value.put("cwd", "/workspace");
        value.put("maxModelCalls", snapshot.maxIters() == null ? 100 : snapshot.maxIters());
        value.set("model", model);
        value.set(
                "toolConfiguration",
                mapper.valueToTree(
                        Map.of(
                                "scope",
                                scope,
                                "backendId",
                                object.backendId(),
                                "key",
                                key,
                                "sha256",
                                object.sha256(),
                                "size",
                                Long.toString(object.size()),
                                "mediaType",
                                "application/octet-stream",
                                "formatVersion",
                                "managed-tool-target-v1")));
        var descriptors = value.putArray("tools");
        var policies = AgentSpecCodec.toPermissionPolicyMap(toolsets);
        String defaultPolicy = AgentSpecCodec.defaultAgentPermissionPolicy(toolsets);
        for (var definition :
                List.of(
                        Map.of(
                                "name",
                                "execute",
                                "operation",
                                "sandbox.exec",
                                "description",
                                "Execute a command in the managed sandbox.",
                                "parameters",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "command",
                                                Map.of("type", "string"),
                                                "timeout_seconds",
                                                Map.of(
                                                        "type", "integer", "minimum", 1, "maximum",
                                                        600)),
                                        "required",
                                        List.of("command"),
                                        "additionalProperties",
                                        false)),
                        Map.of(
                                "name",
                                "read_file",
                                "operation",
                                "fs.download",
                                "description",
                                "Read a sandbox file; content is base64 encoded.",
                                "parameters",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("path", Map.of("type", "string")),
                                        "required",
                                        List.of("path"),
                                        "additionalProperties",
                                        false)),
                        Map.of(
                                "name",
                                "write_file",
                                "operation",
                                "fs.upload",
                                "description",
                                "Write base64-encoded content to a sandbox file.",
                                "parameters",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "path",
                                                Map.of("type", "string"),
                                                "content",
                                                Map.of("type", "string")),
                                        "required",
                                        List.of("path", "content"),
                                        "additionalProperties",
                                        false)))) {
            String name = definition.get("name").toString();
            String policy = policies.getOrDefault(name, defaultPolicy);
            if (!enabled(tools, name) || "deny".equals(policy)) continue;
            var descriptor = mapper.valueToTree(definition);
            ((ObjectNode) descriptor).put("permission", policy);
            descriptors.add(descriptor);
        }
        for (var tool : mcp.require(scope, tools, resolved, turn).tools()) {
            String policy =
                    policies.getOrDefault(
                            tool.name(),
                            policies.getOrDefault(
                                    tool.server() + "__*",
                                    tool.readOnly() ? "always_allow" : "always_ask"));
            if ("deny".equals(policy)) continue;
            var descriptor = descriptors.addObject();
            descriptor
                    .put("name", tool.name())
                    .put("operation", "mcp.call")
                    .put("description", tool.description() == null ? "" : tool.description())
                    .put("permission", policy);
            descriptor.set("parameters", mapper.valueToTree(tool.inputSchema()));
            var envelope =
                    descriptor
                            .putObject("argumentEnvelope")
                            .put("server", tool.server())
                            .put("tool", tool.remoteName())
                            .put("descriptor_hash", ManagedMcpCatalog.descriptorHash(tool, mapper));
            if (tool.recovery() != null)
                envelope.set("recovery", mapper.valueToTree(tool.recovery()));
        }
        var response = mapper.createObjectNode();
        response.put("protocolVersion", "0.1.0").put("turnId", turn);
        response.set("scope", mapper.valueToTree(scope));
        response.set("configuration", value);
        response.putObject("identity")
                .put("formatVersion", RunConfigurationContract.FORMAT)
                .put("harnessType", harnessType)
                .put("agentId", resolved.session().agentId())
                .put("agentVersion", resolved.session().agentVersion())
                .put(
                        "sourceDigest",
                        CanonicalJson.hash(mapper.valueToTree(resolved.agentSnapshot())))
                .put("effectiveDigest", CanonicalJson.hash(mapper.valueToTree(effective)));
        return RunConfigurationContract.seal(response);
    }

    static JsonNode modelConfiguration(
            ObjectMapper mapper, String provider, String id, String options) {
        return modelConfiguration(mapper, provider, id, options, "{}", "pi");
    }

    static JsonNode modelConfiguration(
            ObjectMapper mapper,
            String provider,
            String id,
            String options,
            String bindings,
            String harnessType) {
        ObjectNode model;
        try {
            var catalog = mapper.readTree(bindings.isBlank() ? "{}" : bindings);
            if (!catalog.isObject()) throw new IllegalArgumentException();
            if (!catalog.isEmpty()) {
                // An explicit catalog is authoritative: no fallback for a misspelled alias.
                if (id == null || !(catalog.get(id) instanceof ObjectNode selected))
                    throw new IllegalArgumentException();
                model = selected.deepCopy();
            } else {
                var parsed = mapper.readTree(options.isBlank() ? "{}" : options);
                if (!(parsed instanceof ObjectNode defaults)) throw new IllegalArgumentException();
                model = defaults.put("provider", provider).put("id", id);
            }
        } catch (Exception invalid) {
            // Never echo credentials or invalid option bodies in configuration failures.
            throw new IllegalArgumentException("Invalid managed model binding or options");
        }
        ModelConfigurationContract.validate(model, harnessType);
        return model;
    }

    private boolean enabled(ToolsConfig tools, String name) {
        if (tools.getDeny() != null
                && (tools.getDeny().contains(name) || tools.getDeny().contains("*"))) return false;
        return tools.getAllow() != null && tools.getAllow().contains(name)
                || tools.isDefaultToolsEnabled() && !tools.isStrictAllow();
    }

    private JsonNode parse(String input) {
        try {
            return mapper.readTree(input);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid admitted input", invalid);
        }
    }

    private ObjectNode modelObject(String input) {
        try {
            var value = mapper.readTree(input.isBlank() ? "{}" : input);
            if (value instanceof ObjectNode object) return object;
        } catch (Exception invalid) {
            // Configuration errors must never echo model credentials or option bodies.
        }
        throw new IllegalArgumentException("Invalid managed model binding or options");
    }
}

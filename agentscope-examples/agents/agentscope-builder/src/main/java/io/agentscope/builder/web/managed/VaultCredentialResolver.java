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
package io.agentscope.builder.web.managed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.builder.web.persistence.jpa.VaultCredentialEntity;
import io.agentscope.builder.web.persistence.jpa.VaultCredentialEntityRepository;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves vault credentials into MCP {@link ToolsConfig} env substitutions so session vault
 * mounts actually reach MCP server processes.
 */
@Component
public class VaultCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(VaultCredentialResolver.class);
    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final VaultService vaultService;
    private final VaultCredentialEntityRepository credentialRepository;

    public VaultCredentialResolver(
            VaultService vaultService, VaultCredentialEntityRepository credentialRepository) {
        this.vaultService = vaultService;
        this.credentialRepository = credentialRepository;
    }

    /**
     * Loads {@code workspace/tools.json}, substitutes {@code ${ENV}} using vault secrets (falling
     * back to {@link System#getenv}), and merges targeted credentials into MCP server env maps.
     *
     * @return patched config, or {@code null} when tools.json is missing/unreadable
     */
    public ToolsConfig resolveToolsConfig(String ownerId, Path workspace, List<String> vaultIds) {
        Map<String, String> secrets = resolveEnvSecrets(ownerId, vaultIds);
        Path toolsJson = workspace.resolve(WorkspaceConstants.TOOLS_JSON);
        if (!Files.isRegularFile(toolsJson)) {
            if (secrets.isEmpty()) {
                return null;
            }
            // No tools.json but vaults present — synthesize empty config with nothing to inject.
            return null;
        }
        try {
            String raw = Files.readString(toolsJson, StandardCharsets.UTF_8);
            String substituted = substituteEnv(raw, secrets);
            ToolsConfig cfg = MAPPER.readValue(substituted, ToolsConfig.class);
            mergeTargetedCredentials(cfg, ownerId, vaultIds, secrets);
            return cfg;
        } catch (Exception ex) {
            log.warn(
                    "Failed to resolve vault-patched tools.json at {}: {}",
                    toolsJson,
                    ex.getMessage());
            return null;
        }
    }

    /**
     * Flattens vault credentials into env-var → plaintext secret. Supports:
     *
     * <ul>
     *   <li>JSON object secret → each key/value becomes an env entry
     *   <li>plain secret + {@code target} looking like {@code ENV_NAME} → single env entry
     * </ul>
     */
    public Map<String, String> resolveEnvSecrets(String ownerId, List<String> vaultIds) {
        Map<String, String> out = new LinkedHashMap<>();
        if (vaultIds == null || vaultIds.isEmpty()) {
            return out;
        }
        for (String vaultId : vaultIds) {
            try {
                vaultService.get(ownerId, vaultId);
            } catch (Exception ex) {
                log.warn("Skipping inaccessible vault {}: {}", vaultId, ex.getMessage());
                continue;
            }
            for (VaultCredentialEntity cred :
                    credentialRepository.findByVaultIdOrderByCreatedAtAsc(vaultId)) {
                String plaintext;
                try {
                    plaintext = vaultService.getDecryptedSecret(ownerId, cred.getCredentialId());
                } catch (Exception ex) {
                    log.warn(
                            "Failed to decrypt credential {}: {}",
                            cred.getCredentialId(),
                            ex.getMessage());
                    continue;
                }
                Map<String, String> asMap = tryParseJsonMap(plaintext);
                if (asMap != null && !asMap.isEmpty()) {
                    out.putAll(asMap);
                    continue;
                }
                String target = cred.getTarget();
                if (target != null && target.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    out.put(target, plaintext);
                } else if (cred.getLabel() != null
                        && cred.getLabel().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    out.put(cred.getLabel(), plaintext);
                }
            }
        }
        return out;
    }

    private void mergeTargetedCredentials(
            ToolsConfig cfg,
            String ownerId,
            List<String> vaultIds,
            Map<String, String> globalSecrets) {
        if (cfg.getMcpServers() == null || cfg.getMcpServers().isEmpty()) {
            return;
        }
        if (vaultIds == null) {
            return;
        }
        for (String vaultId : vaultIds) {
            for (VaultCredentialEntity cred :
                    credentialRepository.findByVaultIdOrderByCreatedAtAsc(vaultId)) {
                String target = cred.getTarget();
                if (target == null || !cfg.getMcpServers().containsKey(target)) {
                    continue;
                }
                McpServerConfig server = cfg.getMcpServers().get(target);
                Map<String, String> env =
                        server.getEnv() != null
                                ? new LinkedHashMap<>(server.getEnv())
                                : new LinkedHashMap<>();
                env.putAll(globalSecrets);
                String plaintext;
                try {
                    plaintext = vaultService.getDecryptedSecret(ownerId, cred.getCredentialId());
                } catch (Exception ex) {
                    continue;
                }
                Map<String, String> asMap = tryParseJsonMap(plaintext);
                if (asMap != null) {
                    env.putAll(asMap);
                } else {
                    env.put(
                            cred.getLabel() != null && !cred.getLabel().isBlank()
                                    ? cred.getLabel()
                                    : "SECRET",
                            plaintext);
                }
                server.setEnv(env);
            }
        }
        // Also inject global secrets into every stdio server env for ${}-free configs.
        if (!globalSecrets.isEmpty()) {
            for (McpServerConfig server : cfg.getMcpServers().values()) {
                if (server.getEnv() == null) {
                    server.setEnv(new LinkedHashMap<>(globalSecrets));
                } else {
                    Map<String, String> env = new LinkedHashMap<>(globalSecrets);
                    env.putAll(server.getEnv());
                    server.setEnv(env);
                }
            }
        }
    }

    private static Map<String, String> tryParseJsonMap(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        String trimmed = plaintext.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            return MAPPER.readValue(trimmed, STRING_MAP);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String substituteEnv(String raw, Map<String, String> secrets) {
        Matcher matcher = ENV_VAR.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = secrets.get(name);
            if (value == null) {
                value = System.getenv(name);
            }
            matcher.appendReplacement(
                    sb, Matcher.quoteReplacement(value != null ? value : matcher.group(0)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}

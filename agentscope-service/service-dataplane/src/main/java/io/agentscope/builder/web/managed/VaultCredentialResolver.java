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
import io.agentscope.builder.web.managed.service.VaultService;
import io.agentscope.builder.web.persistence.jpa.VaultCredentialEntity;
import io.agentscope.builder.web.persistence.jpa.VaultCredentialEntityRepository;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
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
     * Patches an in-memory {@link ToolsConfig} (typically derived from the agent version
     * snapshot) with vault secrets: substitutes {@code ${ENV}} placeholders and merges
     * targeted credentials into MCP server env maps.
     *
     * @param base config to patch; when {@code null}, returns {@code null}
     * @return a new patched config, or {@code base} unchanged when no vault secrets apply
     */
    public ToolsConfig resolveToolsConfig(String ownerId, ToolsConfig base, List<String> vaultIds) {
        if (base == null) {
            return null;
        }
        Map<String, String> secrets = resolveEnvSecrets(ownerId, vaultIds);
        ToolsConfig cfg = copyToolsConfig(base);
        if (secrets.isEmpty() && (vaultIds == null || vaultIds.isEmpty())) {
            return cfg;
        }
        try {
            // Round-trip through JSON so ${ENV} placeholders inside string fields are rewritten.
            String raw = MAPPER.writeValueAsString(cfg);
            String substituted = substituteEnv(raw, secrets);
            ToolsConfig parsed = MAPPER.readValue(substituted, ToolsConfig.class);
            mergeTargetedCredentials(parsed, ownerId, vaultIds, secrets);
            return parsed;
        } catch (Exception ex) {
            log.warn(
                    "Failed to vault-patch ToolsConfig for owner {}: {}", ownerId, ex.getMessage());
            mergeTargetedCredentials(cfg, ownerId, vaultIds, secrets);
            return cfg;
        }
    }

    /** Resolves only session-authorized CP credentials. Never reads ambient process secrets. */
    public ToolsConfig resolveSessionToolsConfig(
            ToolsConfig base, List<Map<String, Object>> credentials) {
        if (base == null) return null;
        ToolsConfig cfg = copyToolsConfig(base);
        if (cfg == base) throw new IllegalStateException("Cannot copy MCP configuration");
        if (cfg.getMcpServers() == null) return cfg;
        Map<String, String> variables = new LinkedHashMap<>();
        if (credentials != null)
            for (Map<String, Object> cred : credentials) {
                String type = String.valueOf(cred.get("type"));
                String target = String.valueOf(cred.get("target"));
                String secret = (String) cred.get("secret");
                if ("environment_variable".equals(type) || "env".equals(type)) {
                    if (!target.matches("[A-Za-z_][A-Za-z0-9_]*") || secret == null)
                        throw new IllegalArgumentException("Invalid environment credential target");
                    if (variables.putIfAbsent(target, secret) != null)
                        throw new IllegalArgumentException(
                                "Ambiguous environment credential: " + target);
                }
            }
        for (var entry : cfg.getMcpServers().entrySet()) {
            McpServerConfig server = entry.getValue();
            server.setHeaders(substituteMap(server.getHeaders(), variables));
            server.setEnv(substituteMap(server.getEnv(), variables));
            server.setQueryParams(substituteMap(server.getQueryParams(), variables));
            boolean authenticated = false;
            if (credentials == null) continue;
            for (Map<String, Object> cred : credentials) {
                String type = String.valueOf(cred.get("type"));
                if (!List.of("static_bearer", "mcp_oauth", "oauth_token").contains(type)) continue;
                String target = String.valueOf(cred.get("target"));
                if (!entry.getKey().equals(target) && !sameEndpoint(target, server.getUrl()))
                    continue;
                if (authenticated)
                    throw new IllegalArgumentException(
                            "Ambiguous MCP credentials for server: " + entry.getKey());
                String token = (String) cred.get("secret");
                if (!"static_bearer".equals(type)) {
                    Map<String, String> oauth = tryParseJsonMap(token);
                    if (oauth == null || !oauth.containsKey("access_token"))
                        throw new IllegalArgumentException("MCP OAuth access token is missing");
                    token = oauth.get("access_token");
                }
                if (token == null
                        || token.isBlank()
                        || token.contains("\r")
                        || token.contains("\n"))
                    throw new IllegalArgumentException("Invalid MCP bearer credential");
                Map<String, String> headers =
                        server.getHeaders() == null
                                ? new LinkedHashMap<>()
                                : new LinkedHashMap<>(server.getHeaders());
                headers.keySet().removeIf(key -> "authorization".equalsIgnoreCase(key));
                headers.put("Authorization", "Bearer " + token);
                server.setHeaders(headers);
                authenticated = true;
            }
        }
        return cfg;
    }

    static boolean sameEndpoint(String left, String right) {
        if (left == null || right == null) return false;
        try {
            return normalizeEndpoint(left).equals(normalizeEndpoint(right));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String normalizeEndpoint(String value) {
        java.net.URI uri = java.net.URI.create(value);
        String scheme = uri.getScheme();
        if (scheme == null
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null) throw new IllegalArgumentException("Invalid MCP URL");
        scheme = scheme.toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https"))
            throw new IllegalArgumentException("Invalid MCP URL scheme");
        int port = uri.getPort();
        if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80))
            port = -1;
        String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
        return scheme
                + "://"
                + uri.getHost().toLowerCase(java.util.Locale.ROOT)
                + (port < 0 ? "" : ":" + port)
                + path
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    private static Map<String, String> substituteMap(
            Map<String, String> values, Map<String, String> variables) {
        if (values == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach(
                (key, value) -> {
                    if (value == null)
                        throw new IllegalArgumentException("Null MCP configuration value");
                    Matcher matcher = ENV_VAR.matcher(value);
                    StringBuilder result = new StringBuilder();
                    while (matcher.find()) {
                        String replacement = variables.get(matcher.group(1));
                        if (replacement == null)
                            throw new IllegalArgumentException(
                                    "Missing session credential: " + matcher.group(1));
                        matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
                    }
                    matcher.appendTail(result);
                    out.put(key, result.toString());
                });
        return out;
    }

    private static ToolsConfig copyToolsConfig(ToolsConfig base) {
        try {
            return MAPPER.readValue(MAPPER.writeValueAsString(base), ToolsConfig.class);
        } catch (Exception ex) {
            return base;
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
                    String type = cred.getCredentialType();
                    if ("mcp_oauth".equalsIgnoreCase(type)
                            || "oauth_token".equalsIgnoreCase(type)) {
                        // Best-effort: prefer access_token; full OAuth refresh is not implemented
                        // in-builder yet — operators should rotate via vault updates.
                        log.debug(
                                "Injecting oauth-shaped credential {} (refresh not auto-managed)",
                                cred.getCredentialId());
                        if (asMap.containsKey("access_token")
                                && !asMap.containsKey("Authorization")) {
                            asMap.putIfAbsent(
                                    "Authorization", "Bearer " + asMap.get("access_token"));
                        }
                    }
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

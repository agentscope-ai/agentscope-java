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

import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ManagedVaultIsolationTest {
    @Test
    void bearerOnlyReachesItsTargetAndDoesNotMutateDefinition() {
        var resolver = new VaultCredentialResolver(null, null);
        var crm = new McpServerConfig();
        crm.setUrl("https://CRM.example:443/mcp/");
        var other = new McpServerConfig();
        other.setUrl("https://crm.example/other");
        var base = new ToolsConfig();
        base.setMcpServers(Map.of("crm", crm, "other", other));
        var result =
                resolver.resolveSessionToolsConfig(
                        base,
                        List.of(
                                Map.of(
                                        "type",
                                        "static_bearer",
                                        "target",
                                        "https://crm.example/mcp",
                                        "secret",
                                        "alice-token")));
        Assertions.assertEquals(
                "Bearer alice-token",
                result.getMcpServers().get("crm").getHeaders().get("Authorization"));
        Assertions.assertNull(result.getMcpServers().get("other").getHeaders());
        Assertions.assertNull(crm.getHeaders());
        Assertions.assertNull(result.getMcpServers().get("crm").getEnv());
    }

    @Test
    void substitutionPreservesJsonCharactersAndNeverUsesProcessEnvironment() {
        var resolver = new VaultCredentialResolver(null, null);
        var server = new McpServerConfig();
        server.setEnv(Map.of("TOKEN", "${TOKEN}"));
        var base = new ToolsConfig();
        base.setMcpServers(Map.of("crm", server));
        String secret = "quote\" newline\n dollar$";
        var result =
                resolver.resolveSessionToolsConfig(
                        base,
                        List.of(
                                Map.of(
                                        "type",
                                        "environment_variable",
                                        "target",
                                        "TOKEN",
                                        "secret",
                                        secret)));
        Assertions.assertEquals(secret, result.getMcpServers().get("crm").getEnv().get("TOKEN"));
        server.setEnv(Map.of("TOKEN", "${PATH}"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolveSessionToolsConfig(base, List.of()));
    }

    @Test
    void differentPathsAndQueriesNeverMatch() {
        Assertions.assertFalse(
                VaultCredentialResolver.sameEndpoint("https://host/a", "https://host/b"));
        Assertions.assertFalse(
                VaultCredentialResolver.sameEndpoint(
                        "https://host/a?tenant=1", "https://host/a?tenant=2"));
        Assertions.assertFalse(
                VaultCredentialResolver.sameEndpoint("https://user@host/a", "https://host/a"));
    }
}

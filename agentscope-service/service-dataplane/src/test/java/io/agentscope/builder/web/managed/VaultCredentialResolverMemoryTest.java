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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.agentscope.builder.web.catalog.spec.AgentSpecCodec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.McpServerSpec;
import io.agentscope.builder.web.managed.service.VaultService;
import io.agentscope.builder.web.persistence.jpa.VaultCredentialEntityRepository;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VaultCredentialResolverMemoryTest {

    @Test
    void resolveToolsConfig_patchesFromInMemoryConfigWithoutDisk() {
        VaultCredentialResolver resolver =
                new VaultCredentialResolver(
                        mock(VaultService.class), mock(VaultCredentialEntityRepository.class));

        ToolsConfig base =
                AgentSpecCodec.toToolsConfig(
                        AgentSpecCodec.defaultToolsets(),
                        List.of(
                                new McpServerSpec(
                                        "demo",
                                        "http",
                                        "https://example.com",
                                        null,
                                        null,
                                        null,
                                        Map.of("TOKEN", "${MISSING_OR_LITERAL}"),
                                        null,
                                        null,
                                        null,
                                        null)));

        ToolsConfig patched = resolver.resolveToolsConfig("alice", base, List.of());
        assertThat(patched).isNotNull();
        assertThat(patched.getMcpServers()).containsKey("demo");
        McpServerConfig server = patched.getMcpServers().get("demo");
        assertThat(server.getUrl()).isEqualTo("https://example.com");
    }

    @Test
    void resolveToolsConfig_nullBaseReturnsNull() {
        VaultCredentialResolver resolver =
                new VaultCredentialResolver(
                        mock(VaultService.class), mock(VaultCredentialEntityRepository.class));
        assertThat(resolver.resolveToolsConfig("alice", null, List.of())).isNull();
    }
}

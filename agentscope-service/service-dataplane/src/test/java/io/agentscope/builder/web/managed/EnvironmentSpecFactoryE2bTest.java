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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.builder.web.api.error.ApiException;
import io.agentscope.builder.web.config.BuilderE2bProperties;
import io.agentscope.extensions.sandbox.e2b.E2bFilesystemSpec;
import io.agentscope.harness.agent.IsolationScope;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EnvironmentSpecFactoryE2bTest {

    private BuilderE2bProperties properties;
    private EnvironmentSpecFactory factory;

    @BeforeEach
    void setUp() {
        properties = new BuilderE2bProperties();
        factory = new EnvironmentSpecFactory(Optional.empty(), Optional.empty(), properties);
    }

    @Test
    void resolveApiKeyPrefersConfigOverProperties() {
        properties.setApiKey("from-props");
        assertThat(factory.resolveE2bApiKey(Map.of("apiKey", "from-config")))
                .contains("from-config");
        assertThat(factory.resolveE2bApiKey(Map.of())).contains("from-props");
    }

    @Test
    void buildE2bSpecRequiresApiKey() {
        assertThatThrownBy(() -> factory.buildE2bFilesystemSpec(IsolationScope.SESSION, Map.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("E2B");
    }

    @Test
    void buildE2bSpecUsesSessionIsolationAndConfig() {
        properties.setApiKey("ek_test");
        E2bFilesystemSpec spec =
                factory.buildE2bFilesystemSpec(
                        IsolationScope.SESSION,
                        Map.of(
                                "templateId",
                                "my-template",
                                "workspaceRoot",
                                "/workspace",
                                "sandboxTimeoutSeconds",
                                120));
        assertThat(spec.getIsolationScope()).isEqualTo(IsolationScope.SESSION);
        assertThat(factory.hasE2bApiKey(Map.of())).isTrue();
    }

    @Test
    void missingApiKeyReportsFalse() {
        assertThat(factory.hasE2bApiKey(null)).isFalse();
        assertThat(factory.hasE2bApiKey(Map.of())).isFalse();
    }
}

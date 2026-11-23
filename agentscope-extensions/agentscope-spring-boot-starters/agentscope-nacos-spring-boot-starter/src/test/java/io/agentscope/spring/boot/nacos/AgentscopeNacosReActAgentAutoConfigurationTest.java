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
package io.agentscope.spring.boot.nacos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.nacos.prompt.NacosPromptListener;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Unit tests for {@link AgentscopeNacosReActAgentAutoConfiguration}. */
class AgentscopeNacosReActAgentAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    AgentscopeNacosPromptAutoConfiguration.class,
                                    AgentscopeNacosReActAgentAutoConfiguration.class,
                                    AgentscopeAutoConfiguration.class))
                    .withBean(Model.class, () -> mock(Model.class))
                    .withPropertyValues("agentscope.agent.enabled=true");

    @Test
    @DisplayName(
            "should leave the ReActAgent to the core starter when Nacos prompts are not enabled")
    void shouldUseCoreReActAgentWhenPromptNotEnabled() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("nacosPromptReActAgent");
                    assertThat(context).hasBean("agentscopeReActAgent");
                    assertThat(context.getBean(ReActAgent.class)).isNotNull();
                });
    }

    @Test
    @DisplayName("should build the ReActAgent with the Nacos prompt when Nacos prompts are enabled")
    void shouldUseNacosPromptWhenEnabled() throws Exception {
        NacosPromptListener listener = mock(NacosPromptListener.class);
        when(listener.getPrompt(eq("agent-prompt"), any(), any(), any(), any()))
                .thenReturn("prompt from nacos");
        try (MockedStatic<AiFactory> mocked = Mockito.mockStatic(AiFactory.class)) {
            mocked.when(() -> AiFactory.createAiService(any(Properties.class)))
                    .thenReturn(mock(AiService.class));

            contextRunner
                    .withBean(NacosPromptListener.class, () -> listener)
                    .withPropertyValues(
                            "agentscope.nacos.server-addr=127.0.0.1:8848",
                            "agentscope.nacos.prompt.enabled=true",
                            "agentscope.nacos.prompt.sys-prompt-key=agent-prompt")
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(context).hasBean("nacosPromptReActAgent");
                                assertThat(context).doesNotHaveBean("agentscopeReActAgent");
                                assertThat(context.getBean(ReActAgent.class).getSysPrompt())
                                        .isEqualTo("prompt from nacos");
                            });
        }
    }
}

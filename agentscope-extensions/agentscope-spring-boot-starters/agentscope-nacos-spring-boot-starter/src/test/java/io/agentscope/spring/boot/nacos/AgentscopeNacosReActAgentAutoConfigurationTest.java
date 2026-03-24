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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.prompt.Prompt;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.Model;
import io.agentscope.core.nacos.prompt.NacosPromptListener;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Unit tests for {@link AgentscopeNacosReActAgentAutoConfiguration}.
 *
 * <p>Verifies that ReActAgent is correctly assembled with Nacos-driven prompts,
 * fallback behavior, and conditional creation logic.
 */
class AgentscopeNacosReActAgentAutoConfigurationTest {

    private Model mockModel;
    private Memory mockMemory;
    private Toolkit mockToolkit;
    private AiService mockAiService;

    @BeforeEach
    void setUp() {
        mockModel = mock(Model.class);
        mockMemory = mock(Memory.class);
        mockToolkit = mock(Toolkit.class);
        mockAiService = mock(AiService.class);
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(AgentscopeNacosReActAgentAutoConfiguration.class))
                .withBean(Model.class, () -> mockModel)
                .withBean(Memory.class, () -> mockMemory)
                .withBean(Toolkit.class, () -> mockToolkit)
                .withBean(NacosPromptListener.class, () -> new NacosPromptListener(mockAiService));
    }

    @Test
    @DisplayName("should create ReActAgent with Nacos prompt when config is available")
    void shouldCreateAgentWithNacosPrompt() throws Exception {
        Prompt prompt = new Prompt("test-agent", "1.0.0", "You are {{role}} in {{dept}}");
        when(mockAiService.subscribePrompt(eq("test-agent"), isNull(), isNull(), any()))
                .thenReturn(prompt);

        contextRunner()
                .withPropertyValues(
                        "agentscope.agent.name=TestBot",
                        "agentscope.agent.sys-prompt=Default prompt",
                        "agentscope.agent.max-iters=5",
                        "agentscope.nacos.prompt.enabled=true",
                        "agentscope.nacos.prompt.sys-prompt-key=test-agent",
                        "agentscope.nacos.prompt.variables.role=Helper",
                        "agentscope.nacos.prompt.variables.dept=Engineering")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ReActAgent.class);
                            ReActAgent agent = context.getBean(ReActAgent.class);
                            assertThat(agent.getName()).isEqualTo("TestBot");
                            assertThat(agent.getSysPrompt())
                                    .isEqualTo("You are Helper in Engineering");
                        });
    }

    @Test
    @DisplayName("should create ReActAgent with versioned Nacos prompt")
    void shouldCreateAgentWithVersionedPrompt() throws Exception {
        Prompt prompt = new Prompt("test-agent", "2.0.0", "V2: You are {{role}}");
        when(mockAiService.subscribePrompt(eq("test-agent"), eq("2.0.0"), isNull(), any()))
                .thenReturn(prompt);

        contextRunner()
                .withPropertyValues(
                        "agentscope.agent.name=VersionBot",
                        "agentscope.agent.sys-prompt=Default prompt",
                        "agentscope.nacos.prompt.enabled=true",
                        "agentscope.nacos.prompt.sys-prompt-key=test-agent",
                        "agentscope.nacos.prompt.version=2.0.0",
                        "agentscope.nacos.prompt.variables.role=Assistant")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ReActAgent.class);
                            ReActAgent agent = context.getBean(ReActAgent.class);
                            assertThat(agent.getSysPrompt()).isEqualTo("V2: You are Assistant");
                        });
    }

    @Test
    @DisplayName("should create ReActAgent with labeled Nacos prompt")
    void shouldCreateAgentWithLabeledPrompt() throws Exception {
        Prompt prompt = new Prompt("test-agent", "1.0.0", "Prod: You are {{role}}");
        when(mockAiService.subscribePrompt(eq("test-agent"), isNull(), eq("prod"), any()))
                .thenReturn(prompt);

        contextRunner()
                .withPropertyValues(
                        "agentscope.agent.name=LabelBot",
                        "agentscope.agent.sys-prompt=Default prompt",
                        "agentscope.nacos.prompt.enabled=true",
                        "agentscope.nacos.prompt.sys-prompt-key=test-agent",
                        "agentscope.nacos.prompt.label=prod",
                        "agentscope.nacos.prompt.variables.role=Assistant")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ReActAgent.class);
                            ReActAgent agent = context.getBean(ReActAgent.class);
                            assertThat(agent.getSysPrompt()).isEqualTo("Prod: You are Assistant");
                        });
    }

    @Test
    @DisplayName("should fallback to YAML prompt when Nacos config is missing")
    void shouldFallbackToYamlPrompt() throws Exception {
        when(mockAiService.subscribePrompt(eq("missing"), isNull(), isNull(), any()))
                .thenReturn(null);

        contextRunner()
                .withPropertyValues(
                        "agentscope.agent.name=FallbackBot",
                        "agentscope.agent.sys-prompt=I am the YAML fallback prompt",
                        "agentscope.nacos.prompt.enabled=true",
                        "agentscope.nacos.prompt.sys-prompt-key=missing")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ReActAgent.class);
                            ReActAgent agent = context.getBean(ReActAgent.class);
                            assertThat(agent.getSysPrompt())
                                    .isEqualTo("I am the YAML fallback prompt");
                        });
    }

    @Test
    @DisplayName("should use YAML prompt when no sys-prompt-key is configured")
    void shouldUseYamlPromptWhenNoKey() {
        contextRunner()
                .withPropertyValues(
                        "agentscope.agent.name=NoKeyBot",
                        "agentscope.agent.sys-prompt=YAML only prompt",
                        "agentscope.nacos.prompt.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ReActAgent.class);
                            ReActAgent agent = context.getBean(ReActAgent.class);
                            assertThat(agent.getSysPrompt()).isEqualTo("YAML only prompt");
                        });
    }

    @Test
    @DisplayName("should not create ReActAgent when prompt is disabled")
    void shouldNotCreateAgentWhenDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(AgentscopeNacosReActAgentAutoConfiguration.class))
                .withPropertyValues("agentscope.nacos.prompt.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(ReActAgent.class);
                        });
    }

    @Test
    @DisplayName("should not replace existing ReActAgent bean")
    void shouldNotReplaceExistingAgent() {
        ReActAgent existingAgent =
                ReActAgent.builder()
                        .name("ExistingAgent")
                        .sysPrompt("Existing prompt")
                        .model(mockModel)
                        .memory(mockMemory)
                        .toolkit(mockToolkit)
                        .build();

        contextRunner()
                .withPropertyValues(
                        "agentscope.agent.name=NacosBot",
                        "agentscope.agent.sys-prompt=Nacos prompt",
                        "agentscope.nacos.prompt.enabled=true")
                .withBean(ReActAgent.class, () -> existingAgent)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ReActAgent.class);
                            assertThat(context.getBean(ReActAgent.class).getName())
                                    .isEqualTo("ExistingAgent");
                        });
    }
}

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
package io.agentscope.spring.boot.llm.interfacesweb;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.converter.ChatMessageConverter;
import io.agentscope.core.chat.completions.converter.OpenAIToolConverter;
import io.agentscope.core.chat.completions.streaming.ChatCompletionsStreamingAdapter;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicMessageConverter;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicResponseBuilder;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicStreamingAdapter;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicToolConverter;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesMessageConverter;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesResponseBuilder;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesStreamingAdapter;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesToolConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

@DisplayName("LLM Interfaces Web Auto Configuration Tests")
class LlmInterfacesWebAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner =
            new WebApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(LlmInterfacesWebAutoConfiguration.class))
                    .withBean(
                            ReActAgent.class, () -> ReActAgent.builder().name("testAgent").build())
                    .withPropertyValues("agentscope.llm-interfaces.enabled=true");

    @Test
    @DisplayName("Should create default beans when enabled")
    void shouldCreateDefaultBeansWhenEnabled() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(ChatMessageConverter.class);
                    assertThat(context).hasSingleBean(OpenAIToolConverter.class);
                    assertThat(context).hasSingleBean(ChatCompletionsResponseBuilder.class);
                    assertThat(context).hasSingleBean(ChatCompletionsStreamingAdapter.class);
                    assertThat(context).hasSingleBean(ResponsesMessageConverter.class);
                    assertThat(context).hasSingleBean(ResponsesToolConverter.class);
                    assertThat(context).hasSingleBean(ResponsesResponseBuilder.class);
                    assertThat(context).hasSingleBean(ResponsesStreamingAdapter.class);
                    assertThat(context).hasSingleBean(AnthropicMessageConverter.class);
                    assertThat(context).hasSingleBean(AnthropicToolConverter.class);
                    assertThat(context).hasSingleBean(AnthropicResponseBuilder.class);
                    assertThat(context).hasSingleBean(AnthropicStreamingAdapter.class);
                    assertThat(context).hasSingleBean(LlmInterfacesController.class);
                    assertThat(context.getBean(LlmInterfacesProperties.class).getBasePath())
                            .isEqualTo("/v1");
                });
    }

    @Test
    @DisplayName("Should not create beans when disabled")
    void shouldNotCreateBeansWhenDisabled() {
        contextRunner
                .withPropertyValues("agentscope.llm-interfaces.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(ChatMessageConverter.class);
                            assertThat(context).doesNotHaveBean(ResponsesMessageConverter.class);
                            assertThat(context).doesNotHaveBean(AnthropicMessageConverter.class);
                            assertThat(context).doesNotHaveBean(LlmInterfacesController.class);
                        });
    }
}

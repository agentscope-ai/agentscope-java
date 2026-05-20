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
package io.agentscope.spring.boot.responses.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.responses.builder.ResponsesResponseBuilder;
import io.agentscope.core.responses.converter.ResponsesGenerationOptionsConverter;
import io.agentscope.core.responses.converter.ResponsesInputConverter;
import io.agentscope.core.responses.converter.ResponsesToolConverter;
import io.agentscope.core.responses.streaming.ResponsesStreamingAdapter;
import io.agentscope.spring.boot.responses.service.ResponsesStateService;
import io.agentscope.spring.boot.responses.service.ResponsesStreamingService;
import io.agentscope.spring.boot.responses.web.ConversationsController;
import io.agentscope.spring.boot.responses.web.ResponsesController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ResponsesWebAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(ResponsesWebAutoConfiguration.class));

    @Test
    void shouldCreateResponsesApiBeansByDefault() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(ResponsesInputConverter.class);
                    assertThat(context).hasSingleBean(ResponsesToolConverter.class);
                    assertThat(context).hasSingleBean(ResponsesGenerationOptionsConverter.class);
                    assertThat(context).hasSingleBean(ResponsesResponseBuilder.class);
                    assertThat(context).hasSingleBean(ResponsesStreamingAdapter.class);
                    assertThat(context).hasSingleBean(ResponsesStreamingService.class);
                    assertThat(context).hasSingleBean(ResponsesStateService.class);
                    assertThat(context).hasSingleBean(ResponsesController.class);
                    assertThat(context).hasSingleBean(ConversationsController.class);
                    assertThat(context).hasSingleBean(ResponsesProperties.class);
                    assertThat(context.getBean(ResponsesProperties.class).getBasePath())
                            .isEqualTo("/v1/responses");
                });
    }

    @Test
    void shouldBindCustomBasePath() {
        contextRunner
                .withPropertyValues("agentscope.responses.base-path=/custom/responses")
                .run(
                        context ->
                                assertThat(context.getBean(ResponsesProperties.class).getBasePath())
                                        .isEqualTo("/custom/responses"));
    }

    @Test
    void shouldNotCreateResponsesApiBeansWhenDisabled() {
        contextRunner
                .withPropertyValues("agentscope.responses.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(ResponsesInputConverter.class);
                            assertThat(context).doesNotHaveBean(ResponsesController.class);
                            assertThat(context).doesNotHaveBean(ConversationsController.class);
                        });
    }
}

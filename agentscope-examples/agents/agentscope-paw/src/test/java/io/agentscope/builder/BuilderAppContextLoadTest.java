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
package io.agentscope.builder;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.claw2.Claw2App;
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;

/**
 * Boots the full Spring Boot context to catch wiring regressions that the unit-level {@link
 * BuilderBootstrapSmokeTest} cannot — e.g. logback appender class names, missing beans, or
 * misconfigured controllers. Stubs the {@link Model} so the test is offline.
 *
 * <p>The stub {@link Model} is registered via {@code @Import} on the static
 * {@link StubModelConfig} below. Auto-detection of nested {@code @TestConfiguration} only fires
 * when {@code @SpringBootTest#classes} is left empty; since we pin {@code classes = Claw2App.class}
 * to scope the context to the production app, the import has to be wired explicitly or the stub
 * never reaches the {@code Optional<Model>} injection point in {@code BuilderConfig}.
 */
@SpringBootTest(
        classes = Claw2App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BuilderAppContextLoadTest.StubModelConfig.class)
@TestPropertySource(
        properties = {
            "claw.home=${java.io.tmpdir}/agentscope-claw-context-load-test",
            "claw.dashscope.api-key="
        })
class BuilderAppContextLoadTest {

    @Autowired ApplicationContext context;
    @Autowired ClawBootstrap bootstrap;
    @Autowired ChatUiChannel chatUiChannel;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(bootstrap).isNotNull();
        assertThat(chatUiChannel).isNotNull();
        assertThat(bootstrap.channelManager().getChannel(ChatUiChannel.CHANNEL_ID)).isPresent();
    }

    @TestConfiguration
    static class StubModelConfig {
        @Bean
        @Primary
        Model stubModel() {
            Model model = Mockito.mock(Model.class);
            Mockito.when(model.getModelName()).thenReturn("stub-model");
            ChatResponse chunk =
                    new ChatResponse(
                            "stub-id",
                            List.of(TextBlock.builder().text("ok").build()),
                            null,
                            Map.of(),
                            "stop");
            Mockito.when(model.stream(Mockito.anyList(), Mockito.any(), Mockito.any()))
                    .thenReturn(Flux.just(chunk));
            return model;
        }
    }
}

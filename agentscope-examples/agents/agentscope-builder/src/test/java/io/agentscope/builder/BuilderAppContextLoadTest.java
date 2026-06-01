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

import io.agentscope.builder.runtime.BuilderBootstrap;
import io.agentscope.builder.runtime.channel.chatui.ChatUiChannel;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.store.BaseStore;
import io.agentscope.harness.agent.store.InMemoryStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;

/**
 * Boots the full Spring Boot context to catch wiring regressions that the unit-level {@link
 * BuilderBootstrapSmokeTest} cannot — e.g. logback appender class names, missing beans, or
 * misconfigured controllers. Stubs the {@link Model} so the test is offline.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "builder.jwt.secret=test-jwt-secret-must-be-at-least-32-characters-long",
            "builder.workspace=${java.io.tmpdir}/agentscope-builder-context-load-test",
            "builder.dashscope.api-key=",
            // Keep the test hermetic: avoid writing the default H2 file under ${user.home}.
            "spring.datasource.url=jdbc:h2:mem:builder-ctx-load;DB_CLOSE_DELAY=-1;MODE=MYSQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            // Skip the dev-only bob/alice demo seed so assertions about user counts stay stable.
            "spring.sql.init.mode=never"
        })
class BuilderAppContextLoadTest {

    @Autowired ApplicationContext context;
    @Autowired BuilderBootstrap bootstrap;
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

        // BaseStore is a hard dependency of the builder configuration after the
        // composite-only filesystem refactor. The Spring context cannot start without
        // one, so the test wires an InMemoryStore to back the per-(owner, agent)
        // RemoteFilesystem routes.
        @Bean
        BaseStore inMemoryStore() {
            return new InMemoryStore();
        }
    }
}

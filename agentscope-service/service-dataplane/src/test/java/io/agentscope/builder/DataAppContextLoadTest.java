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

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;

@SpringBootTest(classes = DataApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(DataAppContextLoadTest.StubModelConfig.class)
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "builder.jwt.secret=test-jwt-secret-must-be-at-least-32-characters-long",
            "builder.internal-token=test-internal-token-must-be-at-least-32-chars",
            "builder.dataplane.register-enabled=false",
            "builder.workspace=${java.io.tmpdir}/agentscope-builder-data-ctx",
            "builder.dashscope.api-key=",
            // INIT pre-creates the dp schema required by hibernate.default_schema (see
            // application.yml); without it schema-qualified H2 statements fail.
            "spring.datasource.url=jdbc:h2:mem:dataCtx;DB_CLOSE_DELAY=-1;MODE=MYSQL;INIT=CREATE"
                    + " SCHEMA IF NOT EXISTS dp",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.sql.init.mode=never"
        })
class DataAppContextLoadTest {

    @Test
    void contextLoads() {}

    @TestConfiguration
    static class StubModelConfig {
        @Bean
        Model model() {
            Model model = Mockito.mock(Model.class);
            Mockito.when(model.stream(Mockito.anyList(), Mockito.any(), Mockito.any()))
                    .thenReturn(
                            Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(TextBlock.builder().text("ok").build()))
                                            .build()));
            return model;
        }

        @Bean
        AgentStateStore agentStateStore() {
            return new InMemoryAgentStateStore();
        }

        @Bean
        BaseStore baseStore() {
            return new InMemoryStore();
        }
    }
}

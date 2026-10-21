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

import io.agentscope.builder.runtime.managed.ManagedConsumerLoop;
import io.agentscope.builder.runtime.managed.ManagedSessionLogStore;
import io.agentscope.builder.runtime.managed.ManagedTurnAcknowledgements;
import io.agentscope.builder.runtime.managed.RedisManagedInfrastructure;
import io.agentscope.builder.runtime.managed.RocketMqManagedMessageBus;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.session.SessionLogStore;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

@SpringBootTest(classes = DataApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(DataAppContextLoadTest.StubModelConfig.class)
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "builder.jwt.secret=test-jwt-secret-must-be-at-least-32-characters-long",
            "builder.internal-token=test-internal-token-must-be-at-least-32-chars",
            "builder.workspace=${java.io.tmpdir}/agentscope-service-agentscope-service-dataplane-ctx",
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
    @Autowired ApplicationContext context;

    // Explicit dependency doubles for this wiring-only test. Real transport/consumption has
    // separate protocol and replica acceptance; production never chooses these mocks.
    @MockitoBean(enforceOverride = true)
    RedisManagedInfrastructure redis;

    @MockitoBean(enforceOverride = true)
    RocketMqManagedMessageBus mq;

    @MockitoBean(enforceOverride = true)
    ManagedTurnAcknowledgements turnAcknowledgements;

    @MockitoBean(name = "managedBrainConsumer", enforceOverride = true)
    ManagedConsumerLoop turns;

    @MockitoBean(name = "managedResumeConsumer", enforceOverride = true)
    ManagedConsumerLoop resumes;

    @Test
    void contextLoads() {
        assertThat(context.getBean(SessionLogStore.class))
                .isInstanceOf(ManagedSessionLogStore.class);
        assertThat(List.of(context.getBeanDefinitionNames()))
                .doesNotContain(
                        "runtimeCommandStore",
                        "runtimeExecutionState",
                        "runtimeNativeExport",
                        "runtimeConfigurationStore",
                        "runtimeSandboxMaintenance",
                        "sessionTurnRunner",
                        "sessionTurnInbox",
                        "managedRuntimeSessions",
                        "dataPlaneSelfRegistration",
                        "pendingHandsToolService");
    }

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

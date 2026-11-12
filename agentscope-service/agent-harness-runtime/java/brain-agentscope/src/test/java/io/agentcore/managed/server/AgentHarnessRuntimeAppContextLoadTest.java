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
package io.agentcore.managed.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentcore.managed.harness.runtime.ExecutionAccess.Candidate;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentcore.managed.server.runtime.SessionServiceBrainWorker;
import io.agentcore.managed.server.runtime.managed.ManagedProcessOwnership;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeWriter;
import io.agentcore.managed.server.runtime.managed.ManagedSessionLogStore;
import io.agentcore.managed.server.runtime.managed.RedisManagedInfrastructure;
import io.agentcore.managed.server.runtime.managed.RocketMqManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.SessionStore;
import io.agentcore.managed.server.web.toolbus.ToolEventBus;
import io.agentcore.managed.server.web.workspace.SharedWorkspacePaths;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.session.SessionLogStore;
import io.agentscope.core.state.AgentStateStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

@SpringBootTest(
        classes = AgentHarnessRuntimeApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AgentHarnessRuntimeAppContextLoadTest.StubModelConfig.class)
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "managed-agents.jwt.secret=test-jwt-secret-must-be-at-least-32-characters-long",
            "managed-agents.internal-token=test-internal-token-must-be-at-least-32-chars",
            "managed-agents.workspace=${java.io.tmpdir}/managed-agents-managed-agents-agent-harness-runtime-ctx",
            "managed-agents.dashscope.api-key=",
            "managed-agents.object-store.root=${java.io.tmpdir}/managed-runtime-context-objects",
            // Any accidental persistence auto-configuration must fail instead of silently using H2.
            "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unreachable",
            "managed-agents.runtime.redis-uri=redis://127.0.0.1:1",
            "managed-agents.runtime.mq.endpoints=127.0.0.1:1"
        })
class AgentHarnessRuntimeAppContextLoadTest {
    @Autowired ApplicationContext context;
    @LocalServerPort int port;
    @MockitoBean SessionServiceBrainWorker worker;

    @Test
    void httpNotificationDecodesProtocolJsonNode() throws Exception {
        Mockito.when(worker.notify(Mockito.any(Candidate.class))).thenReturn(true);
        String body =
                """
                {"scope":{"tenant":"tenant","namespace":"default","sessionId":"session"},
                 "turnId":"turn", "trigger":{"kind":"ready","messageId":"message","queueVersion":"1"}}
                """;
        HttpRequest request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:" + port + "/api/internal/brain/notify"))
                        .header("Content-Type", "application/json")
                        .header(
                                "X-Managed-Agents-Internal-Token",
                                "test-internal-token-must-be-at-least-32-chars")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        // HTTP goes through the actual Boot codec and security configuration.
        assertThat(
                        HttpClient.newHttpClient()
                                .send(request, HttpResponse.BodyHandlers.ofString())
                                .statusCode())
                .isEqualTo(202);
        Mockito.verify(worker)
                .notify(
                        Mockito.argThat(
                                candidate ->
                                        candidate.trigger().path("kind").asText().equals("ready")));
    }

    @Test
    void contextLoads() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(AgentStateStore.class)).isEmpty();
        assertThat(context.getBeansOfType(RedisManagedInfrastructure.class)).isEmpty();
        assertThat(context.getBeansOfType(RocketMqManagedMessageBus.class)).isEmpty();
        assertThat(context.getBeansOfType(ManagedProcessOwnership.class)).isEmpty();
        assertThat(context.getBeansOfType(SessionStore.class)).isEmpty();
        assertThat(context.getBean(SessionServiceClient.class)).isNotNull();
        assertThat(context.getBean(ManagedRuntimeWriter.class)).isNotNull();
        assertThat(context.getBean(ToolEventBus.class)).isNotNull();
        assertThat(context.getBean(SharedWorkspacePaths.class)).isNotNull();
        for (String serviceType :
                List.of("ManagedProjectionPublisher", "ManagedProjectionConsumer")) {
            assertThat(
                            context.getClassLoader()
                                    .getResource(
                                            "io/agentcore/managed/server/runtime/managed/"
                                                    + serviceType
                                                    + ".class"))
                    .as("Session Service implementation must stay outside the Brain classpath")
                    .isNull();
        }
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
                        "pendingToolWorkerToolService");
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
    }
}

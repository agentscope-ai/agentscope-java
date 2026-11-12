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
package io.agentcore.managed.server.agentservice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.RemoteRuntimeObjectStore;
import io.agentcore.managed.server.runtime.managed.RuntimeObjectStore;
import io.agentcore.managed.server.web.auth.InternalTokenAuthFilter;
import io.agentscope.harness.agent.HarnessAgent;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@TestPropertySource(locations = "classpath:application-test.properties")
@SpringBootTest(
        classes = AgentServiceApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "managed-agents.local-dev=false",
            "spring.datasource.url=jdbc:h2:mem:agent-service-http-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "management.endpoints.web.exposure.include=health,info,metrics",
            "managed-agents.internal-token=portal-test-internal-token-at-least-32-characters"
        })
@ActiveProfiles("test")
class AgentServiceAppTest extends AgentServiceInfrastructureTestSupport {
    @Autowired ApplicationContext context;

    @Value("${local.server.port}")
    int port;

    @Test
    void agentServiceStartsIndependentlyWithoutExecutionComponents() {
        assertTrue(context.getBeansOfType(HarnessAgent.class).isEmpty());
        assertFalse(context.containsBean("sessionTurnRunner"));
        assertFalse(context.containsBean("harnessAgentBuildService"));
        client().get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void protocolProbeRequiresTheInternalServiceIdentity() {
        client().post()
                .uri("/api/v1/runtime-hosts/register")
                .bodyValue(Map.of("hostKey", "test"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client().get()
                .uri("/api/internal/runtime-protocol")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client().get()
                .uri("/api/internal/runtime-protocol")
                .header(
                        InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER,
                        "portal-test-internal-token-at-least-32-characters")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.version")
                .isEqualTo(1)
                .jsonPath("$.role")
                .isEqualTo("agent-service");
    }

    @Test
    void metricsRequireInternalIdentity() {
        client().get().uri("/actuator/metrics").exchange().expectStatus().isUnauthorized();
        client().get()
                .uri("/actuator/metrics")
                .header(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, "wrong")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client().get()
                .uri("/actuator/metrics")
                .header(
                        InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER,
                        "portal-test-internal-token-at-least-32-characters")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.names")
                .isArray();
    }

    @Test
    void sessionServiceUsesBearerIdentityAndTheProtocolJsonCodec() {
        var scope = new ManagedScope("tenant", "test", "http-codec");
        context.getBean(ManagedAdmissionStore.class).createSession(scope, "{}");
        var query = Map.of("scope", scope, "operation", "head", "arguments", Map.of());
        client().post()
                .uri("/internal/session-service/v0/prepared/query")
                .bodyValue(query)
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client().post()
                .uri("/internal/session-service/v0/prepared/query")
                .header("Authorization", "Bearer portal-test-internal-token-at-least-32-characters")
                .bodyValue(query)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.sequence")
                .isEqualTo(0);
        var client =
                new SessionServiceClient(
                        URI.create("http://localhost:" + port + "/internal/session-service/v0"),
                        "portal-test-internal-token-at-least-32-characters",
                        context.getBean(ObjectMapper.class),
                        Duration.ofSeconds(10));
        var objects =
                new RemoteRuntimeObjectStore(
                        client, "runtime", context.getBean(ObjectMapper.class));
        byte[] payload = new byte[2 * 1024 * 1024];
        var ref =
                objects.put(
                        scope.messageGroup(),
                        "recovery",
                        RuntimeObjectStore.sha256(payload),
                        payload);
        assertArrayEquals(payload, objects.read(ref));
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }
}

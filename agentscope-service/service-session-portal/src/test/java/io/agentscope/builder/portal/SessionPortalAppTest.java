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
package io.agentscope.builder.portal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        classes = SessionPortalApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "builder.local-dev=false",
            "builder.internal-token=portal-test-internal-token-at-least-32-characters"
        })
@ActiveProfiles("test")
class SessionPortalAppTest extends PortalInfrastructureTestSupport {
    @Autowired ApplicationContext context;

    @Value("${local.server.port}")
    int port;

    @Test
    void sessionPortalStartsIndependentlyWithoutExecutionComponents() {
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
                .isEqualTo("session_portal");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }
}

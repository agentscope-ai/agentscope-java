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
package io.agentcore.managed.server.agentservice.config;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

class AgentServiceReadinessSecurityTest {
    @Test
    void probeGroupsArePublicWhileMetricsAndInternalRoutesKeepTheirAuthorization() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "fixture",
                                    Map.of(
                                            "managed-agents.local-dev", false,
                                            "managed-agents.jwt.secret",
                                                    "fixture-jwt-secret-must-be-at-least-32-characters",
                                            "managed-agents.internal-token",
                                                    "fixture-internal-token-must-be-at-least-32-characters")));
            context.register(AgentServiceSecurityConfig.class, Probe.class);
            context.refresh();
            var client = WebTestClient.bindToApplicationContext(context).build();
            client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
            client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
            client.get().uri("/actuator/health/db").exchange().expectStatus().isUnauthorized();
            client.get().uri("/actuator/metrics").exchange().expectStatus().isUnauthorized();
            client.get().uri("/api/internal/probe").exchange().expectStatus().isUnauthorized();
            client.get()
                    .uri("/actuator/metrics")
                    .header(
                            "X-Managed-Agents-Internal-Token",
                            "fixture-internal-token-must-be-at-least-32-characters")
                    .exchange()
                    .expectStatus()
                    .isOk();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class Probe {
        @Bean
        RouterFunction<ServerResponse> routes() {
            return RouterFunctions.route()
                    .GET(
                            "/actuator/health/readiness",
                            request -> ServerResponse.ok().bodyValue(Map.of("status", "UP")))
                    .GET(
                            "/actuator/health/liveness",
                            request -> ServerResponse.ok().bodyValue(Map.of("status", "UP")))
                    .GET("/actuator/health/db", request -> ServerResponse.ok().bodyValue("private"))
                    .GET("/actuator/metrics", request -> ServerResponse.ok().bodyValue("metrics"))
                    .GET(
                            "/api/internal/probe",
                            request -> ServerResponse.ok().bodyValue("internal"))
                    .build();
        }
    }
}

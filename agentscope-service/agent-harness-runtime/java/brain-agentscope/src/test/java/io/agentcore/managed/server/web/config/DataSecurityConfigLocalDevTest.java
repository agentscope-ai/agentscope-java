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
package io.agentcore.managed.server.web.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentcore.managed.server.web.auth.JwtService;
import io.jsonwebtoken.Claims;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/** HarnessRuntime resource security stays intact after moving Session/Worker ingress out of the process. */
class DataSecurityConfigLocalDevTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void resourcesUseExplicitLocalIdentityOrUserJwtAndNeverWorkerCredentials(boolean localDev) {
        try (var context = new AnnotationConfigApplicationContext()) {
            var jwt = mock(JwtService.class);
            var claims = mock(Claims.class);
            when(jwt.parse("fixture-token")).thenReturn(claims);
            when(jwt.extractUserId(claims)).thenReturn("native-user");
            when(jwt.extractRoles(claims)).thenReturn(List.of("user"));
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "fixture",
                                    Map.of(
                                            "managed-agents.local-dev",
                                            localDev,
                                            "managed-agents.internal-token",
                                            "fixture-internal")));
            context.registerBean(JwtService.class, () -> jwt);
            context.register(DataSecurityConfig.class, Probe.class);
            context.refresh();
            var client = WebTestClient.bindToApplicationContext(context).build();
            var anonymous = client.get().uri("/api/probe").exchange();
            if (localDev)
                anonymous
                        .expectStatus()
                        .isOk()
                        .expectBody(String.class)
                        .isEqualTo("local-developer");
            else anonymous.expectStatus().isUnauthorized();
            if (!localDev) {
                client.get().uri("/actuator/metrics").exchange().expectStatus().isUnauthorized();
                client.get()
                        .uri("/actuator/metrics")
                        .header("Authorization", "Bearer fixture-token")
                        .exchange()
                        .expectStatus()
                        .isForbidden();
                client.get()
                        .uri("/actuator/metrics")
                        .header("X-Managed-Agents-Internal-Token", "fixture-internal")
                        .exchange()
                        .expectStatus()
                        .isOk();
                client.get()
                        .uri("/api/probe")
                        .header("X-Managed-Agents-Environment-Key", "worker-key")
                        .exchange()
                        .expectStatus()
                        .isUnauthorized();
                client.get()
                        .uri("/api/probe")
                        .header("Authorization", "Bearer fixture-token")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(String.class)
                        .isEqualTo("native-user");
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class Probe {
        @Bean
        RouterFunction<ServerResponse> probe() {
            return RouterFunctions.route()
                    .GET("/actuator/metrics", request -> ServerResponse.ok().bodyValue("metrics"))
                    .GET(
                            "/api/probe",
                            request ->
                                    request.principal()
                                            .flatMap(
                                                    principal ->
                                                            ServerResponse.ok()
                                                                    .bodyValue(
                                                                            principal.getName())))
                    .build();
        }
    }
}

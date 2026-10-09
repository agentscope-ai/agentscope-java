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
package io.agentscope.builder.web.config;

import io.agentscope.builder.web.auth.JwtService;
import java.security.Principal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;

class SchedulerSecurityConfigLocalDevTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyExplicitLocalModeAcceptsRequestsWithoutValidCredentials(boolean localDev) {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context, "builder.local-dev=" + localDev);
            context.register(TestConfig.class);
            context.refresh();
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            for (String path : new String[] {"/api/probe", "/api/internal/probe"}) {
                var response =
                        client.get()
                                .uri(path)
                                .header("Authorization", "Bearer invalid")
                                .header("X-Builder-Environment-Key", "invalid")
                                .exchange();
                if (localDev) {
                    response.expectStatus()
                            .isOk()
                            .expectBody(String.class)
                            .isEqualTo("local-developer");
                } else {
                    response.expectStatus().isUnauthorized();
                }
            }
        }
    }

    @Configuration
    @EnableWebFlux
    @Import({SchedulerSecurityConfig.class, Probe.class})
    static class TestConfig {
        @Bean
        JwtService jwtService() {
            return new JwtService("local-development-test-secret-at-least-32chars");
        }
    }

    @RestController
    static class Probe {
        @GetMapping({"/api/probe", "/api/internal/probe"})
        String probe(Principal principal) {
            return principal.getName();
        }
    }
}

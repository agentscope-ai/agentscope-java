// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package io.agentcore.managed.server.toolworker;

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

class ManagedMetricsSecurityTest {
    @Test
    void anonymousProbesDoNotExposeOtherHealthPaths() {
        try (var context =
                new AnnotationConfigApplicationContext(
                        ManagedToolWorkerSecurity.class, Probe.class)) {
            var client = WebTestClient.bindToApplicationContext(context).build();
            client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
            client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
            client.get().uri("/actuator/health/db").exchange().expectStatus().is4xxClientError();
            client.get().uri("/actuator/metrics").exchange().expectStatus().is4xxClientError();
        }
    }

    @Test
    void onlyInternalIdentityCanReadMetrics() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "fixture",
                                    Map.of("managed-agents.internal-token", "fixture-internal")));
            context.register(ManagedToolWorkerSecurity.class, Probe.class);
            context.refresh();
            var client = WebTestClient.bindToApplicationContext(context).build();
            client.get().uri("/actuator/metrics").exchange().expectStatus().is4xxClientError();
            client.get()
                    .uri("/actuator/metrics")
                    .header("X-Managed-Agents-Internal-Token", "wrong")
                    .exchange()
                    .expectStatus()
                    .is4xxClientError();
            client.get()
                    .uri("/actuator/metrics")
                    .header("X-Managed-Agents-Internal-Token", "fixture-internal")
                    .exchange()
                    .expectStatus()
                    .isOk();
            client.get()
                    .uri("/private")
                    .header("X-Managed-Agents-Internal-Token", "fixture-internal")
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class Probe {
        @Bean
        RouterFunction<ServerResponse> route() {
            return RouterFunctions.route()
                    .GET(
                            "/actuator/health/readiness",
                            request -> ServerResponse.ok().bodyValue("UP"))
                    .GET(
                            "/actuator/health/liveness",
                            request -> ServerResponse.ok().bodyValue("UP"))
                    .GET("/actuator/health/db", request -> ServerResponse.ok().bodyValue("private"))
                    .GET("/actuator/metrics", request -> ServerResponse.ok().bodyValue("metrics"))
                    .build();
        }
    }
}

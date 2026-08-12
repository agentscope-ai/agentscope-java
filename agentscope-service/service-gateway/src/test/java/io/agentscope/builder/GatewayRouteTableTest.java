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

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import reactor.test.StepVerifier;

@SpringBootTest(
        classes = GatewayApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "builder.control-plane-url=http://control:8081",
            "builder.data-plane-url=http://data:8082",
            "builder.scheduler-plane-url=http://scheduler:8083"
        })
class GatewayRouteTableTest {

    @Autowired RouteLocator routeLocator;

    @Test
    void routeTableCoversPlanesAndRejectsInternal() {
        StepVerifier.create(routeLocator.getRoutes().collectList())
                .assertNext(
                        routes -> {
                            assertThat(find(routes, "reject-internal").getUri())
                                    .isEqualTo(URI.create("no://op"));
                            assertThat(find(routes, "data-session-turn").getUri())
                                    .isEqualTo(URI.create("http://data:8082"));
                            assertThat(find(routes, "control-api").getUri())
                                    .isEqualTo(URI.create("http://control:8081"));
                            assertThat(find(routes, "scheduler-outbound").getUri())
                                    .isEqualTo(URI.create("http://scheduler:8083"));
                            assertThat(find(routes, "scheduler-channel-callbacks").getUri())
                                    .isEqualTo(URI.create("http://scheduler:8083"));
                            assertThat(find(routes, "reject-internal").getOrder())
                                    .isLessThan(find(routes, "data-session-turn").getOrder());
                            assertThat(find(routes, "data-session-turn").getOrder())
                                    .isLessThan(find(routes, "control-api").getOrder());
                            assertThat(find(routes, "scheduler-channel-callbacks").getOrder())
                                    .isLessThan(find(routes, "control-api").getOrder());
                        })
                .verifyComplete();
    }

    private static Route find(List<Route> routes, String id) {
        return routes.stream()
                .filter(r -> id.equals(r.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing route id: " + id));
    }
}

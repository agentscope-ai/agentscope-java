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
package io.agentscope.harness.claw.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * WebFlux router configuration for the React SPA fallback.
 *
 * <p>Any request that:
 * <ul>
 *   <li>Does NOT start with {@code /api}
 *   <li>Does NOT contain a file extension (i.e. is a SPA deep-link)
 * </ul>
 * is forwarded to {@code /static/index.html} so the React router can handle navigation client-side.
 *
 * <p>Static assets (JS, CSS, images) with extensions are served directly by Spring's default static
 * resource handler from {@code classpath:/static/}.
 */
@Configuration
public class WebConfig {

    @Bean
    public RouterFunction<ServerResponse> spaFallback() {
        ClassPathResource indexHtml = new ClassPathResource("/static/index.html");
        return RouterFunctions.route()
                .GET(
                        request -> {
                            String path = request.path();
                            return !path.startsWith("/api")
                                    && !path.contains(".")
                                    && !path.startsWith("/actuator");
                        },
                        request ->
                                ServerResponse.ok()
                                        .contentType(MediaType.TEXT_HTML)
                                        .bodyValue(indexHtml))
                .build();
    }
}

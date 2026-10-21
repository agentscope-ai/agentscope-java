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

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer.PathSegment;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Deployment gate for user file APIs when no shared user-file backend is provisioned. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class UserFilesFilter implements WebFilter {
    private static final Set<String> FILE_SEGMENTS =
            Set.of(
                    "file",
                    "files",
                    "upload",
                    "uploads",
                    "artifact",
                    "artifacts",
                    "workspace",
                    "workspaces",
                    "attachments");
    private final boolean enabled;

    public UserFilesFilter(
            @Value("${builder.user-files-enabled:${BUILDER_USER_FILES_ENABLED:true}}")
                    boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var path = exchange.getRequest().getPath().pathWithinApplication();
        boolean api = path.value().startsWith("/api/") || path.value().startsWith("/v1/");
        boolean file =
                path.elements().stream()
                        .anyMatch(
                                element ->
                                        element instanceof PathSegment segment
                                                && FILE_SEGMENTS.contains(segment.valueToMatch()));
        if (enabled || !api || !file) return chain.filter(exchange);
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body =
                "{\"code\":\"user_files_disabled\",\"message\":\"User file features are disabled on this deployment\"}"
                        .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}

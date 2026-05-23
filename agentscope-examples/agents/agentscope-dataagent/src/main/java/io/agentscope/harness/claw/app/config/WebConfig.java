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
package io.agentscope.harness.claw.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * is forwarded to {@code classpath:/static/index.html} so the React router can handle navigation
 * client-side.
 *
 * <p>When the frontend has not been built yet (IDE / dev mode without {@code npm run build}), a
 * lightweight placeholder page is served instead so the API endpoints remain fully functional.
 *
 * <p>Static assets (JS, CSS, images) with extensions are served directly by Spring's default
 * static resource handler from {@code classpath:/static/}.
 */
@Configuration
public class WebConfig {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    private static final String DEV_PLACEHOLDER =
            """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="UTF-8"/>
              <title>agentscope-claw — dev mode</title>
              <style>
                body { font-family: system-ui, sans-serif; max-width: 640px;
                       margin: 80px auto; padding: 0 24px; color: #333; }
                code { background: #f4f4f4; padding: 2px 6px; border-radius: 4px;
                       font-size: 0.9em; }
                pre  { background: #f4f4f4; padding: 16px; border-radius: 8px;
                       overflow-x: auto; }
                a    { color: #0070f3; }
              </style>
            </head>
            <body>
              <h1>🐾 agentscope-claw</h1>
              <p>The backend is running, but the React frontend has <strong>not been built</strong> yet.</p>
              <p>The API is fully available — you can call it directly:</p>
              <pre>curl -X POST http://localhost:8080/api/auth/login \\
                -H "Content-Type: application/json" \\
                -d '{"username":"admin","password":"admin123"}'</pre>
              <p>To build the frontend, run:</p>
              <pre>cd agentscope-claw/frontend
            npm install &amp;&amp; npm run build</pre>
              <p>Or skip the frontend and use the API directly — all <code>/api/**</code> endpoints work.</p>
              <hr/>
              <p><a href="/actuator/health">Actuator health</a></p>
            </body>
            </html>
            """;

    @Bean
    public RouterFunction<ServerResponse> spaFallback() {
        ClassPathResource indexHtml = new ClassPathResource("/static/index.html");
        boolean frontendBuilt = indexHtml.exists();

        if (!frontendBuilt) {
            log.warn(
                    "Frontend not built: classpath:/static/index.html is missing. "
                            + "SPA routes will serve a dev placeholder. "
                            + "Run 'npm run build' in agentscope-claw/frontend to build the UI.");
        }

        return RouterFunctions.route()
                .GET(
                        request -> {
                            String path = request.path();
                            return !path.startsWith("/api")
                                    && !path.contains(".")
                                    && !path.startsWith("/actuator");
                        },
                        request -> {
                            if (frontendBuilt) {
                                return ServerResponse.ok()
                                        .contentType(MediaType.TEXT_HTML)
                                        .bodyValue(indexHtml);
                            }
                            return ServerResponse.ok()
                                    .contentType(MediaType.TEXT_HTML)
                                    .bodyValue(DEV_PLACEHOLDER);
                        })
                .build();
    }
}

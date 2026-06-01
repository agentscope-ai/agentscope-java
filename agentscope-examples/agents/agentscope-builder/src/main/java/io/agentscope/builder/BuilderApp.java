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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the agentscope-builder platform.
 *
 * <p>Starts a Spring Boot WebFlux server that hosts:
 *
 * <ul>
 *   <li>{@code /api/auth/**}, {@code /api/user/**} — JWT authentication + profile
 *   <li>{@code /api/agents/**} — agent catalog, per-agent chat (SSE), workspace CRUD,
 *       sessions inbox, channel bindings
 *   <li>{@code /api/channels} — registered channels directory
 *   <li>{@code /api/templates/**} — bundled starter templates
 *   <li>{@code /**} — React SPA static assets with SPA fallback to {@code index.html}
 * </ul>
 */
@SpringBootApplication
public class BuilderApp {
    public static void main(String[] args) {
        SpringApplication.run(BuilderApp.class, args);
    }
}

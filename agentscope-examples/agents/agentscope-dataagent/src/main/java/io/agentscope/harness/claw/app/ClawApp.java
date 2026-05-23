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
package io.agentscope.harness.claw.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the agentscope-claw multi-tenant chat application.
 *
 * <p>Starts a Spring Boot WebFlux server serving:
 *
 * <ul>
 *   <li>{@code /api/auth/**} — JWT authentication endpoints
 *   <li>{@code /api/chat/**} — SSE streaming chat and synchronous send
 *   <li>{@code /api/sessions/**} — per-user session management
 *   <li>{@code /api/user/**} — user profile, bindings, identity links, skills
 *   <li>{@code /api/admin/**} — admin-only observability (read-only runtime data)
 *   <li>{@code /**} — React SPA static assets with SPA fallback to {@code index.html}
 * </ul>
 */
@SpringBootApplication
public class ClawApp {

    public static void main(String[] args) {
        SpringApplication.run(ClawApp.class, args);
    }
}

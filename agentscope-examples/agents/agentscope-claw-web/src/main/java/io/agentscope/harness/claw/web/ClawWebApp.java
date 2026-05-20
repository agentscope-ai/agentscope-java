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
package io.agentscope.harness.claw.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the agentscope-claw-web admin console.
 *
 * <p>Starts a Spring Boot WebFlux server serving a read-only admin UI:
 *
 * <ul>
 *   <li>{@code /api/admin/overview} — platform overview (proxied from agentscope-claw runtime)
 *   <li>{@code /api/admin/sessions} — active sessions (proxied)
 *   <li>{@code /api/admin/channels} — channel state (proxied)
 *   <li>{@code /api/admin/users} — user list (from shared users.json)
 *   <li>{@code /api/admin/config/**} — agent config read-only view
 *   <li>{@code /api/admin/debug/**} — debug info (log stream URL)
 *   <li>{@code /**} — React admin SPA static assets
 * </ul>
 *
 * <p>Admin users authenticate against agentscope-claw's {@code /api/auth/login} and use the
 * resulting JWT here (same signing secret).
 */
@SpringBootApplication
public class ClawWebApp {

    public static void main(String[] args) {
        SpringApplication.run(ClawWebApp.class, args);
    }
}

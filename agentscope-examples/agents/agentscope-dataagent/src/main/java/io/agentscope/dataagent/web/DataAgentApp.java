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
package io.agentscope.dataagent.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the agentscope-dataagent Spring Boot application.
 *
 * <p>DataAgent is a multi-tenant, distributed-deployable agent product built on
 * {@code HarnessAgent}. It hosts a curated built-in {@code data-agent} (SQL / chart /
 * explorer / report-writer) available to every tenant out of the box, plus per-user
 * custom data agents in fully-isolated workspaces.
 *
 * <p>The primary UX is the React SPA served from {@code classpath:/static/}. External
 * IM and ticketing systems can invoke a tenant's DataAgent through side-channel
 * adapters (DingTalk, generic webhook) that land in the same per-user session store.
 *
 * <p>This class is intentionally minimal — all wiring lives in the {@code runtime/}
 * and {@code web/} packages and is picked up via component scanning.
 */
@SpringBootApplication(scanBasePackages = "io.agentscope.dataagent")
public class DataAgentApp {

    public static void main(String[] args) {
        SpringApplication.run(DataAgentApp.class, args);
    }
}

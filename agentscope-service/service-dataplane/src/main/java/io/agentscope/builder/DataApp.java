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

import io.agentscope.builder.web.config.BuilderE2bProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the builder <b>data plane</b>.
 *
 * <p>Hosts everything that executes agent turns against live session state:
 *
 * <ul>
 *   <li>{@code /api/sessions/{id}/events/**} — inbound user events (message / interrupt / tool
 *       confirmation / tool results), persisted event log, SSE streaming
 *   <li>{@code /api/sessions/{id}/hands-stats} — hands (sandbox lease) metrics
 *   <li>{@code /api/environments/{id}/work/**} — environment-worker work-queue operations
 *       (environment-key authenticated)
 *   <li>{@code /api/environments/{id}/sessions/{sessionId}/pending-tools|tool-results|skills} —
 *       self-hosted worker session operations (environment-key authenticated)
 *   <li>{@code /agentscope/**} — aistio data-plane HTTP contract (info / health / sessions /
 *       context / messages / compress / terminate) plus self-registration with aistiod
 * </ul>
 *
 * <p>The data plane instantiates {@code HarnessAgent} instances from the version snapshot pinned
 * on each session row, loads their state and runs all inference. It never creates sessions and
 * never manages static catalog definitions — both belong to the control plane.
 */
@SpringBootApplication(
        scanBasePackages = {
            "io.agentscope.builder.control",
            "io.agentscope.builder.web.api",
            "io.agentscope.builder.web.auth",
            "io.agentscope.builder.web.catalog",
            "io.agentscope.builder.web.config",
            "io.agentscope.builder.web.coord",
            "io.agentscope.builder.web.managed",
            "io.agentscope.builder.web.persistence",
            "io.agentscope.builder.web.share",
            "io.agentscope.builder.web.toolbus",
            "io.agentscope.builder.web.workspace"
        })
@EnableConfigurationProperties(BuilderE2bProperties.class)
@EnableScheduling
public class DataApp {
    public static void main(String[] args) {
        SpringApplication.run(DataApp.class, args);
    }
}

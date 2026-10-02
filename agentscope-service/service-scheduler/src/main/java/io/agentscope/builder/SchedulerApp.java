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
 * Entry point for the builder <b>scheduler plane</b>.
 *
 * <p>Hosts everything that executes work outside the request path of the other planes:
 *
 * <ul>
 *   <li><b>IM channel runtimes</b> (DingTalk, Feishu, WeCom, GitHub, GitLab) — channel
 *       configuration is pulled from the control plane's internal API; inbound messages are
 *       bridged into managed sessions (control plane find-or-create, data plane turn events)
 *   <li><b>Outbound delivery</b> — {@code /api/outbound/send} pushes agent-initiated messages
 *       into the registered channels
 *   <li><b>Cron deployments</b> — due detection + fire leases, then control-plane fire API
 *   <li><b>Hands workers</b> — {@code io.agentscope.builder.worker.HandsWorkerMain} is the
 *       standalone self-hosted environment worker entry point shipped in the same jar
 * </ul>
 *
 * <p>The scheduler never builds {@code HarnessAgent} instances (data plane) and never manages
 * catalog definitions or session lifecycle (control plane).
 */
@SpringBootApplication(
        scanBasePackages = {
            "io.agentscope.builder.runtime",
            "io.agentscope.builder.web.auth",
            "io.agentscope.builder.web.catalog",
            "io.agentscope.builder.web.config",
            "io.agentscope.builder.web.coord",
            "io.agentscope.builder.web.managed",
            "io.agentscope.builder.web.persistence",
            "io.agentscope.builder.web.share",
            "io.agentscope.builder.worker"
        })
public class SchedulerApp {
    public static void main(String[] args) {
        SpringApplication.run(SchedulerApp.class, args);
    }
}
